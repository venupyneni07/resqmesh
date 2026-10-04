# Synthetic model fixtures

These files were generated for local software checks. They are not user microphone/camera captures or reports of real emergencies.

- `synthetic-voice.m4a`: generated English speech asking for help near a blue gate, explicitly introduced as a test recording.
- `synthetic-image.jpg`: generated plain blue image.
- `synthetic-video.mp4`: generated visuals with a generated speech track.

Run the opt-in local-model harness as described in [testing](../../../../docs/TESTING.md). It verifies upload, durable processing and provenance. Model completion does not establish perceptual accuracy; the plain image previously produced invented text observations. Real user recordings and inference diagnostics stay in ignored local directories.
