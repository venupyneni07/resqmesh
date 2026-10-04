from __future__ import annotations

import re
import time
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_serializer, model_validator


QUICK_SOS_TEXT = "Help needed; details unavailable."
QUICK_NEED_LABELS = {"cannot_move": "Cannot move", "cannot_speak": "Cannot speak", "people_injured": "People injured"}
V3_FIELDS = ("message_source", "quick_needs", "location_context")
MEDIA_LIMITS = {"audio": 1_048_576, "image": 1_048_576, "video": 8_388_608}
MEDIA_MIME_TYPES = {"audio": "audio/mp4", "image": "image/jpeg", "video": "video/mp4"}


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Attachment(StrictModel):
    id: str
    kind: Literal["audio", "image", "video"]
    mime_type: Literal["audio/mp4", "image/jpeg", "video/mp4"]
    byte_size: int = Field(gt=0, le=8_388_608, strict=True)
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    duration_ms: int | None = Field(default=None, gt=0, le=30_000, strict=True)

    @field_validator("id")
    @classmethod
    def canonical_id(cls, value: str) -> str:
        if str(UUID(value)) != value:
            raise ValueError("attachment id must be a canonical lowercase UUID")
        return value

    @model_validator(mode="after")
    def media_consistency(self) -> "Attachment":
        if self.mime_type != MEDIA_MIME_TYPES[self.kind]:
            raise ValueError("attachment MIME type must match its kind")
        if self.byte_size > MEDIA_LIMITS[self.kind]:
            raise ValueError("attachment exceeds the size limit for its kind")
        if self.kind == "image" and self.duration_ms is not None:
            raise ValueError("images cannot declare a duration")
        if self.kind == "video" and self.duration_ms is not None and self.duration_ms > 15_000:
            raise ValueError("video duration exceeds 15 seconds")
        return self


class LocationContext(StrictModel):
    source: Literal["unknown", "manual", "saved", "device"] = "unknown"
    observed_at: int | None = Field(default=None, gt=0, strict=True)
    latitude: float | None = Field(default=None, ge=-90, le=90, allow_inf_nan=False, strict=True)
    longitude: float | None = Field(default=None, ge=-180, le=180, allow_inf_nan=False, strict=True)
    accuracy_m: float | None = Field(default=None, ge=0, allow_inf_nan=False, strict=True)

    @model_validator(mode="after")
    def location_consistency(self) -> "LocationContext":
        if self.source == "unknown":
            if any(value is not None for value in (self.observed_at, self.latitude, self.longitude, self.accuracy_m)):
                raise ValueError("unknown location cannot contain an observation or coordinates")
        elif self.observed_at is None:
            raise ValueError("known location source requires its original observation time")
        if (self.latitude is None) != (self.longitude is None):
            raise ValueError("latitude and longitude must be supplied together")
        if self.accuracy_m is not None and self.latitude is None:
            raise ValueError("location accuracy requires coordinates")
        if self.source == "device" and self.latitude is None:
            raise ValueError("device location requires coordinates")
        return self


class ReportPacket(StrictModel):
    schema_version: Literal[1, 2, 3, 4]
    id: UUID
    origin_id: str = Field(min_length=1, max_length=80)
    created_at: int = Field(gt=0, strict=True)
    expires_at: int = Field(gt=0, strict=True)
    text: str = Field(min_length=1, max_length=2000)
    building: str | None = Field(default=None, max_length=120)
    zone: str | None = Field(default=None, max_length=120)
    hop_count: int = Field(ge=0, le=8, strict=True)
    max_hops: int = Field(ge=1, le=8, strict=True)
    relay_path: list[str] = Field(min_length=1, max_length=9)
    simulation: bool = Field(strict=True)
    emergency_type: Literal["medical", "fire", "flood", "accident", "trapped", "safety_threat", "other"] | None = None
    location_text: str | None = Field(default=None, max_length=240)
    floor: str | None = Field(default=None, max_length=40)
    room: str | None = Field(default=None, max_length=40)
    people_affected: int | None = Field(default=None, ge=0, le=10000, strict=True)
    vulnerability: str | None = Field(default=None, max_length=240)
    message_source: Literal["user", "preset"] = "user"
    quick_needs: list[Literal["cannot_move", "cannot_speak", "people_injured"]] = Field(default_factory=list, max_length=3)
    location_context: LocationContext = Field(default_factory=LocationContext)
    attachments: list[Attachment] = Field(default_factory=list, max_length=3)

    @model_validator(mode="before")
    @classmethod
    def optional_v3_message(cls, value):
        if not isinstance(value, dict):
            return value
        if value.get("schema_version") != 4 and "attachments" in value:
            raise ValueError("attachments require schema_version 4")
        if value.get("schema_version") not in (3, 4):
            if any(key in value for key in V3_FIELDS):
                raise ValueError("quick SOS metadata requires schema_version 3 or 4")
            return value
        if type(value["schema_version"]) is not int:
            raise ValueError("schema_version 3 or 4 must be an integer")
        if "message_source" in value and value["message_source"] not in ("user", "preset"):
            raise ValueError("message_source must be user or preset when supplied")
        value = dict(value)
        text = value.get("text", "")
        if isinstance(text, str) and not text.strip():
            value.update(text=QUICK_SOS_TEXT, message_source="preset")
        return value

    @model_serializer(mode="wrap")
    def serialize_packet(self, handler):
        result = handler(self)
        if self.schema_version < 3:
            for key in V3_FIELDS:
                result.pop(key, None)
        if self.schema_version < 4:
            result.pop("attachments", None)
        return result

    @field_validator("text", "building", "zone", "location_text", "floor", "room", "vulnerability")
    @classmethod
    def usable_text(cls, value: str | None) -> str | None:
        if value is None:
            return None
        if not value.strip():
            raise ValueError("text fields must not be blank; use null for unknown metadata")
        if any(ord(char) < 32 and char not in "\n\r\t" for char in value):
            raise ValueError("control characters are not permitted")
        return value

    @model_validator(mode="after")
    def packet_consistency(self) -> "ReportPacket":
        nodes = [self.origin_id, *self.relay_path]
        if any(re.fullmatch(r"[A-Za-z0-9_.:-]{1,80}", node) is None for node in nodes):
            raise ValueError("node IDs must use letters, digits, underscore, period, colon or dash")
        if self.relay_path[0] != self.origin_id:
            raise ValueError("relay_path must begin at origin_id")
        if len(set(self.relay_path)) != len(self.relay_path):
            raise ValueError("relay_path cannot contain repeated nodes")
        if len(self.relay_path) != self.hop_count + 1 or self.hop_count > self.max_hops:
            raise ValueError("hop_count must match relay_path and not exceed max_hops")
        if self.expires_at <= self.created_at:
            raise ValueError("expires_at must be later than created_at")
        if len(self.quick_needs) != len(set(self.quick_needs)):
            raise ValueError("quick_needs must not contain duplicates")
        if len({item.id for item in self.attachments}) != len(self.attachments):
            raise ValueError("attachment IDs must be unique within a report")
        if sum(item.byte_size for item in self.attachments) > 10_485_760:
            raise ValueError("attachments together cannot exceed 10 MiB")
        if self.message_source == "preset" and self.text != QUICK_SOS_TEXT:
            raise ValueError("preset message must use the exact quick SOS text")
        if self.location_context.observed_at is not None and self.location_context.observed_at > self.created_at + 300_000:
            raise ValueError("location observation is more than five minutes after report creation")
        return self


class IncidentPatch(StrictModel):
    status: Literal["new", "acknowledged", "in_progress", "resolved"] | None = None
    category: str | None = Field(default=None, min_length=1, max_length=80)
    team: str | None = Field(default=None, min_length=1, max_length=80)

    @model_validator(mode="after")
    def nonempty(self) -> "IncidentPatch":
        if not self.model_fields_set:
            raise ValueError("provide at least one editable field")
        if "status" in self.model_fields_set and self.status is None:
            raise ValueError("status cannot be null")
        for value in (self.category, self.team):
            if value is not None and (not value.strip() or any(ord(c) < 32 for c in value)):
                raise ValueError("category and team must contain visible text")
        return self


class CorrelationDecision(StrictModel):
    decision: Literal["confirm", "reject"]


class GatewayHeartbeat(StrictModel):
    simulation: bool = Field(strict=True)


class VerificationAction(StrictModel):
    action: Literal["request_verification", "corroborated", "responder_verified", "false_closed"]
    notes: str | None = Field(default=None, max_length=2000)
    reviewer_label: str | None = Field(default=None, max_length=120)
    check_method: Literal["on_site", "callback", "independent_witness", "external_reference", "other"] | None = None
    evidence_reference: str | None = Field(default=None, max_length=1000)
    checked_at: int | None = Field(default=None, gt=0, strict=True)

    @field_validator("reviewer_label", "evidence_reference")
    @classmethod
    def readable_review_metadata(cls, value: str | None) -> str | None:
        if value is None:
            return None
        value = value.strip()
        if not value:
            raise ValueError("review metadata must not be blank; use null when not recorded")
        if any(ord(c) < 32 and c not in "\n\r\t" for c in value):
            raise ValueError("review metadata cannot contain control characters")
        return value

    @model_validator(mode="after")
    def decisive_notes(self) -> "VerificationAction":
        if self.notes is not None:
            self.notes = self.notes.strip()
            if any(ord(c) < 32 and c not in "\n\r\t" for c in self.notes):
                raise ValueError("notes cannot contain control characters")
        if self.action != "request_verification" and not self.notes:
            raise ValueError("notes are required for decisive verification actions")
        if self.checked_at is not None and self.checked_at > int(time.time() * 1000) + 300_000:
            raise ValueError("checked_at is more than five minutes in the future")
        return self


class Fact(StrictModel):
    field: Literal["emergency_type", "location", "people_affected", "vulnerable_person", "situation"]
    value: str = Field(min_length=1, max_length=240)
    quote: str = Field(min_length=1, max_length=400)
    source: Literal["text", "building", "zone", "emergency_type", "location_text", "floor", "room", "people_affected", "vulnerability", "quick_needs"]


class IntakeOutput(StrictModel):
    facts: list[Fact] = Field(max_length=12)
    uncertain_interpretations: list[str] = Field(max_length=8)
    missing_information: list[str] = Field(max_length=8)


class Evidence(StrictModel):
    report_id: str
    quote: str = Field(min_length=1, max_length=400)


class TriageOutput(StrictModel):
    summary: str = Field(min_length=1, max_length=1000)
    suggested_urgency: Literal["critical", "high", "medium", "low", "unknown"]
    urgency_reason: str = Field(min_length=1, max_length=800)
    response_category: str = Field(min_length=1, max_length=100)
    questions: list[str] = Field(max_length=6)
    acknowledgement_draft: str = Field(min_length=1, max_length=500)
    missing_information: list[str] = Field(max_length=8)
    evidence: list[Evidence] = Field(min_length=1, max_length=8)


class CorrelationProposal(StrictModel):
    candidate_incident_id: str
    confidence: float = Field(ge=0, le=1)
    reason: str = Field(min_length=1, max_length=800)
    evidence: list[Evidence] = Field(min_length=2, max_length=6)


class CorrelationOutput(StrictModel):
    suggestions: list[CorrelationProposal] = Field(max_length=6)


class SignalAssessment(StrictModel):
    signal_id: str
    assessment: str = Field(min_length=1, max_length=600)
    why_not_conclusive: str = Field(min_length=1, max_length=600)


class VerificationOutput(StrictModel):
    suggested_state: Literal["unverified", "corroborated", "conflicting_evidence", "suspicious_reporting_pattern"]
    summary: str = Field(min_length=1, max_length=1000)
    signal_assessments: list[SignalAssessment] = Field(min_length=1, max_length=8)
    evidence: list[Evidence] = Field(max_length=8)
    limitations: list[str] = Field(min_length=1, max_length=8)
    questions: list[str] = Field(max_length=6)
    source_independence: Literal["unknown"]
