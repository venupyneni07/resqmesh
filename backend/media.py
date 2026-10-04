"""Bounded media ingestion; bytes remain separate from immutable SOS packets."""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import struct
import tempfile

from fastapi import Request

from .store import Store, StoreError


def validate_signature(path: Path, kind: str):
    """Check container signatures, not the truth of recorded events or codec safety."""
    size = path.stat().st_size
    with path.open("rb") as source:
        if kind == "image":
            start = source.read(3)
            source.seek(max(0, size - 2))
            if size < 5 or start != b"\xff\xd8\xff" or source.read(2) != b"\xff\xd9":
                raise StoreError(422, "Attachment is not a JPEG image")
            return

        boxes_seen = 0
        handlers = set()
        required = set()

        def boxes(start, end, depth=0):
            nonlocal boxes_seen
            position = start
            while position < end:
                boxes_seen += 1
                if boxes_seen > 10_000 or end - position < 8:
                    raise StoreError(422, "Malformed MP4 container")
                source.seek(position)
                length, name = struct.unpack(">I4s", source.read(8))
                header = 8
                if length == 1:
                    if end - position < 16:
                        raise StoreError(422, "Malformed MP4 box")
                    length = struct.unpack(">Q", source.read(8))[0]
                    header = 16
                elif length == 0:
                    length = end - position
                if length < header or position + length > end:
                    raise StoreError(422, "Malformed MP4 box length")
                payload = position + header
                if depth == 0:
                    if position == 0 and name != b"ftyp":
                        raise StoreError(422, "Attachment is not an MP4 recording")
                    if name == b"ftyp":
                        if length - header < 8:
                            raise StoreError(422, "Malformed MP4 file type")
                        source.seek(payload)
                        brands = source.read(min(length - header, 256))
                        accepted = {b"isom", b"iso2", b"mp41", b"mp42", b"M4A ", b"M4V ", b"avc1", b"3gp4", b"3gp5", b"3gp6"}
                        if not ({brands[:4]} | {brands[i:i+4] for i in range(8, len(brands), 4)}) & accepted:
                            raise StoreError(422, "Unsupported MP4 file type")
                        required.add(name)
                    elif name in (b"moov", b"mdat") and length > header:
                        required.add(name)
                if name in (b"moov", b"trak", b"mdia") and depth < 3:
                    boxes(payload, position + length, depth + 1)
                elif name == b"hdlr" and depth == 3 and length - header >= 12:
                    source.seek(payload + 8)
                    handlers.add(source.read(4))
                position += length

        boxes(0, size)
        expected = b"soun" if kind == "audio" else b"vide"
        if required != {b"ftyp", b"moov", b"mdat"} or expected not in handlers or (kind == "audio" and b"vide" in handlers):
            raise StoreError(422, "MP4 recording does not match the declared media kind")


async def receive_attachment(request: Request, store: Store, report_id: str, attachment_id: str) -> dict:
    manifest = store.attachment(report_id, attachment_id)
    if request.headers.get("content-type", "").split(";", 1)[0].strip().lower() != manifest["mime_type"]:
        raise StoreError(415, "Content-Type must match the report attachment manifest")
    content_length = request.headers.get("content-length")
    if content_length is not None:
        if not content_length.isdigit():
            raise StoreError(400, "Invalid Content-Length")
        if int(content_length) > manifest["byte_size"]:
            raise StoreError(413, "Attachment exceeds its declared byte size")
        if int(content_length) != manifest["byte_size"]:
            raise StoreError(422, "Attachment byte size does not match its manifest")
    final = store.media_path(report_id, attachment_id)
    final.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    staging: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(dir=final.parent, prefix=".upload-", delete=False) as output:
            staging = Path(output.name)
            digest = hashlib.sha256()
            received = 0
            async for chunk in request.stream():
                received += len(chunk)
                if received > manifest["byte_size"]:
                    raise StoreError(413, "Attachment exceeds its declared byte size")
                digest.update(chunk)
                output.write(chunk)
            if received != manifest["byte_size"]:
                raise StoreError(422, "Attachment byte size does not match its manifest")
            if digest.hexdigest() != manifest["sha256"]:
                raise StoreError(422, "Attachment SHA-256 does not match its manifest")
            output.flush()
            os.fsync(output.fileno())
        validate_signature(staging, manifest["kind"])
        duplicate = False
        try:
            # Exclusive atomic publication also handles concurrent duplicate uploads.
            os.link(staging, final)
        except FileExistsError:
            if final.stat().st_size != manifest["byte_size"] or hashlib.sha256(final.read_bytes()).hexdigest() != manifest["sha256"]:
                raise StoreError(409, "Stored attachment conflicts with this immutable manifest")
            duplicate = True
        if not duplicate:
            directory_fd = os.open(final.parent, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        store.record_attachment(report_id, manifest)
        return {**manifest, "report_id": report_id, "status": "available", "duplicate": duplicate}
    finally:
        if staging is not None:
            staging.unlink(missing_ok=True)
