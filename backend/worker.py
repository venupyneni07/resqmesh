from __future__ import annotations

import asyncio
import json
import logging
from contextlib import suppress

from .ai import InferenceUnavailable, InvalidAIOutput, OllamaAgents, now_ms
from .store import Store
from .signals import verification_signals
from .media_analysis import MediaAnalyzer

log = logging.getLogger("resqmesh.worker")


class Worker:
    def __init__(self, store: Store, agents: OllamaAgents, retry_seconds: float = 15):
        self.store, self.agents = store, agents
        self.retry_seconds = retry_seconds
        self.wake = asyncio.Event()
        self.active_job = None
        self.status = "checking"
        self.error = None
        self.checked_at = None
        self.media_analyzer = MediaAnalyzer(agents)
        self.active_media_job = None

    def ai_state(self) -> dict:
        return {"provider": "ollama", "model": self.agents.model, "status": self.status,
                "error": self.error, "checked_at": self.checked_at, "active_job": self.active_job,
                "queue": self.store.queue_counts(),
                "media": {"provider": "local_ollama", "model": self.agents.model,
                          "queue": self.store.media_queue_counts(), "active_job": self.active_media_job}}

    async def check_model(self):
        try:
            await self.agents.check()
            self.status, self.error = "ready", None
        except InferenceUnavailable as exc:
            self.status, self.error = "unavailable", str(exc)
        self.checked_at = now_ms()

    async def run(self):
        self.store.recover_jobs()
        self.store.recover_media_jobs()
        media_task = asyncio.create_task(self.run_media())
        try:
            await self.run_text()
        finally:
            media_task.cancel()
            with suppress(asyncio.CancelledError):
                await media_task

    async def run_media(self):
        # Separate loop: a large text backlog never prevents media review; model I/O
        # and bounded decoding never block receiving/acknowledging an SOS packet.
        while True:
            job = self.store.claim_media_job()
            if job:
                await self.process_media(job)
            else:
                await asyncio.sleep(0.5)

    async def process_media(self, job: dict):
        self.active_media_job = {"report_id": job["report_id"], "attachment_id": job["attachment_id"]}
        try:
            manifest = self.store.attachment(job["report_id"], job["attachment_id"])
            result = await self.media_analyzer.analyze(self.store.media_path(job["report_id"], job["attachment_id"]), manifest)
            self.store.save_media_analysis(job, result)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            error = str(exc)[:400] if isinstance(exc, (InferenceUnavailable, InvalidAIOutput)) else "Unexpected media analysis error; inspect backend logs"
            if not isinstance(exc, (InferenceUnavailable, InvalidAIOutput)):
                log.exception("Media analysis failed for %s", job["attachment_id"])
            self.store.fail_media_analysis(job, error, self.retry_seconds * (2 ** (job["attempts"] - 1)))
        finally:
            self.active_media_job = None

    async def run_text(self):
        await self.check_model()
        while True:
            job = self.store.claim_job()
            if job:
                await self.process(job)
                continue
            if self.checked_at is None or now_ms() - self.checked_at >= 30_000:
                await self.check_model()
            self.wake.clear()
            try:
                await asyncio.wait_for(self.wake.wait(), timeout=0.5)
            except TimeoutError:
                pass

    def set_stage(self, job: dict, stage: str):
        job["stage"] = stage
        self.active_job = {"kind": job["kind"], "target_id": job["target_id"], "stage": stage}
        self.store.stage(job["id"], stage)

    async def triage(self, context: dict):
        if len(json.dumps(context["reports"], ensure_ascii=False)) > 20_000:
            raise InvalidAIOutput("Incident exceeds the demo model context budget; human review is required")
        self.store.triage_running(context["id"])
        result = await self.agents.triage(context["reports"])
        self.store.save_triage(context, result)

    async def verify(self, context: dict):
        related = self.store.verification_context(context)
        signals = verification_signals(context["reports"], related)
        if len(json.dumps({"reports": context["reports"], "related": related, "signals": signals}, ensure_ascii=False)) > 22_000:
            raise InvalidAIOutput("Verification context exceeds the demo model budget; human review is required")
        result = await self.agents.verify(context["reports"], signals, related)
        self.store.save_verification(context, signals, result)

    async def correlate(self, source: dict):
        candidates = self.store.candidates(source["id"])
        # Keep whole reports: never hide text by truncating the evidence inside a selected report.
        # A deterministic character budget bounds input; omissions are recorded in audit.
        def context(item):
            return {"id": item["id"], "reports": [OllamaAgents.report_context(r) for r in item["reports"]]}
        source_payload = context(source)
        if len(json.dumps(source_payload, ensure_ascii=False)) > 14_000:
            raise InvalidAIOutput("Source incident exceeds the correlation context budget; human review is required")
        selected, payloads = [], []
        for candidate in candidates:
            proposed = context(candidate)
            if len(json.dumps({"source": source_payload, "candidates": payloads + [proposed]}, ensure_ascii=False)) <= 14_000:
                selected.append(candidate)
                payloads.append(proposed)
        proposals = await self.agents.correlate(source_payload, payloads)
        self.store.save_correlations(source, selected, proposals, self.agents.model, omitted=len(candidates) - len(selected))

    async def process(self, job: dict):
        try:
            if job["kind"] == "report":
                report = self.store.report(job["target_id"])
                self.store.report_running(report["id"])
                incident = self.store.incident_context(report["incident_id"])
            else:
                incident = self.store.incident_context(job["target_id"])
            for report in incident["reports"]:
                if report["intake"] is not None:
                    continue
                incident = self.store.incident_context(incident["id"])
                self.set_stage(job, "intake")
                self.store.stage_status(incident, "intake", "running")
                try:
                    self.store.report_running(report["id"])
                    result = await self.agents.intake(report)
                    self.store.save_intake(report["id"], result)
                except (InferenceUnavailable, InvalidAIOutput) as exc:
                    self.store.stage_status(incident, "intake", "unavailable" if isinstance(exc, InferenceUnavailable) else "failed", str(exc))
                    raise
            failures = []
            for stage, operation in (("correlation", self.correlate), ("verification", self.verify), ("triage", self.triage)):
                # Re-read revision and membership before each step; merges can happen in flight.
                incident = self.store.incident_context(incident["id"])
                if incident["processing_stages"][stage]["status"] in ("complete", "not_applicable"):
                    continue
                self.set_stage(job, stage)
                self.store.stage_status(incident, stage, "running")
                try:
                    await operation(incident)
                except (InferenceUnavailable, InvalidAIOutput) as exc:
                    self.store.stage_status(incident, stage, "unavailable" if isinstance(exc, InferenceUnavailable) else "failed", str(exc))
                    failures.append((stage, exc))
                    # Verification/correlation failure does not withhold an available triage.
            if failures:
                job["stage"] = failures[0][0]
                raise failures[0][1]
            incident = self.store.incident_context(incident["id"])
            complete = all(stage["status"] in ("complete", "not_applicable") for stage in incident["processing_stages"].values())
            self.store.finish_job(job, complete=complete, incident_id=incident["id"])
            self.status, self.error, self.checked_at = "ready", None, now_ms()
        except asyncio.CancelledError:
            # Leave running work durable; startup recovery requeues it.
            raise
        except Exception as exc:
            unavailable = isinstance(exc, InferenceUnavailable)
            error = str(exc)[:400] if isinstance(exc, (InferenceUnavailable, InvalidAIOutput)) else "Unexpected AI processing error; inspect backend logs"
            if not isinstance(exc, (InferenceUnavailable, InvalidAIOutput)):
                log.exception("AI job %s failed", job["id"])
            if unavailable:
                self.status, self.error, self.checked_at = "unavailable", error, now_ms()
            self.store.fail_job(job, error, unavailable, self.retry_seconds * (2 ** (job["attempts"] - 1)))
        finally:
            self.active_job = None
