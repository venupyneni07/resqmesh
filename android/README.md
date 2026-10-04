# ResQMesh Android

Native Kotlin Views, Android 8+ (min API 26), compile/target API 35. Open this directory in Android Studio. The Gradle daemon criteria select JDK 17; Studio's bundled JDK 25 is not used to run Gradle 8.10.1.

## Product flow

This prototype has four persistent tabs: **Home, Reports, Network and Settings**. Home keeps the emergency actions together and shows only the latest report. Reports owns the full history, with five reports per page, Previous/Next controls and **All reports / Needs attention** filters. Network also paginates reports carried for others. Screen changes reset the scroll position; changing a report page returns to its top.

Home starts **Record voice / Take photo / Record video** directly from large tiles, without a category form. Voice uses foreground MediaRecorder AAC and explicit microphone permission; **Stop & send SOS** saves the recording and submits it. A limit or interruption stops recording for review rather than sending automatically. Photo/video use the device camera through a narrowly scoped FileProvider URI, then show a preview with one send action. Camera cancellation and permission failure leave **Send SOS without media** available; it opens the general-SOS confirmation before saving a request without an attachment. No background recording or broad photo-library permission is used. Media drafts survive Activity recreation; successful sends save the file into encrypted private per-report storage before discarding the capture draft. Private plaintext capture/preview files exist temporarily. Home exposes an unsent media draft through **Continue unsent media SOS**.

Capture state distinguishes starting, microphone permission, external camera, recording, processing, ready, cancelled and error. The selected kind is committed before navigation, so permission/camera waits never render a second method chooser or a previous cancellation notice. A failed/cancelled photo, video or voice request offers its own retry action; **Choose another method** returns Home. Only one request may run at a time. Leaving a microphone permission request cancels its intent, and a later permission result cannot begin background recording. Camera results without a matching pending request cannot erase a newer capture.

**Home → Choose help** opens an unsent draft with no emergency type selected. Choose a type (or **Other / not sure**), optionally add Cannot move / Cannot speak / People injured, a people count, a message and location details, then **Review SOS → SEND EMERGENCY SOS**. The preview shows the actual draft; Edit details or system Back preserves it, and nothing is saved until final send. A pending location lookup is stopped before review so a later fix cannot silently alter the approved location. Home offers **Resume draft** and **Discard draft**, with confirmation before discarding selected help or typed details. Navigation and Activity recreation preserve the draft. No typing is required. An empty message is explicitly labeled as app default text; structured selections remain prominent. Unknown location and people count are valid.

The separate **General SOS** action does not require selecting a category. It first opens **Send general SOS?**, showing the preset message, the frozen location/source/observation age (or an unknown-location warning), and that no selected category, typed draft, voice, photo or video is included. The dialog explains local saving and the need for a connection for delivery. **Send SOS** saves exactly that snapshot; **Cancel**, Back or leaving the app does not send. Repeated taps on General SOS cannot stack confirmation dialogs. Existing report history is retained for delivery tracking.

**Home → Add location / Change location** and **media preview → Change location** open **Location for SOS**. Enter a landmark/address, request device coordinates with foreground permission, or use a saved location, then select **Use this location**. **Continue without location** remains available. Home saves its selection for future quick SOS and new media captures; its opt-out persists and suppresses silent reuse of a cached fix. A media-preview selection applies only to that capture. The detailed SOS form retains its separate location fields and explicit saved-location control. The source and observation time stay attached to the report; a saved or cached location is never silently presented as a fresh fix. Location denial or an unavailable fix does not block sending. No background location permission is requested.

**SOS delivery** places status first and separates Saved locally, Relaying, Backend received and Human acknowledged using only evidence held by this device. Media availability/receipt is separate from alert delivery. **Delivery details** expands technical events, relay paths and receipts; the main view does not require reading this history. An acknowledgement does not confirm that a rescue team was dispatched. **Network** shows discovery/authentication/connection states and current directly advertised gateway leases. **Settings** controls transport mode and automatic relay; **Connection setup** expands the response-server origin and API key. Pausing nearby relay does not disable direct backend delivery.

**Settings → Background relay** opts into a user-visible relay session with a persistent notification and Stop action. The session uses Android `dataSync`, plus `connectedDevice` for real Nearby mode. Android 15+ transfer timeouts stop it and offer a resume reminder. Boot or app update posts a tap-to-resume reminder rather than starting a prohibited background service. OS-permitted sticky recovery is supported, but force-stop, Doze and OEM restrictions can still interrupt work. Enabling background relay never enables background microphone, camera or location capture; new Nearby connections still require confirmation in the app.

**Delivered history retention** defaults to keeping all history. Optional 7/30/90-day settings and manual cleanup remove only old local copies with a consistent backend receipt and backend confirmation for every attachment. Age starts from the later of creation/receipt. Pending and expired-but-unconfirmed reports remain, and cleanup skips active network/media work. This does not delete the backend's original report.

The app interface is English-only, with no language selector. Original SOS text and AI transcripts retain their source language. Unsent text/selection drafts are encrypted for process recovery. Accessibility additions include 48dp minimum action targets, heading semantics, field label associations and pane titles. Compact-height and font scales of 1.6 or greater move actions into scrollable content. These changes still require real TalkBack and emergency-user review.

Simulation is explicitly labeled throughout. **Settings → Simulation tools → Open Simulation Lab** opens a developer environment editor, separate from normal SOS operation. The first environment contains one independently generated offline node, with no route or gateway. Add arbitrary nodes, connect/disconnect links, toggle each node's simulated Internet availability, and change the local viewing perspective. Removing a virtual node retains its reports; restore saved nodes to inspect earlier reports. The optional two flood examples are only inserted by the developer sample button. There is no predefined A/B/C/D runtime and no manual forward action.

A connected link triggers automatic store-carry-forward. A node with network availability attempts the actual backend heartbeat; only a successful response establishes a temporary gateway lease. Gateway uploads and receipt polling run automatically. The server origin defaults to `http://10.0.2.2:8000`, the emulator's route to the Mac. A simulated Internet switch permits an HTTP attempt; it cannot manufacture backend acceptance.

## Evidence and architecture

`core/Relay.kt` is deterministic Kotlin with an injected clock, `ReportStore` and `Transport`. It enforces immutable UUID deduplication, exact observed paths, expiry, loop prevention and at most eight hops. An application persisted-copy ACK changes the sending copy to `FORWARDED`; this is not a backend receipt. Expiry maintenance runs even when forwarding is paused.

`core/Runtime.kt` defines shared typed transport events, peer states, inventory/capability control messages, dynamic `SimulationEnvironment`, `SimulationTransport`, and `ReceiptRelay`. `NearbyTransport` implements the same boundary over authenticated Google Nearby Connections `P2P_CLUSTER`. Inventory and capability exchange use actual connected peers. Gateway capabilities expire and only advertise directly observed backend reachability. Receipt gossip has immutable receipt-ID deduplication, its own observed return path, up to eight hops and a 24-hour TTL. Backend and responder receipts are separate evidence.

`MeshController` owns a serialized coordinator, Room storage, bounded network workers and immutable `StateFlow`. The UI collects state with the Activity lifecycle and sees only the selected local node's stored reports, locally received receipts and observed peers. It never reads remote simulator rows to infer that delivery succeeded. Periodic maintenance handles retries and TTL, without predetermined delivery timers. Upload and receipt-poll work use separate rotating bounded batches, so large histories or repeated failures do not permanently starve later reports.

An encrypted durable submission-operation journal retains an already approved report UUID across process recovery. Retrying that operation recovers the same stored report instead of creating a second SOS; it does not automatically send a new unapproved draft. Android Keystore AES-GCM also protects durable report/receipt JSON, saved location/text-and-selection drafts, gateway credentials, complete media and received chunks. Room's routing/index metadata remains readable. Capture, assembly, preview and upload use private plaintext temporary files, and historical capture/media drafts can remain plaintext. Preview cleanup runs on startup and background transitions when uploads are not active. Logical migration and checkpoint/VACUUM do not securely erase OS backups or snapshots. There is no end-to-end encryption or cryptographic reporter identity claim.

Room v2 adds receipt and structured journey tables through `MIGRATION_1_2`, retaining every v1 report/event, original UUID, node ID, path and forwarding state. New anonymous IDs are generated once and persisted. Old simulation-node rows are available through the Lab's restore-saved-nodes control; they do not become the default route. New text-only reports use schema v3 structured fields and provenance; existing v1/v2 envelopes remain readable.

Media SOS uses schema v4 attachment manifests; text-only SOS remains v3. Existing v1–v3 reports remain readable without a Room migration. `MediaRelay` uses the same Transport boundary and sends bounded 16 KiB chunks after the alert. Each receiver persists chunks and exposes the file only when complete size and SHA-256 match; partial files survive restart. Simulation uses distinct per-node files, not shared availability. Failed transfers retry with backoff, and an SOS receipt does not suppress pending media forwarding. Forwarding obeys the report's existing expiry/hop limits. Gateways upload files independently after base-report acceptance and verify returned manifest details. A separate media worker leaves alert uploads and receipt handling available while video transfers. Local media status claims server receipt only after this device observes actual HTTP confirmation; an offline origin can therefore know its SOS arrived while media delivery remains unconfirmed.

Capture bounds are 30 seconds / 1 MiB for AAC voice, 1 MiB for compressed JPEG, and 15 seconds / 8 MiB for MP4 video. The UI supports one capture per SOS; the v4 manifest allows up to three files / 10 MiB for peers. The local backend now transcribes/interprets uploaded media through a separate Gemma worker; this inference is not performed on the phone or inside the relay engine. Interpretation stays separate from the original report and requires human review. See [MEDIA_AI.md](../docs/MEDIA_AI.md) for sampled-video coverage and observed model errors. All phones must install this build for v4 relaying.

A `backend_received` receipt means the server accepted the SOS. A `responder_acknowledged` receipt records an explicit operator acknowledgement, not a rescue promise or ETA. Neither is shown at the source until it reaches that source through HTTP or an actual transport packet. The immutable upload path and the receipt's return path are distinct. Receipts identify their trust as `backend_issued_unattested`: this prototype validates shape, IDs, mode, plausible timestamps and consistency with known reports but **does not cryptographically authenticate receipt contents**. Authenticated Nearby links authenticate connection peers, not backend receipt signatures.

Without an opted-in background session, discovery/relay pauses when the Activity leaves the foreground. With the service active, saved work can continue within Android's lifecycle and radio restrictions. Persistent storage and durable operation recovery support process restarts; they do not guarantee background delivery, radio range, cryptographic identity or production emergency-service availability.

## Physical phones

Choose physical mode, enable automatic relay, grant requested runtime permissions, and enable Wi-Fi/Bluetooth (plus Location through Android 12L). Google Play services availability is checked. Both users compare and confirm Nearby authentication digits. Discovery/connection state is visible. Transient peer failures retry after 3/6/12 seconds and then every 30 seconds while the peer remains discoverable. Explicit rejections are not automatically retried; disable and re-enable relay after agreement to try again.

Any physical node that can actually reach the configured backend automatically becomes a gateway; there is no fixed role toggle. OS network availability and validated Internet are displayed separately from a successful backend heartbeat. Local HTTP supports the hackathon setup; use HTTPS outside the trusted local demo. An optional server key sends `X-API-Key`, is protected at rest with Android Keystore AES-GCM and is never logged. The local account backend can provision a separate key scoped to a gateway's node ID. Changing server origin clears an old key unless a new key is explicitly supplied. Changing server settings invalidates old pending probes and gateway leases; historical receipts remain historical evidence from the accepting backend.

Physical checklist (still unverified):

- Install the same APK on two or more phones; verify distinct persistent IDs, permission recovery and authentication on both screens.
- Disable Internet on origin/relay devices while retaining Wi-Fi/Bluetooth. Send an SOS and verify the receiving phone's matching UUID and actual path.
- Remove a peer, send another SOS, then reconnect; verify retained reports resume without duplicate rows. Reject a connection and verify no automatic acceptance.
- Give any node access to the backend. Verify its heartbeat lease and matching uploaded UUID. Remove access and verify the lease disappears and pending work remains queued.
- Partition the origin before upload. Confirm it continues to show delivery unknown. Restore the path and verify the returned backend receipt, then deliberately acknowledge from the operator dashboard and verify the separate returned responder receipt.
- Arrange distance/controlled topology if demonstrating multiple hops; nearby phones may legitimately shortcut the path. Read the recorded path rather than assuming a chain.
- Test revoked permissions, radios off, process restart, foreground/background, battery saving, multiple gateways and gateway switching.

Emulator simulation **does not verify Bluetooth, Wi-Fi Direct, Nearby radio communication, discovery range or physical multi-phone relay**. Nearby 19.3.0 remains the previously verified pinned dependency; target SDK 35 does not opt into target 37 local-network permission behavior.

## Build and verification

With JDK 17 and the Android SDK configured:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

JVM tests cover relay invariants, malformed/late ACKs, dynamic nodes and links, partitions, immutable receipt deduplication, reverse-delivery knowledge, separate human acknowledgements, paused expiry and rotating gateway work. Emulator tests cover Room reopen, additive v1→v2 migration, v1/v2 wire round-trips, normal product flows, and a delayed old heartbeat arriving after the configured backend changes.

The earlier mobile redesign passed `MobileRedesignFlowTest`, `ProductFlowTest` and `MediaCaptureFlowTest` on the emulator. These cover bounded report history, draft review/resume/discard, location choices, recreation, duplicate-send protection and synthetic media preparation/playback. The opt-in `MobileUxAuditTest` captures the current user's main screens without sending an SOS, starting media capture or changing settings; it compares persisted report IDs and saved preferences before and after. That revision's logs remain in the developer's ignored local artifacts directory. These historical checks do not verify later lifecycle/storage changes or establish real-user acceptance or physical camera/microphone/radio performance. The backend media-inference checks are described in [MEDIA_AI.md](../docs/MEDIA_AI.md).

The later reliability/storage change passed 50 JVM tests, `BackgroundRelayTest`, four `ReliabilityStorageTest` cases, and a separate checkpoint/VACUUM export assertion on the emulator. Lint reported zero errors and 32 warnings. All 149 original decoded report copies, 103 original media hashes and the configured credential were preserved in that run. Detailed logs remain local; the test sources are included in this repository. English-only UI/accessibility verification is recorded separately; these results do not prove phone radio operation, OEM behavior or sustained background delivery.

Schema 3 additionally carries preset/user message provenance, selected quick needs and location observations. All peers should install the updated APK before a phone rehearsal; an older APK cannot decode a schema 3 report. Updated apps retain older report formats and existing Room data.

Install with `adb install -r` to preserve existing app data. Running the Gradle connected-test task can remove its temporary app installation; manual instrumentation avoids that teardown:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  -e class org.resqmesh.app.RoomPersistenceTest,org.resqmesh.app.ProductFlowTest,org.resqmesh.app.GatewaySettingsTest \
  org.resqmesh.app.test/androidx.test.runner.AndroidJUnitRunner
```

The live gateway test is skipped unless `liveBackend` is explicitly supplied. Use the updated backend with heartbeat and receipt endpoints:

```sh
adb shell am instrument -w \
  -e class org.resqmesh.app.SimulationGatewayTest \
  -e liveBackend http://10.0.2.2:8000 \
  org.resqmesh.app.test/androidx.test.runner.AndroidJUnitRunner
```

It creates arbitrary nodes and two synthetic flood reports, adds a relay and gateway after origination, partitions the reverse path, waits for real HTTP acceptance, proves the source remains unaware, and restores the link to deliver receipts. A second arbitrary gateway appears and loses Internet. It then explicitly acknowledges only the newly created test incidents and proves the separate human receipt also needs a return path. Reports/environment remain inspectable; prior mode/server/key are restored. This integration test writes synthetic reports and operator acknowledgement events to the supplied backend. It is not a physical-radio test.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
