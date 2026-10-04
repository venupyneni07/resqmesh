# Secure deployment profile

This is a prepared deployment, not a running public emergency service. No cloud
resources, charges, public DNS records, or rescue-service integration were created.
The current demo remains on the Mac. A chosen host/domain and authorized responder
accounts are required before publication.

`deploy/compose.yaml` runs Caddy HTTPS, one FastAPI worker, durable SQLite/media,
and private Ollama. Neither the backend nor Ollama publishes a host port. Only
Caddy's fixed private address is trusted to supply forwarded HTTPS headers.
Use a host with enough RAM for the selected model; CPU inference can be slow.
Use an encrypted host volume for backend data and backups. Container volumes are
not themselves encryption. Android encryption does not imply end-to-end encryption.

Provisioning sequence for the operator:

1. Point the chosen domain at the host; allow inbound 80/443 for certificates/HTTPS.
2. Set `deploy/.env` from the example. Build and start the compose services.
3. Pull `gemma4:e2b` inside the private Ollama service; confirm model capabilities.
4. Provision individual accounts with `scripts/accounts.py --database /data/accounts.sqlite3 user NAME --role responder` inside the backend container. The password prompt avoids command history; passwords use salted PBKDF2-SHA256. Roles are viewer, responder, admin. Never share responder credentials with phones.
5. Provision a distinct gateway key for each real node with the same script's `gateway NODE --key-file PRIVATE_FILE` command. Deliver it securely to that phone's connection settings, then remove the temporary delivery copy. Keys are hashed server-side; rotating them invalidates the old key immediately.
6. Verify HTTPS with a trusted certificate, login/logout, role denial, report upload,
   media processing, delivery receipts and a database/media backup/restore rehearsal.
   Keep physical-radio acceptance separate.

Sessions are HttpOnly/SameSite Strict, secure in production, and expire in eight
hours. Password reset or account disabling revokes sessions. Human actions bind
the authenticated account into the audit trail; self-entered evidence references
are still not proof. Gateway keys only permit ingestion, their own heartbeats,
and media/receipt access for reports uploaded through that gateway. Request size,
rate and cross-origin mutation checks apply. Production refuses startup without
account storage and an HTTPS public origin, and refuses plaintext API access.

The rate limiter is per process; use one worker with SQLite. Horizontal scale needs
a shared limiter and queue. Keep reliable monitoring, key recovery, encrypted
backups, patching and staff access reviews in the operator's runbook. OS force-stop,
OEM background restrictions and real-user acceptance still require device/field tests.

## AI deployment choice

Local Ollama/Gemma is the current implemented provider, including local media
interpretation. Gemma 4 E2B is also available through AWS Bedrock's `bedrock-mantle`
OpenAI-compatible endpoint, but Bedrock alone does not host this API/database/UI.
A cloud provider switch must be explicit; never silently send emergency recordings
to a third party when local inference fails. AWS account access, cost configuration
and an actual endpoint test are required before describing Bedrock as integrated.

References: [Caddy automatic HTTPS](https://caddyserver.com/docs/automatic-https),
[Gemma 4 E2B on Bedrock](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-google-gemma-4-e2b.html).
