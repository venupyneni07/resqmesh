# ResQMesh local API

Run from `resqmesh`: `.venv/bin/uvicorn backend.app:app --host 127.0.0.1 --port 8000`.
The dashboard is served at `/`; APIs are under `/api`. The hackathon runtime uses the local
backend and local Ollama model; no cloud deployment or cloud AI account is required.
The product UI is English-only. Original report text and AI transcripts retain their source
language; the API does not translate stored source evidence or select a UI language.
All timestamps are epoch milliseconds. IDs are UUID strings except device/node IDs.
Keep the service bound to loopback for the emulator demo. Local authentication modes are
described below; account support is not a claim of production emergency-service readiness.

## Local authentication and roles

`GET /api/auth/config` is public and returns
`{"mode":"local_demo" | "legacy_key" | "accounts", "production":false}` for local operation.
The `production` property reflects the configured guard mode, not a readiness assessment.

- With neither account database nor token configured, `local_demo` permits local API access.
- Without an account database, optional `RESQMESH_API_TOKEN` enables `legacy_key`: send
  `X-API-Key` on protected routes. This shared key has no individual responder identity.
- `RESQMESH_AUTH_DB_PATH` enables `accounts`, taking precedence over the shared-key mode.
  `scripts/accounts.py` provisions local users and node-bound gateway keys. User passwords
  are salted PBKDF2-HMAC-SHA256 hashes (600,000 iterations); session and gateway tokens are
  stored as SHA-256 hashes. Provisioning a password/role revokes that user's prior sessions;
  disabling an account revokes sessions and prevents authentication.

`/api/health`, `/api/auth/config` and `/api/auth/login` are public in all modes. `/` and static
dashboard assets are public, but protected report data still requires API authorization.
An HTTP header or cookie does not encrypt network traffic. This local setup does not deploy
a public endpoint; HTTPS/production guards exist in code but are separate from this rehearsal.

| Endpoint | Local account contract |
| --- | --- |
| `POST /api/auth/login` | JSON `{"username":"operator","password":"..."}`. Success returns `{name, role, node_id:null}` and an eight-hour `resqmesh_session` cookie. The cookie is HttpOnly, SameSite=Strict, path `/api`; Secure is enabled by the separate production guard. Invalid credentials return 401; accounts not configured returns 409. |
| `GET /api/auth/me` | Returns the authenticated account principal. In demo/shared-key mode returns `{name:"Local demo",role:"demo"}` after any required shared-key check. |
| `POST /api/auth/logout` | Revokes the current session, expires its cookie and returns `{"signed_out":true}`. In account mode this requires an authenticated human account. |

| Role | Allowed operations |
| --- | --- |
| `viewer` | Read protected state, reports/media and receipts; inspect own session and sign out. Human decisions and retries are forbidden. |
| `responder` | Read access plus incident status/assignment changes, human acknowledgement/verification, correlation decisions, report reanalysis and media-analysis retry. Cannot originate gateway report uploads. |
| `admin` | All implemented protected API operations. Account provisioning remains the local administration script, not a dashboard endpoint. |
| `gateway` | Upload reports, heartbeat its own node, retrieve receipts and get/put attachments for reports with an upload observation for that gateway. Cannot read `/api/state`, make human decisions, retry AI or use human session endpoints. |

A gateway uses its provisioned `X-API-Key` **and** matching `X-ResQMesh-Node-ID` on every
protected gateway request. The upload header must also match the final `relay_path` node.
Receipt/media access is rejected if that gateway has no matching report upload observation.
Authentication failure returns 401; insufficient role, wrong node binding or inaccessible
gateway report returns 403. A valid human session is checked before a supplied gateway key.

Account mode limits login to eight requests per client address per minute, and other API
requests to 240 per principal/client per minute. Limits are per process; excess returns 429
with `Retry-After: 60`. Cross-origin or `Sec-Fetch-Site: cross-site` mutations return 403.
JSON mutation bodies are limited to 32 KiB; attachment uploads remain bounded by their
manifest and the 8 MiB route limit. Sensitive API responses use `Cache-Control: no-store`.
Signed-in operator audit events use the account name. This authenticates an account, not
the reporter's real identity or the truth of a human-entered evidence reference.

## Ingest

`POST /api/reports` returns HTTP 202 after a durable database commit; inference is queued.

```json
{
  "schema_version": 3,
  "id": "d8b1c1f8-c1c6-49fa-a94c-4269b4a51531",
  "origin_id": "RQM-82F1",
  "created_at": 1791040000000,
  "expires_at": 1791043600000,
  "text": "Water is rising quickly on the ground floor. Three people trapped. One elderly person.",
  "building": "Demo apartment",
  "zone": "Ground floor",
  "emergency_type": "flood",
  "location_text": "East entrance",
  "floor": "Ground",
  "room": null,
  "people_affected": 3,
  "vulnerability": "One elderly person",
  "message_source": "user",
  "quick_needs": ["cannot_move"],
  "location_context": {
    "source": "manual",
    "observed_at": 1791040000000,
    "latitude": null,
    "longitude": null,
    "accuracy_m": null
  },
  "hop_count": 3,
  "max_hops": 8,
  "relay_path": ["RQM-82F1", "RQM-194C", "RQM-7A31", "RQM-409F"],
  "simulation": true
}
```

Use current timestamps when sending this example. `expires_at` must exceed `created_at`.
These anonymous IDs illustrate one observed path; the backend imposes no fixed node count or
gateway role. Versions 1 and 2 remain supported with their existing wire shape and replay
idempotency. Text-only Android reports use version 3; reports with media use version 4.
Expired new reports return 410; future creation more than five minutes ahead returns 422.
Node IDs use letters, digits, `_`, `.`, `:`, `-` (1–80 characters). Paths begin at the origin,
contain no repeats, and contain `hop_count + 1` nodes. `max_hops` is 1–8. Text is 1–2000
characters; metadata is at most 120 characters. Raw text is untrusted data, never HTML.
`emergency_type` is `medical | fire | flood | accident | trapped | safety_threat | other`.
`location_text` and `vulnerability` are at most 240 characters; `floor`/`room` at most 40.
`people_affected` is an optional integer from 0 to 10000, never a boolean or inferred default.
Use null for unknown fields; non-null text cannot be blank. These values are sender-provided
claims and remain separate from AI-extracted facts. An optional `X-ResQMesh-Node-ID` upload
header must match the last node in `relay_path`.

### Optional text and location in version 3

Version 3 permits an empty or whitespace-only `text`. It is stored as
`Help needed; details unavailable.` with `message_source: "preset"`. Android applies this
normalization before saving locally, so every relay carries the same immutable payload.
Typed text uses `message_source: "user"`. A preset source cannot label arbitrary text.
Versions 1 and 2 retain their nonblank-text requirement.

`quick_needs` contains unique values from `cannot_move`, `cannot_speak`, and `people_injured`.
These are explicit selections, not inferred diagnoses or victim counts. An unknown count
stays null. Selecting `people_injured` does not imply how many people are injured.

`location_context.source` is `unknown`, `manual`, `saved`, or `device`. Unknown location has
null timestamp, coordinates and accuracy. Other sources carry `observed_at`: the time the
location was entered/confirmed or the device fix was observed, not the time it was reused.
Saved coordinates can be old; responders must use the displayed timestamp and accuracy.
Latitude and longitude must be supplied together and be finite, within ±90 and ±180.
`accuracy_m` is nullable, finite and nonnegative, and requires coordinates. A device location
requires coordinates; manual or saved locations can instead use the existing address fields.
Observation time must be positive and no more than five minutes after report creation.
No location field is required to save or relay an SOS.

Preset wording is marked as app-generated in the response center. Missing details are
uncertainty, not evidence that a report is false. Preset wording alone is not used to
correlate unrelated incidents or flag repeated-message suspicion. Human verification and
receipt semantics remain unchanged.

```json
{"status":"accepted","report_id":"d8b1c1f8-c1c6-49fa-a94c-4269b4a51531","incident_id":"82489a6e-998e-4e4a-82d2-cced826c3556","duplicate":false,"receipt":{"id":"7e6f1c03-a30c-4d2c-b79d-86e274e5f3df","report_id":"d8b1c1f8-c1c6-49fa-a94c-4269b4a51531","incident_id":"82489a6e-998e-4e4a-82d2-cced826c3556","type":"backend_received","timestamp":1791040001000,"gateway_id":"RQM-409F","simulation":true,"issuer":"resqmesh_backend","trust":"backend_issued_unattested","relay_path":["RQM-82F1","RQM-194C","RQM-7A31","RQM-409F"]}}
```

A repeated ID with the same immutable payload returns the original/current incident and
`duplicate:true`, even if its transit path changed. Reusing an ID with changed text, source,
timestamps, metadata, simulation flag, or hop limit returns 409. Reports are never duplicated.

## Media attachments in version 4

Version 4 extends version 3 with an immutable `attachments` list (up to three, at most 10 MiB combined). Each manifest has `id` (canonical lowercase UUID), `kind` (`audio`, `image`, `video`), matching `mime_type` (`audio/mp4`, `image/jpeg`, `video/mp4`), `byte_size` (positive integer), `sha256` (64 lowercase hex characters), and nullable `duration_ms`. Audio/image are limited to 1 MiB each, video to 8 MiB. Supplied durations are at most 30000 ms for audio or 15000 ms for video; images have no duration. Versions 1–3 reject attachment fields rather than silently ignoring them.

The small report is accepted before media bytes. `PUT /api/reports/{report_id}/attachments/{attachment_id}` streams raw bytes using the manifest MIME type and applicable account/gateway/shared-key authorization. The manifest must already exist; size, SHA-256 and container kind must match. Atomic storage makes retries idempotent. Success returns the manifest plus `report_id`, `status: "available"` and `duplicate`. Files are kept beside the database in `<database-path>.media/`; back up both.

`GET /api/reports/{report_id}/attachments` returns `{ "attachments": [...] }`, adding `status: "pending" | "available"` to each manifest. `GET /api/reports/{report_id}/attachments/{attachment_id}` serves the actual file with authenticated byte-range support for playback. API authentication also applies to media; dashboard playback uses its session cookie or shared-key header and a temporary local object URL. No key goes in the URL. Report views add a separate `media` availability field without altering stored packet JSON.

`backend_received` confirms the SOS report only. It never asserts that every attachment arrived or that anyone viewed it. Byte validation does not establish authenticity. Completed uploads now enter a separate durable local media-analysis queue; upload success never waits for inference.

### Media-analysis state and retry

`GET /api/state` adds `reports[].media_analysis`, one entry per declared attachment. This is
separate from media availability, text `ai_status`, original packet JSON and delivery receipts.

```json
{
  "report_id":"report-uuid",
  "attachment_id":"attachment-uuid",
  "status":"queued",
  "attempts":0,
  "available_at":1791040001000,
  "created_at":1791040001000,
  "updated_at":1791040001000,
  "error":null,
  "result":null
}
```

`status` is `waiting_upload | queued | running | complete | failed`. A `waiting_upload`
entry has no queue timestamps yet. Queued retries may retain a previous error; `available_at`
is the earliest next-attempt time. Claims increment `attempts`. Failure retries are bounded
to three attempts with backoff. Interrupted running work is requeued on process restart,
and completed files missing their queue transaction are discovered without changing the SOS.

`POST /api/reports/{report_id}/attachments/{attachment_id}/analyze` returns HTTP 202:

```json
{"status":"queued","report_id":"report-uuid","attachment_id":"attachment-uuid","idempotent":false}
```

Responder/admin accounts (or the applicable local demo/shared-key mode) may request retry.
Viewer and gateway accounts cannot. An existing queued/running job returns `idempotent:true`
without restarting it; the response's `status:"queued"` acknowledges the request, so read
`media_analysis` for the actual current state. Otherwise the attempt budget resets to zero.
The API can reanalyze a completed result; the current dashboard offers retry for failed work.
Unknown report/attachment returns 404; attachment bytes not yet available returns 409.

Reanalysis retains any old `result` while queued/running/failed. Clients must label it
**previous analysis** and exclude it from current AI priority until `status` is `complete`.
The original SOS, manifest, fingerprint, human decisions and receipts remain unchanged.

A completed `result` contains `transcript`, `language`, `summary`, `visual_observations`,
`audible_observations`, `uncertainties`, `requested_human_checks`, `suggested_urgency`
(`critical | high | normal | unknown`) and `urgency_reason`. Provenance includes `model`,
`provider`, `generated_at`, `pipeline_version`, `attachment_id`, `source_sha256`, and `coverage`
(decoded duration, sampled frame timestamps, audio inclusion and measured signal metadata).
It explicitly sets `human_review_required:true`, `authenticity:"unverified"`,
`transcript_source:"ai_transcription_not_reporter_text"`,
`notification_scope:"dashboard_review_only"` and `dispatch_performed:false`.

Audio is bounded to 30 seconds; video to 15 seconds and three sampled frames plus available
audio. These interpretations can be wrong. They are never proof of authenticity, an independent
location observation, a medical diagnosis, a human acknowledgement or rescue dispatch.
See [MEDIA_AI.md](docs/MEDIA_AI.md) for observed model errors and coverage limits.

`ai.media` returns `{provider, model, queue:{queued,running,failed,complete}, active_job}`;
an active job identifies its report and attachment. `media.analysis_completed`,
`media.analysis_failed` and `media.reanalysis_requested` appear in audit. Dashboard review
notifications are local to the open workspace (browser permission required for system
notifications), not a delivery acknowledgement or contact with emergency services.

## Dashboard state

`GET /api/state` returns this illustrative shape, plus `account` for the current account/demo
principal and the media fields described above. Nullable AI objects remain null while unavailable; no
fabricated fallback output is substituted. Up to the latest 500 reports, 500 incidents,
500 correlations, and 200 audit entries are returned; `truncated` flags indicate limits.

```json
{
  "server_time": 1791040001000,
  "reports": [{
    "schema_version":1,"id":"report-uuid","origin_id":"RQM-82F1",
    "created_at":1791040000000,"expires_at":1791043600000,
    "text":"Three people trapped.","building":null,"zone":null,
    "hop_count":0,"max_hops":8,"relay_path":["RQM-82F1"],"simulation":true,
    "incident_id":"incident-uuid","received_at":1791040001000,
    "ai_status":"complete","ai_error":null,
    "intake":{
      "facts":[{"field":"people_affected","value":"3","quote":"Three people trapped.","source":"text","report_id":"report-uuid"}],
      "uncertain_interpretations":[],"missing_information":["Exact location"],
      "model":"gemma4:e2b","generated_at":1791040002000
    }
  }],
  "incidents":[{
    "id":"incident-uuid","title":"Unclassified incident","building":null,"zone":null,
    "report_ids":["report-uuid"],"status":"new","category":null,"team":null,
    "acknowledged_at":null,"created_at":1791040001000,"updated_at":1791040002000,
    "merged_into":null,"triage_status":"complete","triage_error":null,
    "triage":{
      "summary":"A report describes three trapped people.","suggested_urgency":"high",
      "urgency_reason":"Reported entrapment requires human assessment.",
      "response_category":"rescue assessment","questions":["What is your exact location?"],
      "acknowledgement_draft":"Your report has been received for human review.",
      "missing_information":["Exact location"],
      "evidence":[{"report_id":"report-uuid","quote":"Three people trapped."}],
      "model":"gemma4:e2b","generated_at":1791040002000
    },
    "reported_people_counts":[{"report_id":"report-uuid","value":3,"quote":"Three people trapped."}],
    "people_total":null,
    "people_total_note":"Counts may overlap; a unique total requires human confirmation."
  }],
  "correlations":[{
    "id":"correlation-uuid","incident_a_id":"incident-uuid","incident_b_id":"other-incident-uuid",
    "confidence":0.74,"reason":"Nearby locations and compatible reports may describe one incident.",
    "evidence":[{"report_id":"report-uuid","quote":"Three people trapped."}],
    "status":"pending","created_at":1791040003000,"decided_at":null,"model":"gemma4:e2b"
  }],
  "audit":[{"id":1,"at":1791040001000,"actor":"gateway","action":"report.accepted","entity_id":"report-uuid","details":{"simulation":true}}],
  "ai":{
    "provider":"ollama","model":"gemma4:e2b","status":"ready","error":null,
    "checked_at":1791040000000,"active_job":null,
    "queue":{"queued":0,"running":0,"failed":0}
  },
  "stats":{"reports":1,"incidents":1,"open_incidents":1,"pending_correlations":1},
  "truncated":{"reports":false,"incidents":false,"correlations":false,"audit":false}
}
```

The example demonstrates shape, not preloaded or verified incident data.
`ai_status`/`triage_status`: `queued | running | complete | unavailable | failed`.
Intake fact `field`: `emergency_type | location | people_affected | vulnerable_person | situation`.
Fact `source`: `text | building | zone | emergency_type | location_text | floor | room |
people_affected | vulnerability | quick_needs`. Its quote is an exact substring of the input
field; a quick-needs fact quotes exactly one selected enum token and uses its defined label.
Location observation metadata is available as context, with its age and uncertainty retained.
`people_affected` values are nonnegative integer strings grounded in explicit numeric evidence.
When quoting structured `people_affected` metadata, the request-specific generation schema
requires the exact decimal string for both fact value and quote, including `0`. A null value
does not permit that source. Text-sourced count facts retain their ordinary exact-quote validation.
Every other value is an AI extraction/translation requiring human review, not independently
verified ground truth. Original quotes remain inspectable. Uncertainty is separate from facts.

The incident title/location come from supplied metadata, not invented geolocation.
Multiple victim counts are kept per report and never summed. Merged incidents remain visible
with `merged_into`; exclude those records from the active incident list.
`triage` is an AI suggestion only. It never changes operator status, category, or team.
Old triage is cleared when membership changes so an obsolete summary cannot look current.
`stats.critical_incidents` counts distinct unresolved/unmerged incidents with a critical text
triage or a **complete** critical media result. A previous result retained during reanalysis
does not count as current media advice. Dashboard priority displays the highest current
suggested urgency, labelled AI advice, while operator status remains human-controlled.

## Operator actions

- `PATCH /api/incidents/{id}`: body may contain `status`, `category`, and/or `team`.
  Status is `new | acknowledged | in_progress | resolved`; category/team are free text of
  1–80 characters or null. Returns the complete incident shape above. Unknown keys rejected.
- `POST /api/incidents/{id}/acknowledge`: empty body or `{}`; returns the incident.
  Records the operator action and creates a durable `responder_acknowledged` receipt for each
  current report. This means receipts are available for gateway retrieval, not that they have
  reached an offline origin. No SMS or autonomous dispatch is performed. Repeated calls are safe;
  calling again after a merge issues receipts for newly added reports only.
- `POST /api/correlations/{id}/decision`: `{"decision":"confirm"}` or `{"decision":"reject"}`.
  Returns `{"correlation":{...},"incident_id":"survivor-uuid-or-null","idempotent":false}`.
  Confirm transactionally merges the source memberships into one incident, retains both raw
  reports, archives the merged incident using `merged_into`, and queues fresh triage.
  Repeating the same decision is safe (`idempotent:true`); changing a final decision is 409.
  Suggestions rendered stale by another merge become `superseded` and cannot be confirmed.
  The survivor retains its human status/category/team; other assignments stay in audit history.
- `POST /api/reports/{id}/reanalyze`: empty body or `{}`. Returns
  `{"status":"queued","report_id":"uuid","idempotent":false}`.
  Already queued/running requests return `idempotent:true`. Explicit retry resets the three-attempt
  budget and clears AI output for this report, while preserving operator decisions/audit.
  Pending correlations involving this incident become `superseded`; a new analysis can create
  a fresh suggestion generation. Confirmed/rejected decisions remain final.

All actions are persisted with audit records. UUIDs in URLs must be valid. Unknown IDs return
404; editing an incident already merged into another returns 409 with the survivor ID.
Validation errors are HTTP 422. Operational exceptions are 500, never an invented success.

## Health and inference behavior

`GET /api/health` returns `{"status":"ok","service":"resqmesh","database":"ok"}`.
In demo/shared-key mode it additionally includes `ai`; in account mode AI/queue details are
omitted from public health and are available only through authorized `/api/state`.
HTTP 200 indicates the ingest service is healthy even if the model is unavailable.
Where included, the AI object has the same shape as state, including its availability status.

The sequential persistent worker runs Intake, Correlation, Verification, then Triage with
four distinct model prompts. A missing comparison candidate completes the Correlation stage
without an inference call; the audit explicitly records `inference_called:false`.
Correlation compares up to 12 recent active incidents; it only inserts suggestions. Every
confirmed merge is an explicit operator action. Stored jobs resume after process restart;
transient failures retry with increasing delays and stop after three attempts. Use reanalyze
to retry after the model is installed/recovered. `unavailable` means model connection or model
availability failed; `failed` means invalid/schema/evidence output or another processing error.
The original reports remain available regardless of inference status.
Queue failure counts exclude historical jobs marked `superseded`: reconciliation requires a
distinct later successful job for the same current incident and completion of all four current
stages. Original job errors/attempts and failure audit remain, with an added audit linking the
replacement job. An incomplete or unrelated failure is never hidden by this reconciliation.
Each agent's validation error receives at most one additional real-model repair request per
attempt, with schema and exact evidence checks preserved. Correlation still requires evidence
from both incidents after repair. Supplied metadata stay visible even when the model does not repeat them. A failed
verification or correlation stage does not suppress an otherwise available triage: the failed
stage stays explicit and bounded retries reattempt it.
Simulation and physical-report incidents are isolated during candidate selection, and cross-mode
merges are rejected. A 14,000-character input budget limits correlation context; whole candidates
that do not fit are omitted and their count is recorded in the correlation-completed audit entry.
An oversized source incident fails analysis explicitly instead of silently dropping its reports.

Text-agent Ollama requests use JSON schemas, temperature 0, `stream:false`, and `think:false`.
Media uses the OpenAI-compatible local endpoint with explicit `input_audio`/image inputs,
JSON schema output and `reasoning_effort:"none"`; no hosted provider is used by this setup.
Model capability is inspected before inference; incompatible thinking controls cause an
explicit unavailable status. Raw reports are delimited untrusted data and cannot grant tools
or change system instructions. Exact source quotes are validated before results are saved.
Schema and quote checks do not prove semantic truth; all extracted meaning still needs review.

Configuration: `RESQMESH_DB_PATH` (default `backend/data/resqmesh.sqlite3`), `OLLAMA_BASE_URL`
(default `http://127.0.0.1:11434`), `OLLAMA_MODEL` (default `gemma4:e2b`),
`OLLAMA_TIMEOUT_SECONDS` (default 180), `RESQMESH_API_TOKEN` (optional),
`RESQMESH_AUTH_DB_PATH` (optional local accounts), and `RESQMESH_FFMPEG` /
`RESQMESH_FFPROBE` (optional executable paths for media decoding).
Start one Uvicorn worker for this prototype's sequential text queue and independent media
queue; SQLite provides persistence. Separate `RESQMESH_ENV=production` and
`RESQMESH_PUBLIC_ORIGIN` guards exist but are not enabled or deployed for the local hackathon.
No CORS origins are opened. Browser output must use text nodes, never render report/model text
as HTML. Relay paths and simulation flags are sender-reported demo telemetry, not cryptographic proof.

## Four-stage incident state and verification

Every incident additionally exposes:

```json
{
  "processing_stages": {
    "intake": {"status":"complete","updated_at":1791040002000,"error":null},
    "correlation": {"status":"complete","updated_at":1791040003000,"error":null},
    "verification": {"status":"queued","updated_at":1791040003000,"error":null},
    "triage": {"status":"queued","updated_at":1791040003000,"error":null}
  },
  "verification_status":"unverified",
  "verification_notes":null,
  "verification_updated_at":null,
  "verification":null,
  "verification_signals":[{
    "id":"source_claims","code":"source_claims","severity":"info",
    "description":"Anonymous node IDs identify claimed origins, not independently verified witnesses.",
    "report_ids":["report-uuid"],
    "observed":{"report_count":1,"claimed_origin_count":1,"source_independence":"unknown","origin_report_counts":{"RQM-82F1":1},"incident_report_ids":["report-uuid"],"related_context_report_ids":[]}
  }],
  "acknowledged_report_ids":[],
  "emergency_type":"flood","emergency_types":["flood"],
  "location_text":"East entrance","floor":"Ground","room":null
}
```

Stage status values: `queued | running | complete | unavailable | failed | not_applicable`.
These are persisted per incident, not inferred from model availability or animation timers.
`verification_status` is human-controlled: `unverified | verification_requested | corroborated |
responder_verified | false_closed`. It is separate from a model suggestion.

Completed `verification` has this shape:

```json
{
  "suggested_state":"unverified",
  "summary":"The report needs independent review.",
  "signal_assessments":[{"signal_id":"source_claims","assessment":"One claimed origin supplied this report.","why_not_conclusive":"The origin is anonymous and has not been independently verified."}],
  "evidence":[{"report_id":"report-uuid","quote":"Three people trapped."}],
  "limitations":["Source independence is unknown"],
  "questions":["Can a responder confirm the location?"],
  "source_independence":"unknown","model":"gemma4:e2b","generated_at":1791040004000
}
```

Model `suggested_state`: `unverified | corroborated | conflicting_evidence |
suspicious_reporting_pattern`. The schema excludes definitive true/fake and human decision
states. Source independence always remains unknown. A single claimed origin cannot corroborate
itself, and distinct anonymous origin IDs do not establish independent witnesses. Although the
response enum retains `corroborated` for compatibility, the current agent input has no independently
established corroboration, so that model suggestion is rejected and repaired rather than saved.
An operator can record corroboration with required notes. Each model signal reference and exact
evidence quote is checked before persistence. The actual Ollama generation schema further
constrains signal IDs to those supplied in that request and omits `corroborated` from eligible
generation states. Invalid output gets one bounded model repair; persistent invalidity stays failed.
Deterministic signals include repeated origins, identical/near-identical text, differing supplied
counts/buildings, missing structured location, clustered backend arrival, and relay provenance.
Each is an observation with alternative explanations, not a fraud verdict.
`similar_wording` compares at most 20 reports using normalized-text SequenceMatcher ratio
at least 0.9 with both texts at least 20 characters. Exact normalized matches use
`repeated_text` instead. At most 20 near-copy pairs enter the model context; observed metadata
records compared/omitted report counts, total matching pairs, and whether pairs were truncated.

Triage receives only the incident's current member reports and their validated intake results.
Verification prose, signals, and evidence can include unmerged candidate context and are never
passed into triage. Thus candidate reports do not become incident facts before a human merge.
Invalid triage schema/evidence receives one bounded real-model repair; a second invalid result
remains failed and is never replaced with a fabricated answer.

Before a merge, verification can examine up to eight same-mode reports selected from 48 recent
backend arrivals within 15 minutes. Selection requires a matching building label or normalized
text similarity of at least 0.85; a 12,000-character report-context budget further limits input.
They are explicitly marked **candidate context, not merged reports**. Signals record both the
incident and candidate report IDs. UUID retransmission never creates an additional witness.
The bounded window is not an exhaustive search or independent truth check.

`POST /api/incidents/{id}/verification` accepts:

```json
{"action":"request_verification","notes":null}
```

Actions: `request_verification | corroborated | responder_verified | false_closed`.
Nonblank notes (maximum 2000 characters) are required for the last three. The response is the
full incident object. `false_closed` is an explicit human action setting status `resolved`; raw
reports and audit remain. Reopening through the status endpoint resets this verification label
to `unverified` and retains the previous reason in audit. Model output never performs these actions.
Merging changes incident membership and sets human verification to `verification_requested`;
old human decisions remain in audit. Fresh verification and triage are queued.

Optional human-review metadata (old notes-only clients remain supported):

```json
{
  "action":"responder_verified",
  "notes":"Responder reported checking the east entrance.",
  "reviewer_label":"Duty desk operator",
  "check_method":"on_site",
  "evidence_reference":"Radio log page 8, east entrance check",
  "checked_at":1791040001000
}
```

`reviewer_label` is a self-reported label, at most 120 characters. `check_method` is
`on_site | callback | independent_witness | external_reference | other`; these name the
operator's claimed check, not a backend-validated event or established source independence.
`evidence_reference` is stored text, at most 1000 characters; URLs are never fetched and external
evidence is not automatically validated. Non-null text fields must be nonblank after trimming.
`checked_at` is a positive integer epoch millisecond timestamp, at most five minutes ahead of
server time; it records the supplied observation time separately from server `recorded_at`.
All four fields may be null or absent. Decisive actions still require notes.

The incident adds `human_verification`, either null for legacy/unreviewed records or the
latest review's `action`, `notes`, four optional fields, server `recorded_at`,
`authenticated_account` (the signed-in account name, otherwise null), `identity_assurance`, and
`evidence_validation: "not_independently_validated"`. A null method means **method not recorded**.
Signed-in actions use `identity_assurance:"authenticated_account_self_reported_reviewer"`;
demo/shared-key actions use `"self_reported_not_authenticated"`. Legacy records may omit
`authenticated_account`. An authenticated account does not authenticate the claimed reviewer
label as a real-world identity or establish that their referenced evidence is true.
The full new record and previous record are retained in audit. An identical notes-only replay
does not erase richer metadata. A merge or reopening a false-closed incident clears the current
review while preserving its evidence in audit. These additions do not change receipts, dispatch,
or AI decisions. Role enforcement applies when the local account database is configured;
the optional shared demo API key does not establish an individual operator identity.

`reported_people_counts` entries now include `source: user_provided | ai_extracted`.
Matching declared and extracted counts within one report are not listed twice. Differing values
retain their sources; unique people totals remain unknown. Intake fact sources can additionally
reference the new structured input field names.

## Gateway presence, receipts, and delivery observations

`POST /api/gateways/{node_id}/heartbeat` body: `{"simulation":true}`. Successful response:

```json
{"node_id":"RQM-409F","simulation":true,"last_seen":1791040001000,"expires_at":1791040121000,"online":true}
```

Leases last 120 seconds based on server receipt time. An accepted upload also proves recent
backend contact and renews the uploading node's lease. No device is statically designated a
gateway. Presence is scoped by node ID and simulation flag; expired records return `online:false`.
It establishes recent contact with this backend, not a global view of every offline mesh node.

`GET /api/receipts?report_id=UUID` returns `{"receipts":[...],"server_time":epochMillis}`.
An unknown report returns an empty array. Receipt fields are shown in the ingest response above.
Types are `backend_received` and `responder_acknowledged`. Receipt IDs and content are immutable,
with one receipt per report/type. A responder receipt has `gateway_id:null`: no downlink gateway
has yet been established. Its `relay_path` is the original upload path, **not a claimed return
journey**. Neither receipt proves arrival at the origin. The Android transport must separately
record actual receipt reception and relay it through its normal store–carry–forward interface.

Merges do not rewrite historical receipt incident IDs. Consumers should resolve current
membership by report ID. Existing v1 uploads are migrated to durable backend receipts using
their stored receive timestamps. Historical dashboard-only acknowledgements do not generate
return receipts until an operator explicitly acknowledges current report membership.

`GET /api/state` adds `gateways`, plus `stats.critical_incidents`, `stats.awaiting_verification`,
and `stats.gateways_online`. Critical means an active incident with AI-suggested critical urgency.
Awaiting verification counts active incidents whose human state is unverified/requested.
Each report adds `receipts` and `deliveries:[{gateway_id,relay_path,first_seen,last_seen,attempts}]`.
Repeated uploads through different gateways preserve separate observed upload routes while
retaining the original packet and immutable receipt. No receipt is cryptographically signed:
the explicit trust label is `backend_issued_unattested`, and relayed copies require honest UI labeling.
