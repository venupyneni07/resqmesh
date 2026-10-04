# Testing and evidence

Run commands from the repository root unless another directory is shown. Automated tests use synthetic data. Do not use a real emergency report or a public responder service for a rehearsal.

## Backend and dashboard logic

After installing `backend/requirements.txt` in `.venv`:

```sh
.venv/bin/python -m pytest backend/tests -q
npm test
```

The Node DOM checks use built-in modules and need no running browser, backend or model. They exercise pagination, unsaved forms, authentication races and media presentation using test doubles.

## Real browser, isolated HTTP fixture

Install Node.js 20 or newer and the pinned browser test dependency:

```sh
npm ci
npx playwright install chromium
.venv/bin/python -m uvicorn backend.tests.browser_fixture:app --host 127.0.0.1 --port 8011
```

In another terminal, run `npm run test:browser`. The fixture uses a temporary database, synthetic reports and test-only account credentials. Its AI values are explicitly stubbed. The browser checks login/logout, roles, original-image decoding, inert transcript text and mobile layout. Stop the fixture server after the check.

The default test target is `http://127.0.0.1:8011`. `RESQMESH_BROWSER_TEST_URL` overrides that isolated test URL. `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` can select an existing Chrome/Chromium binary instead of the installed Playwright browser. Output screenshots and results are written to ignored `artifacts/local-completion/browser/`.

## Android

Use JDK 17 and install Android SDK Platform 35. In `android/`:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

See [Android build and instrumentation instructions](../android/README.md) for emulator setup and selected device tests. Install with `adb install -r` when preserving existing local history matters. Live gateway tests require an explicitly supplied isolated backend and create synthetic reports there. Camera/microphone launch tests use controlled fixtures; they do not establish physical capture reliability.

## Actual local Gemma media processing

This opt-in check requires local Ollama with `gemma4:e2b`, FFmpeg and ffprobe:

```sh
.venv/bin/python -m backend.tests.run_live_media_validation
```

The harness uses the committed generated audio/image/video files in `backend/tests/fixtures/media/`, creates a new isolated SQLite database and makes actual local model requests. If a normal local backend database exists, its original report fingerprints are compared before and after. The harness does not send a real SOS or acknowledge reports.

The checks distinguish accepted reports, attachment integrity, job completion and interpretation accuracy. A completed model result can still be wrong. The [media review notes](MEDIA_AI.md) describe observed errors and bounded video coverage.

For a focused blank-image and speech check, wait until the local backend has no pending model jobs, then run:

```sh
.venv/bin/python -m backend.tests.run_live_blank_media_validation
```

This runner generates a plain-white JPEG and uses the committed synthetic voice fixture. It starts its own loopback HTTP server on an available port with a new isolated database, then exercises upload, the real media worker, local Gemma and results returned by `/api/state`. It verifies completion events, byte-identical downloads, unchanged original packets and backend-received receipts without automatic human acknowledgement. The four text-agent jobs are intentionally excluded from this focused check. The temporary server stops when the check finishes; existing report and attachment hashes are compared without removing legitimate new reports. Results are written to ignored local artifacts; the live responder database is unchanged.

The recorded run returned a no-discernible-content summary and unknown urgency for the white image; the synthetic speech transcript matched its known words ignoring capitalization. This demonstrates processing and visible results for those fixtures, not universal accuracy. A separate synthetic API regression checks that a completed result remains available with an empty transcript and unknown urgency. Dashboard checks cover summaries and processing states in incoming rows, case Overview and the original-media viewer, expanded review details and the explanation for unclear speech.

## Recorded software verification

On October 4, 2026, the latest full backend suite passed **143 tests**, with one upstream Starlette deprecation warning. This includes regressions for rejecting truncated or incomplete media output, checking model capabilities before inference, retaining both brief and detailed descriptions through the dashboard API, and supplying modality-specific description guidance without padding blank or unclear inputs.

The separate Android baseline passed **50 Kotlin JVM tests and 12 distinct emulator tests**. Android lint reported zero errors and 33 warnings. Original local report payloads, media and settings were checked for preservation. These are dated checks, not a claim that future commits or every device have passed.

Isolated actual-model checks of `media-review-v2` produced two distinct outcomes:

- A known synthetic speech fixture matched its expected words.
- Replayed YouTube audio captured through the host microphone completed with an empty transcript, unknown language and unknown urgency, alongside explicit uncertainty. Capture and upload were verified, but intelligible speech was not recovered from that recording.

These results verify bounded processing and an observable uncertain result; they do not establish accurate live transcription, emergency sound/scene classification, or physical radio communication. The same captured recording was subsequently reanalysed through the running backend and completed with the same uncertain outcome. Original report payloads and audio bytes were unchanged. Original media remains available for human review.

Generated logs, actual recordings, local databases and development screenshots are intentionally excluded from Git. The source tests are included so checks can be repeated. Physical peer radios, representative-user/TalkBack acceptance and prolonged field/OEM behavior remain pending; see the [physical test plan](PHYSICAL_TEST_PLAN.md).
