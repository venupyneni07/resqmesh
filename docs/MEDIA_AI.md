# Local media review

The local backend now interprets completed audio, photo and video uploads with the installed
`gemma4:e2b` model in Ollama. It creates a separate, explicitly unverified interpretation for
the responder dashboard. The immutable original SOS text, attachment manifest, fingerprint,
delivery receipt, incident assignment and operator decisions are unchanged.

## Processing and recovery

1. Accept and acknowledge the SOS immediately, without waiting for attachment upload or AI.
2. Verify attachment size, SHA-256 and media container, then publish the private file atomically.
3. Persist a unique media-analysis queue record for the report and attachment.
4. A worker separate from text analysis validates the hash again, decodes bounded local media,
   and requests real Ollama inference. Decoding runs outside the HTTP event loop.
5. Save the interpretation and `media.analysis_completed` event. Dashboard review alerts are
   not rescue dispatch, proof of authenticity, or a human acknowledgement.

Interrupted running jobs return to the queue at startup. Startup also discovers a completed
file published before a crash prevented its queue transaction. Duplicate uploads never
reset completed analysis or duplicate upload events. Failures retry three times with backoff;
an authorized operator can request another analysis. A failure does not block the SOS, remove
the attachment or replace it with a generated result. Previous results retained during manual
reanalysis must be displayed as previous results until the new job completes.

`media-review-v2` rejects truncated or incomplete model responses, including parseable JSON returned with an incomplete finish reason. For unclear audio, the model is prompted to preserve uncertainty and leave the transcript empty instead of guessing or repeating syllables. These safeguards do not guarantee recognition accuracy.

`reports[].media_analysis` exposes waiting_upload, queued, running, complete and failed states.
`ai.media` exposes the queue and current attachment. The retry endpoint is
`POST /api/reports/{report_id}/attachments/{attachment_id}/analyze`.

## Finding results in Response Center

Incoming report rows show each attachment's analysis state and a short summary. The case's
**Overview** includes media review cards, and the original photo/audio/video viewer shows the
AI interpretation beside or below the source media. In **Reports & delivery**, the AI review
opens expanded, showing the summary, transcript when available, uncertainty and suggested
urgency. Observations, suggested human checks and analysis coverage can be expanded separately.

Waiting for upload, queued, processing and failed states have visible explanations; eligible
operators can retry failed or unavailable analysis. Results remain visible when urgency is
unknown or the transcript is empty. For unclear audio, the UI explains that the model did not
identify intelligible speech and asks the reviewer to play the original; this does not establish
that the recording contains no speech. Blank and ordinary images enter the same analysis queue;
the expected result describes visible content and uncertainty without claiming that an emergency
or a false report has been established. New analyses completed after the initial page load
produce an in-workspace notice, including normal/unknown results.
Optional browser notifications remain limited to high/critical suggestions and require permission.

## Bounded inputs and provenance

- Audio: up to 30 seconds, locally decoded to mono 16 kHz PCM WAV.
- Photo: one locally decoded frame, maximum 1024-pixel dimensions after normalization.
- Video: up to 15 seconds, only three frames at 0%, 50% and 90%, plus its audio track if present.
- The actual decoded duration is checked; the untrusted declared duration cannot bypass limits.
- Decoding is limited by wall-clock timeout, permitted file/pipe protocols, input pixel count,
  and normalized output size. Original files remain available for human review.
- Results include model, pipeline version, generation time, original SHA-256, attachment ID,
  audio inclusion and frame timestamps. Transcript text is labelled AI transcription, not
  reporter-authored text. RMS/peak measurements establish only measurable audio activity,
  never a sound's identity or cause.

The implementation uses Ollama's OpenAI-compatible `input_audio` interface because some native
chat versions silently ignored an `audios` property. Required audio/vision capabilities are
checked before inference. Output schemas constrain absent modalities: image-only input cannot
produce speech, and audio-only input cannot produce visual observations.

References: [Ollama vision API](https://docs.ollama.com/capabilities/vision),
[official input_audio implementation](https://github.com/ollama/ollama/blob/main/openai/openai.go),
[Gemma 4 modality documentation](https://registry.ollama.com/library/gemma4),
[native audio-field issue](https://github.com/ollama/ollama/issues/17730).

## Local runtime

No new Python dependency or extra model download is needed on this development machine.
FFmpeg and ffprobe are required. `RESQMESH_FFMPEG` and `RESQMESH_FFPROBE` can identify their
executables when absent from the server PATH. Ollama uses the existing `OLLAMA_BASE_URL`,
`OLLAMA_MODEL` and timeout settings; the default endpoint is local loopback. No cloud service,
AWS integration or public deployment is required for this workflow.

## Evidence and honest limits

Unit/integration tests cover durable recovery, duplicate ingestion, failure/manual retries,
modality constraints, bounded duration, integrity failure, immutable original packets and
critical-review counts. Test stubs are explicitly labelled and are not live inference evidence.

`artifacts/media-intelligence/live-validation.json` records separate real-model component runs.
The generated voice and video transcripts matched the spoken fixture ignoring capitalization.
Silence and a generated tone did not become invented speech. The image model identified a
blue image, but also hallucinated unreadable characters on that plain image in some runs.
The model missed the generated tone as a non-speech observation. These are observed model
limitations, not evidence of validated emergency scene or sound classification.

`backend/tests/run_live_media_validation.py` exercises actual HTTP ingestion, durable queue,
Worker and local model against a new isolated database. It verifies completion events,
unchanged original packets, backend-received receipts only, and preservation of live reports.
Its evidence is `artifacts/media-intelligence/live-pipeline-validation.json`.

The opt-in `backend/tests/run_live_blank_media_validation.py` check uses a generated plain-white
JPEG and the committed synthetic speech fixture. It runs a real loopback HTTP server, media
worker and local Gemma against an isolated database; the four text-agent jobs are outside this
focused check. The white image completed with a no-discernible-content summary and unknown
urgency; the speech transcript matched the fixture ignoring capitalization. Both results were
visible through the dashboard API, with original bytes/packets preserved and no automatic human
acknowledgement. These examples do not establish universal image or transcription accuracy.
See [testing instructions](TESTING.md#actual-local-gemma-media-processing) to repeat the check.

Human review is mandatory. The system cannot prove that media is recent or authentic, identify
the reporter, establish a current location, diagnose injuries, or guarantee speech recognition
across languages, accents, noise and stress. Three video frames can miss important events.
The media suggestions do not overwrite operator urgency/verification, downgrade an existing
text critical suggestion, acknowledge the SOS, merge incidents, contact anyone, or dispatch
rescue. An Android Emulator capture using the host microphone was uploaded and reanalysed through the running backend. Replayed YouTube audio produced an empty transcript, unknown language/urgency and explicit uncertainty; original report payloads and audio bytes were unchanged. A known synthetic speech fixture matched its expected words. This verifies capture, upload and processing for those inputs, not reliable speech recognition. Physical phone microphone/camera/radio testing and representative emergency-user validation remain open.
