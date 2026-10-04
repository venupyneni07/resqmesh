"""Inspectable deterministic observations. These are not authenticity verdicts."""
from collections import Counter, defaultdict
from difflib import SequenceMatcher
import re


def verification_signals(reports: list[dict], related_reports: list[dict] | None = None) -> list[dict]:
    incident_ids = [r["id"] for r in reports]
    related_reports = [r for r in (related_reports or []) if r["id"] not in incident_ids]
    reports = list({r["id"]: r for r in reports + related_reports}.values())
    ids = [r["id"] for r in reports]
    origins = Counter(r["origin_id"] for r in reports)
    signals = [{"id": "source_claims", "code": "source_claims", "severity": "info",
                "description": "Anonymous node IDs identify claimed origins, not independently verified witnesses.",
                "report_ids": ids, "observed": {"report_count": len(reports), "claimed_origin_count": len(origins),
                    "source_independence": "unknown", "origin_report_counts": dict(origins),
                    "incident_report_ids": incident_ids, "related_context_report_ids": [r["id"] for r in related_reports]}}]
    if any(count > 1 for count in origins.values()):
        signals.append({"id": "repeated_origin", "code": "repeated_origin", "severity": "review",
                        "description": "Multiple reports claim the same origin. They may be updates and do not establish independent corroboration.",
                        "report_ids": [r["id"] for r in reports if origins[r["origin_id"]] > 1],
                        "observed": {"origin_report_counts": {k: v for k, v in origins.items() if v > 1}}})
    texts = defaultdict(list)
    for report in reports:
        if report.get("message_source") == "preset":
            continue
        texts[re.sub(r"\s+", " ", report["text"].strip().casefold())].append(report["id"])
    repeated = [group for group in texts.values() if len(group) > 1]
    if repeated:
        signals.append({"id": "repeated_text", "code": "repeated_text", "severity": "review",
                        "description": "Different report IDs contain identical normalized text. This may be resubmission, not separate evidence.",
                        "report_ids": [item for group in repeated for item in group], "observed": {"groups": repeated}})
    # Bound CPU and model payload independently of the number of merged incident members.
    # Exact normalized matches already have their own signal; very short phrases are weak
    # evidence of copying and are excluded from this near-copy comparison.
    written_reports = [r for r in reports if r.get("message_source") != "preset"]
    compared = [(r["id"], " ".join(r["text"].casefold().split())) for r in written_reports[:20]]
    similar, matching_pair_count = [], 0
    for index, (left_id, left_text) in enumerate(compared):
        for right_id, right_text in compared[index + 1:]:
            if min(len(left_text), len(right_text)) < 20 or left_text == right_text:
                continue
            ratio = SequenceMatcher(None, left_text, right_text).ratio()
            if ratio >= .9:
                matching_pair_count += 1
                if len(similar) < 20:
                    similar.append({"report_ids": [left_id, right_id], "text_similarity": round(ratio, 3)})
    if similar:
        signals.append({"id": "similar_wording", "code": "similar_wording", "severity": "review",
                        "description": "Reports use similar wording. Templates, shared information, or updates can explain this; it is not proof of coordination or deception.",
                        "report_ids": sorted({i for pair in similar for i in pair["report_ids"]}),
                        "observed": {"pairs": similar, "comparison": "normalized text SequenceMatcher ratio >= 0.9; both texts at least 20 characters",
                                     "compared_report_count": len(compared), "omitted_report_count": max(0, len(written_reports) - len(compared)),
                                     "matching_pair_count": matching_pair_count, "pairs_truncated": matching_pair_count > len(similar)}})
    if len(reports) >= 3:
        received = [r["received_at"] for r in reports if r.get("received_at")]
        if len(received) == len(reports) and max(received) - min(received) <= 120_000:
            signals.append({"id": "clustered_arrival", "code": "clustered_arrival", "severity": "review",
                            "description": "Several reports reached the backend within two minutes. A reconnecting gateway can cause this; timing alone does not show coordinated abuse.",
                            "report_ids": ids, "observed": {"report_count": len(reports), "server_receipt_span_ms": max(received) - min(received)}})
    declared = [{"report_id": r["id"], "value": r["people_affected"]} for r in reports if r.get("people_affected") is not None]
    if len({item["value"] for item in declared}) > 1:
        signals.append({"id": "different_declared_counts", "code": "different_declared_counts", "severity": "review",
                        "description": "Sender-provided counts differ. They may describe different groups or times; never add them or infer deception.",
                        "report_ids": [item["report_id"] for item in declared], "observed": {"declared_counts": declared}})
    buildings = {r["building"].strip().casefold() for r in reports if r.get("building")}
    if len(buildings) > 1:
        signals.append({"id": "different_buildings", "code": "different_buildings", "severity": "review",
                        "description": "Structured building labels differ. Check whether this incident combines different locations.",
                        "report_ids": ids, "observed": {"buildings": sorted(buildings)}})
    preset_ids = [r["id"] for r in reports if r.get("message_source") == "preset"]
    if preset_ids:
        signals.append({"id": "preset_sos", "code": "preset_sos", "severity": "info",
                        "description": "The app supplied a generic SOS because no message was typed. Missing detail and shared preset wording do not establish abuse or low urgency.",
                        "report_ids": preset_ids, "observed": {"preset_report_count": len(preset_ids)}})
    missing = [r["id"] for r in reports if not any(r.get(k) for k in ("building", "zone", "location_text", "floor", "room"))
               and (r.get("location_context") or {}).get("latitude") is None]
    if missing:
        signals.append({"id": "missing_structured_location", "code": "missing_structured_location", "severity": "review",
                        "description": "No structured location was supplied for these reports; raw text may still describe a location.",
                        "report_ids": missing, "observed": {"missing_report_count": len(missing)}})
    signals.append({"id": "relay_provenance", "code": "relay_provenance", "severity": "info",
                    "description": "Relay copies are transport observations, not extra eyewitness reports. Paths are sender-reported and not cryptographically attested.",
                    "report_ids": ids, "observed": {"simulation": all(r["simulation"] for r in reports),
                        "paths": [{"report_id": r["id"], "relay_path": r["relay_path"]} for r in reports]}})
    return signals
