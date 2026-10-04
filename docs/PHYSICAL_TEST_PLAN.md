# Physical device milestone

Status: **not executed**. Real Android phones are required. Simulation and emulator results do not establish Bluetooth/Wi-Fi discovery, range, throughput, background delivery or successful radio relaying.

Install the same APK on each participating phone. Use invented reports. Keep the app in the foreground, enable Wi-Fi/Bluetooth, grant the requested permissions and compare the Nearby authentication code on both screens before accepting a connection. Each relay phone must run ResQMesh.

For a laptop backend, start FastAPI on a trusted LAN and configure its reachable address in Android Settings. The default emulator address `10.0.2.2` is not the laptop address for physical phones. Any phone that can actually reach the backend may become a gateway. The optional shared demo API token is not transport encryption or individual responder authentication.

| Test | Setup and action | Required evidence |
| --- | --- | --- |
| No peers | Keep one phone offline and create an SOS | Report survives force-stop/reopen and stays waiting |
| Voice capture | Tap Record voice, allow microphone, speak briefly, Stop & send SOS | Playable local audio, correct duration, no required typing/category; microphone stops on exit/background |
| Photo/video capture | Take a photo and record a short video, preview and send each | Actual camera output, orientation and playback correct; cancel/denial still permits SOS without media |
| Media relay | Send captured files over authenticated Nearby with no Internet, disconnect/reconnect mid-transfer | Base alert arrives before complete media; partial chunks resume; bytes match the manifest; no simulation packets accepted |
| Independent media upload | Introduce slow media upload and send a second SOS | Second alert and receipts progress independently; UI distinguishes alert acceptance from attachment availability |
| No typing | Tap Home → SEND SOS, select a type, leave message blank, review and confirm | Nothing is sent before confirmation; selected type, optional needs, unknown count and location are represented honestly |
| Emergency shortcut | Tap Can't add details? Send now with no saved location or location permission | General help request, available cached location with its original time or explicit unknown, durable local copy; no permission or keyboard barrier |
| Review and edit | Select category and quick needs, review, go back and edit, then confirm | Draft survives Back and recreation; every receiving peer and the response center retain the reviewed selections and preset provenance |
| Location unavailable | Decline location permission or disable location providers, then send | Sending remains available and location is explicitly unknown or labeled with the actual cached observation time |
| Saved location | Save a landmark or device location, reopen, then send Quick SOS | Original observation time remains unchanged; saved source and age are visible |
| Two phones offline | Connect two phones through Nearby | Receiver stores the same UUID and appends its node ID once |
| Reconnection | Disconnect a peer during transfer and reconnect | Report remains stored, retry succeeds, no duplicate report |
| Carry and forward | Connect a relay to source, disconnect source, then connect relay to another phone | Later phone receives the retained report |
| Gateway appears | Give a receiving phone access to the backend | Valid backend receipt, one accepted report, actual observed route |
| Gateway fails | Stop backend reachability while internet remains available | UI distinguishes unavailable backend, no fabricated delivery |
| Alternate gateway | Let another connected phone reach the backend | Automatic upload, same UUID remains idempotent |
| Return receipt | Restore a route to the source | Source changes status only after receiving the receipt |
| Responder acknowledgement | Acknowledge the incident in Response Center | Separate acknowledgement receipt reaches source, no claim of dispatch |
| Duplicate and loop | Make multiple peer links, replay the same report | One local copy per node, bounded path, no forwarding loop |
| Hop/expiry limits | Use a test report with constrained limits | Forwarding stops at the bound, local history remains inspectable |
| Simulation isolation | Try a simulated payload on the physical transport | Payload is rejected |

Record OS/device models, application version, node IDs, timestamps, report UUID, route, logs and backend receipt IDs. To prove a multi-hop path, ensure the source cannot connect directly to the gateway during the test. Physical topology cannot be inferred from the desired diagram.

Background service reliability, battery consumption, OEM restrictions and prolonged field operation need separate tests after the foreground milestone.
