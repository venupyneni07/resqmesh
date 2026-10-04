# Local ResQMesh demo

This walkthrough demonstrates the software on one development machine using an Android Emulator and the real local backend. Use invented details and non-sensitive fixture media. Keep the **Simulation Mode** label visible throughout.

It demonstrates persistence, automatic forwarding, HTTP upload, returned receipts and human review. It does not demonstrate real Nearby/Bluetooth/Wi-Fi radio communication.

## Before the walkthrough

1. Complete the [README setup](../README.md#run-locally): install dependencies, start Ollama, download the configured model and start the backend.
2. Open Response Center at `http://127.0.0.1:8000`. Check that the backend and configured model are available. A model failure should remain visible rather than being replaced by an example answer.
3. Run the Android app in an emulator. Under **Settings → Connection setup**, use `http://10.0.2.2:8000` for the backend. Match any explicitly configured local access key.
4. Select **Simulation Mode** and open **Settings → Simulation tools → Open Simulation Lab**. Use a fresh isolated virtual node with its Internet switch off. The Lab creates ordinary node IDs; it does not impose an A/B/C/D chain.
5. Retain existing reports. If a completely separate rehearsal is needed, start a backend with its own database instead of clearing the current one.

For example, from the repository root, an isolated backend can run in its own terminal:

```sh
mkdir -p .runtime
RESQMESH_DB_PATH=.runtime/rehearsal.sqlite3 \
  .venv/bin/python -m uvicorn backend.app:app --host 127.0.0.1 --port 8001
```

Use `http://127.0.0.1:8001` in the browser and `http://10.0.2.2:8001` in the emulator. The configured Ollama endpoint remains local. Do not point synthetic-test scripts at a database containing real emergency reports.

## Walkthrough: an SOS survives disconnection

### 1. Save with no connection

From the isolated virtual source, return Home and choose **Choose help**. Select **Flood**, optionally choose **Cannot move**, and enter an invented landmark and message such as:

> Synthetic demo: water is entering the ground floor. Two people need assistance near the east entrance.

Review the report, then explicitly send it. Show **Saved locally** and the pending delivery state. Opening the form or review alone must not create a report.

An alternative no-typing demonstration is **General SOS**. Show its confirmation, including the preset message and known or unknown location. Cancel once to demonstrate that nothing is sent. A general SOS deliberately contains less context than a selected need or recording.

### 2. Add a relay after the report exists

Open the Simulation Lab, add another virtual node and connect it to the source. Do not select a forwarding hop or press a manual “forward” action: the relay engine reacts to the connection.

Switch the viewing perspective to the receiving node and inspect the same report UUID with its observed path. Then return to the source. A peer-stored acknowledgement establishes a persisted copy on that peer; it does not establish backend delivery.

### 3. Let any receiving node become a gateway

Add another node and connect it to a node carrying the report, or use an existing receiver. Enable that node's simulated Internet availability. The toggle permits real backend requests; a successful heartbeat and upload are still required.

In Response Center, find the uploaded report and inspect the original details and upload path. On the source, show the backend-received status only after its receipt returns over connected links. Explain that the displayed route was observed, not prewritten.

### 4. Partition the return path

Disconnect the source's link. In Response Center, explicitly acknowledge the incident as the human operator.

The dashboard can record that action, but the isolated source must retain its last known state. Restore a path to the source and wait for the separate responder receipt. **Human acknowledged** means someone acknowledged the report; it does not mean a rescue team was dispatched.

### 5. Review related reports without inventing certainty

Submit a second synthetic report from a distinct virtual origin describing the same invented location. Inspect **Intake**, **Correlation**, **Verification** and **Triage** in the response workspace.

Review one correlation pair at a time and explicitly confirm or reject it. Show verification signals and the unknown source-independence status. Separate node IDs and similar descriptions are not proof of independent witnesses or a real incident. Human verification should include explanatory notes.

## Optional media demonstration

Home offers **Record voice**, **Take photo** and **Record video** directly. A voice recording can use **Stop & send SOS**; photo/video show a preview before sending. The alert travels before the larger attachment. Show these distinct states:

1. The SOS is saved and may already have a backend receipt.
2. The attachment is still pending, or becomes available after verified upload.
3. Media interpretation is waiting, queued, running, complete or failed independently.
4. A responder can explicitly open/play the original media and compare it with the unverified AI output.

For an actual microphone demonstration, deliberately enable the emulator's host-microphone input and grant the relevant host/app permission. Do not leave ambient recording active. A missing or disabled host input is different from a relay or upload failure.

Synthetic audio, photo and video fixtures have exercised transfer, playback and local-model processing. The current `media-review-v2` rejects truncated/incomplete output. In isolated checks, a known speech fixture matched its expected words, while replayed YouTube audio captured through the host microphone completed with an empty transcript, unknown language and unknown urgency, with explicit uncertainty. Capture/upload worked; intelligible speech was not recovered from that recording. Show these as separate outcomes, not proof of accurate transcription. The captured recording was also reanalysed through the running backend with the same uncertain outcome and unchanged original report/audio.

Video inference samples a few frames plus an available audio track and may miss events. Transcription and sound/scene descriptions can be wrong. No media output proves authenticity or automatically contacts emergency services.

## Optional background and recovery demonstration

Enable **Settings → Background relay** while the app is visible. Show the persistent notification and its **Stop background relay** action, then leave the Activity and return. Explain that this is an opted-in Android foreground-service session, subject to Android transfer limits and device policies.

Unsent text/selection drafts have a Resume/Discard flow. Approved submission recovery reuses its stored operation UUID; it never sends an unapproved draft automatically. After a reboot, an enabled relay session offers a tap-to-resume reminder. Do not promise automatic recovery from a user force-stop or every manufacturer's battery policy.

## Expected failure states

| Situation | What should remain true |
| --- | --- |
| No route or no reachable backend | The report remains saved; the source does not claim backend delivery. |
| Return path disconnected | The source does not claim a remote acknowledgement it has not received. |
| Microphone permission denied or camera cancelled | No media SOS is sent; selected-method retry and an explicit no-media option remain. |
| Location unavailable | Sending remains possible with an explicit unknown-location state. |
| Attachment upload fails | The SOS receipt remains separate; media stays pending/retryable. |
| Ollama unavailable or model output rejected | Original reports/media remain reviewable and the failed processing state is visible. |
| Repeated upload of one UUID | The backend retains one original rather than treating relay copies as new witnesses. |
| Retention disabled or report unconfirmed | Local cleanup must preserve that report. |

If inference is slow, show its actual status. Do not insert a fabricated result to keep the walkthrough moving. The [testing guide](TESTING.md), [API contract](../API.md), [architecture](ARCHITECTURE.md), [media review notes](MEDIA_AI.md) and [physical-phone test plan](PHYSICAL_TEST_PLAN.md) describe the next verification steps.
