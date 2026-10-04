"use strict";

// All remote text is inserted with textContent. No report or model output is HTML.
(() => {
  const $ = (id) => document.getElementById(id);
  const initialState = { reports: [], incidents: [], correlations: [], audit: [], ai: {}, stats: {} };
  let state = initialState;
  let selectedFilter = "active";
  let selectedIncidentId = null;
  let selectedCaseTab = "overview";
  let selectedSort = "priority";
  let currentView = "overview";
  let incidentPage = 1;
  let reportPage = 1;
  let correlationPage = 1;
  let selectedCorrelationId = null;
  let correlationDecisionPending = false;
  let correlationRefreshRequired = false;
  let incidentPageSize = 5;
  let reportPageSize = 6;
  let incidentResizeAnchor = null;
  let reportResizeAnchor = null;
  let capacityFramePending = false;
  let paneSizeObserver = null;
  const correlationPageSize = 8;
  let connected = false;
  let loading = false;
  let refreshTimer;
  let toastTimer;
  let key = ""; // Optional API key lives in memory only.
  let account = {name: "Local demo", role: "demo"};
  const seenMediaResults = new Set();
  let mediaBaselineLoaded = false;
  let renderSignature = "";
  let detailSignature = "";
  let correlationSignature = "";
  let lastReportCount = 0;
  let formDirty = false;
  const dirtyCaseFields = new Set();
  const caseDrafts = new Map(); // Sensitive unfinished notes live only in this page's memory.
  let correlationScope = null;
  let mutationVersion = 0;
  let authEpoch = 0;
  let refreshAfterCurrent = false;
  let stateReceivedAt = Date.now();

  const statusLabels = {new: "New", acknowledged: "Acknowledged", in_progress: "In progress", resolved: "Resolved"};
  const urgencyLabels = {critical: "Critical", high: "High", medium: "Medium", normal: "Normal", low: "Low", unknown: "Unassessed"};
  const sourceTypeLabels = {medical: "Medical emergency", fire: "Fire / smoke", flood: "Flooding", accident: "Accident", trapped: "People trapped", safety_threat: "Safety threat", other: "Other emergency"};
  const quickNeedLabels = {cannot_move: "Cannot move", cannot_speak: "Cannot speak", people_injured: "People injured"};
  const mediaLabels = {audio: "Voice message", image: "Photo", video: "Video"};
  const mediaDialogs = new Set();
  const simulationLocationNote = "Simulation location · not a verified person’s position";
  const verificationLabels = {unverified: "Unverified", verification_requested: "Verification requested", corroborated: "Human-marked corroborated", responder_verified: "Responder verified", false_closed: "Human-closed as false"};
  const checkMethods = {on_site: "On-site observation", callback: "Callback to a known contact", independent_witness: "Independent witness check", external_reference: "External evidence reference", other: "Other documented check"};
  const stageDefinitions = [["intake", "Intake"], ["correlation", "Correlation"], ["verification", "Verification"], ["triage", "Triage"]];
  const array = (value) => Array.isArray(value) ? value : [];
  const shortId = (id) => String(id || "Unknown").slice(0, 8).toUpperCase();
  const stringValue = (value, fallback = "Not provided") => value == null || value === "" ? fallback : typeof value === "object" ? JSON.stringify(value) : String(value);
  const humanField = (value) => stringValue(value, "Unknown").replace(/_/g, " ");
  function node(tag, className, value) {
    const result = document.createElement(tag);
    if (className) result.className = className;
    if (value !== undefined) result.textContent = String(value);
    return result;
  }
  function tag(value, style = "neutral") { return node("span", `tag tag-${style}`, value); }
  function button(label, className, handler) {
    const result = node("button", `button ${className}`, label);
    result.type = "button";
    if (handler) result.addEventListener("click", handler);
    return result;
  }
  function replace(target, children) { target.replaceChildren(...children); }
  function date(value, full = false) {
    if (!value) return "Time unavailable";
    const d = new Date(value);
    if (Number.isNaN(d.valueOf())) return "Time unavailable";
    return d.toLocaleString(undefined, full ? {month: "short", day: "numeric", hour: "2-digit", minute: "2-digit"} : {hour: "2-digit", minute: "2-digit", second: "2-digit"});
  }
  function timeNode(value, full = false) {
    const result = node("time", "", date(value, full));
    if (value && !Number.isNaN(new Date(value).valueOf())) result.dateTime = new Date(value).toISOString();
    return result;
  }
  function empty(title, text, symbol = "⌁") {
    const result = node("div", "empty-state");
    const icon = node("span", "empty-symbol", symbol);
    icon.setAttribute("aria-hidden", "true");
    result.append(icon, node("h3", "", title), node("p", "", text));
    return result;
  }
  function toast(message) {
    const dialogs = [...document.querySelectorAll("dialog[open]")];
    (dialogs.at(-1) || document.body).append($("toast"));
    $("toast").textContent = message;
    $("toast").hidden = false;
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => { $("toast").hidden = true; }, 5000);
  }
  function reportsFor(incident) { return state.reports.filter((report) => array(incident.report_ids).includes(report.id)); }
  function activeIncidents() { return state.incidents.filter((incident) => !incident.merged_into); }
  function pendingCorrelations() { return state.correlations.filter((item) => item.status === "pending"); }
  function location(incident) {
    const reports = reportsFor(incident);
    const fields = [["building", ""], ["location_text", ""], ["floor", "Floor "], ["room", "Room "], ["zone", ""]];
    let differing = false;
    const parts = fields.flatMap(([key, prefix]) => {
      const supplied = [...new Set(reports.map((report) => report[key]).filter(Boolean))];
      if (supplied.length > 1) differing = true;
      const value = incident[key] || (supplied.length === 1 ? supplied[0] : null);
      return value ? [`${prefix}${value}`] : [];
    });
    const text = [...new Set(parts)].join(" / ");
    if (differing) return `${text || "Multiple reported locations"} · details differ across reports`;
    if (text) return text;
    const positioned = reports.filter((report) => Number.isFinite(report.location_context?.latitude) && Number.isFinite(report.location_context?.longitude));
    if (positioned.length) return positioned.length === 1 ? coordinateLabel(positioned[0].location_context, positioned[0].simulation) : "Coordinates supplied · review each observation";
    return reports.length < array(incident.report_ids).length ? "Location unavailable · review original reports" : "Location not provided";
  }
  function coordinateLabel(context, simulation = false) {
    return `${context.latitude.toFixed(5)}, ${context.longitude.toFixed(5)} · ${simulation ? simulationLocationNote : `${humanField(context.source)} observation`}`;
  }
  function locationObservation(report) {
    const context = report.location_context;
    if (!context) return "Provenance not recorded in this report version";
    if (context.source === "unknown") return "Unknown · no position attached";
    const source = {manual: "Manually entered", saved: "Saved location", device: "Device location"}[context.source] || "Source not recorded";
    const age = Number.isFinite(report.created_at) && Number.isFinite(context.observed_at) ? report.created_at - context.observed_at : null;
    const freshness = age === null ? "age unknown" : age < 0 ? "observation time is after report time · check device clock" : age < 60_000 ? "under 1 minute old at SOS" : age < 3_600_000 ? `${Math.floor(age / 60_000)} minute(s) old at SOS` : `${Math.floor(age / 3_600_000)} hour(s) old at SOS`;
    return `${source} · ${freshness}; current position unconfirmed`;
  }
  function simulationTag(report) { return report.simulation === true ? tag("SIMULATED", "simulation") : tag("DEVICE REPORT", "neutral"); }
  function incidentMode(incident) {
    const reports = reportsFor(incident);
    if (reports.length && reports.every((report) => report.simulation)) return tag("SIMULATED", "simulation");
    if (reports.some((report) => report.simulation)) return tag("MIXED PROVENANCE", "warning");
    return tag("DEVICE REPORT", "neutral");
  }
  function mediaFindings(incident) {
    return reportsFor(incident).flatMap((report) => array(report.media_analysis).filter((entry) => entry.status === "complete" && entry.result).map((entry) => ({report, ...entry})));
  }
  function urgency(incident) {
    const rank = {critical: 0, high: 1, medium: 2, normal: 2, low: 3, unknown: 4};
    const suggestions = [incident.triage?.suggested_urgency || "unknown", ...mediaFindings(incident).map((entry) => entry.result.suggested_urgency)];
    const chosen = suggestions.sort((a, b) => (rank[a] ?? 4) - (rank[b] ?? 4))[0];
    return chosen;
  }
  function urgencyTag(incident) {
    const level = urgency(incident);
    const mediaSuggested = mediaFindings(incident).some((entry) => entry.result.suggested_urgency === level);
    return tag(level !== "unknown" ? `${urgencyLabels[level] || "Unknown"} · AI${mediaSuggested ? " media" : ""} suggestion` : "URGENCY UNASSESSED", ["critical", "high"].includes(level) ? "warning" : "neutral");
  }
  function verificationTag(incident) {
    const status = incident.verification_status || "unverified";
    return tag(verificationLabels[status] || humanField(status), ["corroborated", "responder_verified"].includes(status) ? "good" : status === "false_closed" ? "warning" : "neutral");
  }
  function processingStages(incident, compact = false) {
    const stages = node("div", `processing-stages${compact ? " compact" : ""}`);
    stages.setAttribute("aria-label", "Incident AI processing stages");
    stageDefinitions.forEach(([kind, label], index) => {
      const stage = incident.processing_stages?.[kind];
      const status = stage?.status || "unknown";
      const item = node("div", `processing-stage stage-${status}`);
      item.append(node("span", "stage-number", String(index + 1).padStart(2, "0")), node("strong", "", label), node("span", "stage-state", humanField(status)));
      if (!compact && stage?.error) item.append(node("p", "stage-error", stage.error));
      if (!compact && stage?.updated_at) item.append(node("small", "", `Updated ${date(stage.updated_at)}`));
      stages.append(item);
    });
    return stages;
  }
  function sourceFields(report) {
    const fields = [["Emergency type", report.emergency_type ? humanField(report.emergency_type) : null], ["Location text", report.location_text], ["Building / landmark", report.building], ["Floor", report.floor], ["Room", report.room], ["Zone", report.zone], ["People affected", report.people_affected], ["Vulnerability", report.vulnerability]];
    if (report.schema_version >= 3) {
      fields.unshift(["Message entry", report.message_source === "preset" ? "No typed message · app default text" : "Optional message supplied by source"], ["Selected needs", array(report.quick_needs).map((need) => quickNeedLabels[need] || humanField(need)).join(" · ") || "None selected · needs unknown"]);
      fields.push(["Location provenance", locationObservation(report)]);
      const context = report.location_context;
      if (context?.observed_at) fields.push(["Location observed", date(context.observed_at, true)]);
      if (Number.isFinite(context?.latitude) && Number.isFinite(context?.longitude)) fields.push(["Coordinates", `${context.latitude}, ${context.longitude}${report.simulation ? ` · ${simulationLocationNote}` : ""}`], ["Accuracy", Number.isFinite(context.accuracy_m) ? `Reported ±${context.accuracy_m} m; not independently verified` : "Not supplied"]);
    }
    const supplied = fields.filter(([, value]) => value !== undefined && value !== null && value !== "");
    if (!supplied.length) return null;
    const block = node("div", "source-fields");
    block.append(node("div", "raw-label", "SOURCE-PROVIDED FIELDS / NOT INDEPENDENTLY VERIFIED"));
    const facts = node("dl", "facts-grid");
    supplied.forEach(([label, value]) => facts.append(node("dt", "", label), node("dd", "", stringValue(value))));
    block.append(facts);
    return block;
  }
  function receiptSummary(report) {
    const receipts = array(report.receipts);
    const summary = node("div", "receipt-summary");
    const acknowledged = receipts.some((receipt) => receipt.type === "responder_acknowledged");
    const accepted = receipts.some((receipt) => receipt.type === "backend_received");
    summary.append(node("strong", "", acknowledged ? "Responder acknowledgement recorded" : accepted ? "Backend receipt issued" : "No receipt record available"), node("span", "", "Return delivery to origin: unknown to this dashboard"));
    return summary;
  }
  function receiptEvidence(reports) {
    const sectionEl = section("Receipts / recorded vs returned");
    sectionEl.append(node("div", "receipt-unconfirmed", "This dashboard can confirm receipt issuance and gateway uploads. It has no evidence that a receipt reached the original reporting device. Receipt paths below describe the original upload, not a return route."));
    reports.forEach((report) => {
      const group = node("div", "receipt-report");
      group.append(node("h4", "detail-subheading", `SOS ${shortId(report.id)} · origin ${report.origin_id}`));
      const receipts = array(report.receipts);
      if (!receipts.length) group.append(node("p", "detail-muted", "No receipt records are available for this report."));
      receipts.forEach((receipt) => {
        const entry = node("article", "receipt-record");
        const meta = node("div", "incident-meta");
        const label = receipt.type === "responder_acknowledged" ? "RESPONDER ACK RECORDED" : receipt.type === "backend_received" ? "BACKEND RECEIVED" : humanField(receipt.type);
        meta.append(tag(label, "good"), node("span", "incident-id", `RECEIPT / ${shortId(receipt.id)}`), simulationTag(receipt));
        const facts = node("dl", "facts-grid");
        [["Issued", date(receipt.timestamp, true)], ["Gateway on receipt", receipt.gateway_id || "No gateway specified · operator-issued"], ["Issuer", receipt.issuer || "Not supplied"], ["Trust evidence", receipt.trust === "backend_issued_unattested" ? "Backend-issued; no cryptographic attestation" : stringValue(receipt.trust)], ["Delivery to origin", "Unknown · no return-delivery evidence available"]].forEach(([key, value]) => facts.append(node("dt", "", key), node("dd", "", value)));
        entry.append(meta, facts);
        if (array(receipt.relay_path).length) entry.append(node("div", "relay-label receipt-route-label", "ORIGINAL UPLOAD ROUTE CARRIED IN RECEIPT"), node("p", "receipt-route", receipt.relay_path.join(" → ")));
        group.append(entry);
      });
      const deliveries = array(report.deliveries);
      if (deliveries.length) {
        const arrivals = node("details", "delivery-evidence");
        arrivals.append(node("summary", "", `Inspect gateway upload evidence (${deliveries.length})`));
        deliveries.forEach((delivery) => {
          const entry = node("div", "receipt-record");
          entry.append(node("strong", "", `Gateway ${delivery.gateway_id || "not recorded"}`), node("p", "", `${Number(delivery.attempts) || 0} recorded upload attempt(s) · first ${date(delivery.first_seen, true)} · latest ${date(delivery.last_seen, true)}`));
          if (array(delivery.relay_path).length) entry.append(node("p", "receipt-route", delivery.relay_path.join(" → ")));
          arrivals.append(entry);
        });
        group.append(arrivals);
      }
      sectionEl.append(group);
    });
    return sectionEl;
  }
  function relayPath(report) {
    const container = node("div", "relay-section");
    container.append(node("div", "relay-label", report.simulation ? "SIMULATED ROUTE / SENDER-REPORTED" : "RELAY ROUTE / SENDER-REPORTED"));
    const path = node("div", "relay-path");
    const route = [...array(report.relay_path)];
    if (route.length) {
      route.push("BACKEND");
      path.setAttribute("aria-label", `Relay path: ${route.join(" to ")}`);
      route.forEach((id, index) => {
        if (index) path.append(node("span", "relay-arrow", "→"));
        path.append(node("span", "relay-node", id));
      });
    } else path.append(node("span", "relay-empty", "No relay path supplied"));
    container.append(path);
    return container;
  }

  async function api(path, options = {}) {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 10000);
    try {
      const response = await fetch(path, {
        ...options,
        cache: "no-store",
        signal: controller.signal,
        headers: { ...(options.body ? {"Content-Type": "application/json"} : {}), ...(key ? {"X-API-Key": key} : {}), ...options.headers },
      });
      let result;
      try { result = await response.json(); } catch { throw new Error(`The backend returned an unreadable response (${response.status}).`); }
      if (!response.ok) {
        const detail = result.detail;
        const error = new Error(typeof detail === "string" ? detail : Array.isArray(detail) ? detail.map((item) => item.msg || "Validation error").join("; ") : detail ? JSON.stringify(detail) : `Request failed (${response.status}).`);
        error.status = response.status;
        throw error;
      }
      return result;
    } catch (error) {
      if (error.name === "AbortError") throw new Error("The backend took too long to respond. Your current data is retained.");
      throw error;
    } finally { window.clearTimeout(timeout); }
  }

  function setConnection(ok, error) {
    connected = ok;
    $("connection-dot").className = `status-dot ${ok ? "good" : "bad"}`;
    $("rail-dot").className = `status-dot ${ok ? "good" : "bad"}`;
    $("connection-label").textContent = ok ? "Backend connected" : "Connection unavailable";
    $("rail-status").textContent = ok ? "Local backend connected" : "Backend unavailable";
    $("connection-error").hidden = ok;
    if (!ok) {
      $("connection-error-text").textContent = `${error?.message || "Unable to reach the local backend."} ${state.reports.length ? "The last received data is still shown." : "Start the ResQMesh backend to receive reports."}`;
      $("retry-button").textContent = error?.status === 401 || error?.status === 403 ? "Enter access key" : "Try again";
      $("retry-button").dataset.authRequired = error?.status === 401 || error?.status === 403 ? "true" : "false";
      renderGateways();
      renderOperationsRail();
    }
  }

  async function refresh({force = false} = {}) {
    if (loading) { if (force) refreshAfterCurrent = true; return; }
    loading = true;
    window.clearTimeout(refreshTimer);
    const version = mutationVersion;
    const session = authEpoch;
    try {
      const next = await api("/api/state");
      // A request started before an operator action must not overwrite its result.
      if (session !== authEpoch) return;
      if (version !== mutationVersion) { refreshAfterCurrent = true; return; }
      if (!Array.isArray(next.reports) || !Array.isArray(next.incidents) || !Array.isArray(next.correlations)) throw new Error("The backend response does not match the ResQMesh API contract.");
      state = { ...initialState, ...next };
      account = state.account || {name: "Local demo", role: "demo"};
      if ($("account-button")) $("account-button").textContent = account.role === "demo" ? "Local demo" : `Sign out · ${account.name} (${account.role})`;
      if ($("account-status")) $("account-status").textContent = account.role === "demo" ? "Local demo · no individual account" : account.role === "viewer" ? "Read-only access" : "Authenticated workspace";
      announceMedia();
      if (correlationRefreshRequired) { correlationRefreshRequired = false; correlationSignature = ""; }
      stateReceivedAt = Date.now();
      setConnection(true);
      $("last-sync").textContent = date(Date.now());
      renderSummary();
      renderAI();
      renderOperationsRail();
      const signature = JSON.stringify([state.reports, state.incidents, state.correlations, state.truncated]);
      if ((force || signature !== renderSignature) && !boardHasInteraction()) {
        renderSignature = signature;
        renderIncidents();
        renderReports();
      }
      if (state.reports.length > lastReportCount && lastReportCount > 0) $("announcer").textContent = `${state.reports.length - lastReportCount} new report received.`;
      lastReportCount = state.reports.length;
      maybeRefreshDialogs();
    } catch (error) {
      if (session !== authEpoch) return;
      if (error.status === 401 && renderSignature) clearWorkspace();
      setConnection(false, error);
      if (!renderSignature) {
        replace($("incidents-list"), [empty("The response board is offline", "No data has been loaded. Check that the backend is running, then try again.", "⌁")]);
        $("model-label").textContent = "Model state unavailable";
        $("model-state").textContent = "OFFLINE";
        $("model-state").className = "tag tag-warning";
        [...$("agent-grid").querySelectorAll("small")].forEach((item) => { item.textContent = "Backend state unavailable"; });
      }
    } finally {
      loading = false;
      $("incidents-list").setAttribute("aria-busy", "false");
      $("reports-list").setAttribute("aria-busy", "false");
      const refreshImmediately = refreshAfterCurrent;
      refreshAfterCurrent = false;
      refreshTimer = window.setTimeout(() => refresh(), refreshImmediately ? 0 : document.hidden ? 10000 : 3000);
    }
  }

  function clearWorkspace() {
    authEpoch++;
    mutationVersion++;
    closeMedia();
    document.querySelectorAll("dialog[open]").forEach((dialog) => dialog.close());
    state = {...initialState};
    caseDrafts.clear(); dirtyCaseFields.clear(); formDirty = false;
    selectedIncidentId = null; selectedCorrelationId = null;
    correlationDecisionPending = false; correlationRefreshRequired = false;
    renderSignature = ""; detailSignature = ""; lastReportCount = 0;
    seenMediaResults.clear(); mediaBaselineLoaded = false;
    account = {name: "Signed out", role: "demo"};
    if ($("account-button")) $("account-button").textContent = "Sign in";
    if ($("account-status")) $("account-status").textContent = "Signed out";
    renderIncidents(); renderReports(); renderSummary(); renderOperationsRail();
    $("detail-content").replaceChildren();
    $("correlation-content").replaceChildren();
  }

  function announceMedia() {
    for (const report of state.reports) for (const analysis of array(report.media_analysis)) {
      if (analysis.status !== "complete" || !analysis.result) continue;
      const identity = `${analysis.attachment_id}:${analysis.result.generated_at}`;
      if (seenMediaResults.has(identity)) continue;
      seenMediaResults.add(identity);
      if (!mediaBaselineLoaded) continue;
      const urgent = ["high", "critical"].includes(analysis.result.suggested_urgency);
      const message = urgent ? `AI suggests ${analysis.result.suggested_urgency} urgency for SOS ${shortId(report.id)}. Review the recording and uncertainty.` : `Media analysis complete for SOS ${shortId(report.id)}. Open the report to review the result and uncertainty.`;
      toast(message);
      $("announcer").textContent = message;
      if (urgent && "Notification" in window && Notification.permission === "granted") {
        // Keep sensitive incident details off the system/lock-screen notification.
        try {
          const notice = new Notification("ResQMesh · review requested", {body: "New media analysis needs human review. Open the response workspace.", tag: identity});
          notice.onclick = () => { window.focus(); openIncident(report.incident_id, "reports", report.id); notice.close(); };
        } catch { /* Platform notification restrictions must not stop dashboard updates. */ }
      }
    }
    mediaBaselineLoaded = true;
  }

  function boardHasInteraction() {
    const selection = window.getSelection();
    return [$("incidents-list"), $("reports-list"), $("incident-pagination"), $("report-pagination")].filter(Boolean).some((container) =>
      container.contains(document.activeElement) || (selection && !selection.isCollapsed && container.contains(selection.anchorNode))
    );
  }

  function renderSummary() {
    const incidents = activeIncidents();
    const reviewCount = pendingCorrelations().length;
    $("stat-reports").textContent = state.stats.reports ?? state.reports.length;
    $("stat-active").textContent = state.stats.open_incidents ?? incidents.filter((item) => item.status !== "resolved").length;
    $("stat-critical").textContent = state.stats.critical_incidents ?? incidents.filter((item) => item.status !== "resolved" && urgency(item) === "critical").length;
    $("stat-verification").textContent = state.stats.awaiting_verification ?? incidents.filter((item) => item.status !== "resolved" && ["unverified", "verification_requested"].includes(item.verification_status || "unverified")).length;
    $("stat-corroborated").textContent = incidents.filter((item) => item.verification_status === "corroborated").length + (state.truncated?.incidents ? "+" : "");
    $("rail-origin-count").textContent = new Set(state.reports.map((report) => report.origin_id).filter(Boolean)).size + (state.truncated?.reports ? "+" : "");
    for (const id of ["review-count", "nav-review-count"]) $(id).textContent = state.stats.pending_correlations ?? reviewCount;
    $("report-count").textContent = state.reports.length + (state.truncated?.reports ? "+" : "");
    const simulated = state.reports.filter((report) => report.simulation === true).length;
    const physical = state.reports.length - simulated;
    $("workspace-provenance").textContent = !state.reports.length ? "AWAITING REPORTS" : simulated && physical ? "MIXED TEST WORKSPACE" : physical ? "PHYSICAL PROTOTYPE" : "SIMULATION WORKSPACE";
    $("workspace-provenance").className = `tag tag-${simulated ? "simulation" : "neutral"}`;
    $("workspace-caveat").textContent = !state.reports.length ? "Routes and provenance come from incoming reports. Physical radio delivery requires device testing." : simulated && physical ? "Includes simulated and device-reported paths. Labels preserve provenance; actual radio behavior requires physical validation." : physical ? "Device-reported relay paths are unverified telemetry. Real Nearby / Bluetooth delivery must be validated on physical devices." : "Relay paths are simulated. Real Nearby / Bluetooth communication requires physical-device testing.";
    renderGateways();
  }

  function renderGateways() {
    if (!connected || !Array.isArray(state.gateways)) {
      $("stat-gateways").textContent = "—";
      replace($("gateway-list"), [node("span", "detail-muted", connected ? "No heartbeat state provided by this backend" : "Heartbeat state unavailable · connectivity unknown")]);
      return;
    }
    const serverNow = Number(state.server_time || stateReceivedAt) + (Date.now() - stateReceivedAt);
    const gateways = state.gateways.filter((gateway) => Number(gateway.last_seen) > 0 && Number(gateway.expires_at) > serverNow && gateway.online === true);
    $("stat-gateways").textContent = gateways.length;
    if (!gateways.length) {
      replace($("gateway-list"), [node("span", "detail-muted", "No active gateway heartbeat leases. A previously received report does not imply an online gateway.")]);
      return;
    }
    replace($("gateway-list"), gateways.map((gateway) => {
      const item = node("div", "gateway-presence");
      item.append(node("span", "status-dot good"), node("strong", "", gateway.node_id), tag(gateway.simulation ? "SIMULATED" : "DEVICE", gateway.simulation ? "simulation" : "neutral"), node("small", "", `Heartbeat ${date(gateway.last_seen)} · lease until ${date(gateway.expires_at)}`));
      return item;
    }));
  }

  function renderAI() {
    const ai = state.ai || {};
    const ready = ai.status === "ready";
    const unavailable = ai.status === "unavailable";
    $("model-label").textContent = `${ai.model || "Model not configured"} · ${ai.provider === "ollama" ? "local Ollama inference" : ai.provider || "provider unknown"}`;
    $("model-state").textContent = ready ? "MODEL AVAILABLE" : unavailable ? "MODEL UNAVAILABLE" : "CHECKING MODEL";
    $("model-state").className = `tag tag-${ready ? "good" : unavailable ? "warning" : "neutral"}`;
    const active = ai.active_job;
    const agents = stageDefinitions.map(([kind, label], index) => [kind, `${String(index + 1).padStart(2, "0")} / ${label}`]);
    replace($("agent-grid"), agents.map(([kind, label]) => {
      const item = node("div", "agent-status");
      const running = active && active.stage === kind;
      const stages = activeIncidents().map((incident) => incident.processing_stages?.[kind]);
      const failed = stages.filter((stage) => stage?.status === "failed").length;
      const stageUnavailable = stages.filter((stage) => stage?.status === "unavailable").length;
      const problems = [failed ? `${failed} failed` : "", stageUnavailable ? `${stageUnavailable} unavailable` : ""].filter(Boolean).join(" · ");
      let detail = unavailable ? "Unavailable · reports retained" : ready ? "Ready · waiting for work" : "Checking local model";
      if (running) detail = `Running${problems ? ` · ${problems}` : ` · ${humanField(active.stage || kind)}`}`;
      else if (problems) detail = `${problems} incident stage(s) · review incidents`;
      else if (ready && kind === "correlation" && pendingCorrelations().length) detail = `${pendingCorrelations().length} suggestion(s) awaiting human review`;
      else if (ready && Number(ai.queue?.queued) > 0) detail = `${ai.queue.queued} job(s) in shared queue`;
      item.append(node("span", `status-dot ${running ? "busy" : problems || unavailable ? "bad" : ready ? "good" : "neutral"}`));
      const copy = node("div");
      copy.append(node("strong", "", label), node("small", "", detail));
      item.append(copy);
      return item;
    }));
    $("ai-footnote").textContent = ai.error ? `Model status: ${ai.error} Original reports remain available. No substitute AI output is generated.` : `${Number(ai.queue?.failed) > 0 ? `${ai.queue.failed} failed job(s). ` : ""}AI organizes evidence and uncertainty. Verification, correlation, assignment, and resolution remain human decisions.`;
  }

  function incidentOrder(a, b, sort = selectedSort) {
    const created = (Number(a.created_at) || 0) - (Number(b.created_at) || 0);
    if (sort === "newest") return -created || String(a.id).localeCompare(String(b.id));
    if (sort === "oldest") return created || String(a.id).localeCompare(String(b.id));
    const rank = {critical: 0, high: 1, medium: 2, normal: 2, low: 3, unknown: 4};
    return (rank[urgency(a)] ?? 4) - (rank[urgency(b)] ?? 4) || -created || String(a.id).localeCompare(String(b.id));
  }

  function syncFilterButtons() {
    document.querySelectorAll("[data-filter]").forEach((item) => {
      const active = item.dataset.filter === selectedFilter;
      item.classList.toggle("active", active);
      item.setAttribute("aria-pressed", String(active));
    });

  }

  function setView(view) {
    if (!["overview", "verification", "reports", "network"].includes(view)) return;
    currentView = view;
    if ($("workspace-main")) $("workspace-main").dataset.view = view;
    const sections = {"overview-summary": view === "overview", "workspace-grid": view === "overview" || view === "verification", "reports-section": view === "reports", "system-section": view === "network"};
    Object.entries(sections).forEach(([id, visible]) => { if ($(id)) $(id).hidden = !visible; });
    document.querySelectorAll("button[data-view]").forEach((item) => {
      const active = item.dataset.view === view;
      item.classList.toggle("active", active);
      if (active) item.setAttribute("aria-current", "page");
      else item.removeAttribute("aria-current");
    });
    const headings = {overview: ["Response Center", "Prioritize incoming incidents and coordinate a human response."], verification: ["Verification desk", "Compare source evidence, document independent checks, and record a human decision."], reports: ["Source reports", "Inspect original reports and their recorded delivery evidence."], network: ["Network & intelligence", "Monitor actual gateway leases, connectivity, and the four processing stages."]};
    if ($("page-title")) $("page-title").textContent = headings[view][0];
    if ($("page-description")) $("page-description").textContent = headings[view][1];
    if (view === "overview" || view === "verification") {
      selectedFilter = view === "verification" ? "verification" : "active";
      incidentPage = 1;
      $("incident-search").value = "";
      syncFilterButtons();
      renderIncidents();
    } else if (view === "reports") renderReports();
    ["incidents-list", "reports-list", "system-section", "workspace-grid", "insights-column"].forEach((id) => { if ($(id)) $(id).scrollTop = 0; });
    document.querySelectorAll(".insights-column").forEach((pane) => { pane.scrollTop = 0; });
  }

  function renderPagination(target, page, count, size, onPage) {
    if (!target) return;
    const pages = Math.max(1, Math.ceil(count / size));
    const summary = node("span", "pagination-summary", count ? `${(page - 1) * size + 1}–${Math.min(page * size, count)} of ${count}` : "0 results");
    const controls = node("div", "pagination-controls");
    const move = (next, direction) => {
      onPage(next);
      const current = target.id ? $(target.id) : target;
      const preferred = current?.querySelector(`[data-page="${direction}"]`);
      const fallback = current?.querySelector(`[data-page="${direction === "next" ? "previous" : "next"}"]`);
      (preferred && !preferred.disabled ? preferred : fallback && !fallback.disabled ? fallback : current?.querySelector(".page-jump"))?.focus();
    };
    const previous = button("Previous", "button-outline small", () => move(page - 1, "previous"));
    previous.dataset.page = "previous";
    previous.disabled = page <= 1;
    const next = button("Next", "button-outline small", () => move(page + 1, "next"));
    next.dataset.page = "next";
    next.disabled = page >= pages;
    const jump = node("select", "page-jump");
    const noun = target.id === "incident-pagination" ? "incident" : target.id === "report-pagination" ? "report" : "correlation";
    jump.setAttribute("aria-label", `Go to ${noun} page`);
    for (let index = 1; index <= pages; index++) {
      const option = node("option", "", `Page ${index}`);
      option.value = String(index);
      jump.append(option);
    }
    jump.value = String(page);
    jump.disabled = pages <= 1;
    jump.addEventListener("change", () => {
      onPage(Number(jump.value));
      (target.id ? $(target.id) : target)?.querySelector(".page-jump")?.focus();
    });
    controls.append(previous, node("span", "pagination-position", `Page ${page} of ${pages}`), jump, next);
    replace(target, [summary, controls]);
  }

  function fitPanePages() {
    const capacity = (target, cssName, fallback) => {
      if (!target || !Number.isFinite(target.clientHeight) || target.clientHeight <= 0) return null;
      const style = typeof window.getComputedStyle === "function" ? window.getComputedStyle(target) : null;
      const rowHeight = parseFloat(style?.getPropertyValue(cssName)) || fallback;
      const padding = (parseFloat(style?.paddingTop) || 0) + (parseFloat(style?.paddingBottom) || 0);
      return Math.max(1, Math.min(10, Math.floor((target.clientHeight - padding) / rowHeight)));
    };
    if (["overview", "verification"].includes(currentView)) {
      const list = $("incidents-list");
      const next = capacity(list, "--incident-row-height", 70);
      if (next !== null && next !== incidentPageSize) {
        incidentResizeAnchor = list.querySelector(".incident-card")?.dataset.incidentId || null;
        const firstIndex = (incidentPage - 1) * incidentPageSize;
        incidentPageSize = next;
        incidentPage = Math.floor(firstIndex / next) + 1;
        renderIncidents();
      }
    }
    if (currentView === "reports") {
      const list = $("reports-list");
      const next = capacity(list, "--report-row-height", 86);
      if (next !== null && next !== reportPageSize) {
        reportResizeAnchor = list.querySelector(".report-list-row")?.dataset.reportId || null;
        const firstIndex = (reportPage - 1) * reportPageSize;
        reportPageSize = next;
        reportPage = Math.floor(firstIndex / next) + 1;
        renderReports();
      }
    }
  }

  function observePaneCapacity() {
    if (typeof ResizeObserver !== "function") return;
    paneSizeObserver = new ResizeObserver(() => {
      if (capacityFramePending) return;
      capacityFramePending = true;
      window.requestAnimationFrame(() => { capacityFramePending = false; fitPanePages(); });
    });
    [$("incidents-list"), $("reports-list")].filter(Boolean).forEach((pane) => paneSizeObserver.observe(pane));
  }

  function auditLabel(entry) {
    const labels = {"report.received": "SOS received by backend", "report.accepted": "SOS accepted by backend", "correlation.suggested": "Possible related incident found", "ai.job_failed": "AI processing needs attention", "report.duplicate": "Existing SOS uploaded again", "report.reanalysis_requested": "AI review requested again", "incident.created": "Incident opened", "incident.acknowledged": "Responder acknowledgement recorded", "incident.updated": "Response decision updated", "incidents.merged": "Incident correlation confirmed", "intake.completed": "Intake analysis completed", "triage.completed": "Triage advice prepared", "verification.completed": "Verification analysis completed", "verification.request_verification": "Human verification requested", "verification.corroborated": "Human corroboration recorded", "verification.responder_verified": "Responder check recorded", "verification.false_closed": "Closed as false by a human", "correlation.confirmed": "Relationship confirmed by a human", "correlation.rejected": "Relationship rejected by a human", "correlation.completed": "Related incident search completed"};
    return labels[entry.action] || humanField(String(entry.action || "Activity recorded").replace(/\./g, " "));
  }

  function renderOperationsRail() {
    const selection = window.getSelection();
    const canReplace = (target) => target && !target.contains(document.activeElement) && !(selection && !selection.isCollapsed && target.contains(selection.anchorNode));
    const desk = $("verification-desk");
    if (canReplace(desk)) {
      if (!connected && !state.server_time) replace(desk, [node("p", "detail-muted", "Verification queue unavailable until the backend connects.")]);
      else {
        const incidents = activeIncidents();
        const unverified = incidents.filter((item) => item.status !== "resolved" && (item.verification_status || "unverified") === "unverified");
        const requested = incidents.filter((item) => item.status !== "resolved" && item.verification_status === "verification_requested");
        const confirmed = incidents.filter((item) => ["corroborated", "responder_verified"].includes(item.verification_status));
        const counts = node("div", "verification-desk-counts");
        [[unverified.length, "Unverified"], [requested.length, "Check requested"], [confirmed.length, "Human-confirmed"]].forEach(([count, label]) => {
          const item = node("div", "desk-counts");
          item.append(node("strong", "", count), node("span", "", label));
          counts.append(item);
        });
        const guidance = node("details", "verification-guidance");
        guidance.append(node("summary", "", "How verification works"), node("p", "", "Compare original accounts, seek an independent check, and record the method and evidence. Similar messages or several anonymous IDs do not establish truth."), node("small", "", "AI flags uncertainty. A responder records the decision; this system does not calculate a real/fake score."));
        const pending = [...unverified, ...requested].sort((a, b) => incidentOrder(a, b, "priority"));
        const next = button(pending.length ? "Open next verification →" : "No verification waiting", "button-primary desk-next", () => {
          const nextIncident = activeIncidents().filter((item) => item.status !== "resolved" && ["unverified", "verification_requested"].includes(item.verification_status || "unverified")).sort((a, b) => incidentOrder(a, b, "priority"))[0];
          if (!nextIncident) { toast("No incidents are waiting for verification."); return; }
          setView("verification");
          openIncident(nextIncident.id, "verification");
        });
        next.disabled = !pending.length;
        const caption = node("p", "rail-data-note", `${connected ? "" : "Last received snapshot · "}${state.truncated?.incidents ? "Loaded incidents only. " : ""}Unverified/requested: open cases. Human checks include resolved cases. Source independence unknown; reviewer identity self-reported.`);
        replace(desk, [counts, guidance, next, caption]);
      }
    }
    const chart = $("arrival-chart");
    if (canReplace(chart)) {
      const serverNow = Number(state.server_time);
      if (!Number.isFinite(serverNow) || serverNow <= 0) replace(chart, [node("p", "detail-muted", "Arrival timestamps unavailable until reports load.")]);
      else {
        const start = serverNow - 3_600_000;
        const buckets = Array(12).fill(0);
        state.reports.forEach((report) => {
          const at = Number(report.received_at);
          if (Number.isFinite(at) && at >= start && at <= serverNow) buckets[Math.min(11, Math.floor((at - start) / 300_000))]++;
        });
        const total = buckets.reduce((sum, count) => sum + count, 0);
        const top = node("div", "arrival-chart-summary");
        top.append(node("strong", "", total), node("span", "", "reports received / last 60 min"));
        const bars = node("ol", "arrival-bars");
        bars.setAttribute("aria-label", "Backend arrivals in twelve five-minute intervals, oldest first");
        const largest = Math.max(1, ...buckets);
        buckets.forEach((count, index) => {
          const item = node("li", "arrival-bucket");
          const label = `${date(start + index * 300_000)}–${date(start + (index + 1) * 300_000)}: ${count} reports`;
          item.title = label;
          item.setAttribute("aria-label", label);
          const bar = node("span", "arrival-bar");
          bar.style.setProperty("--bar-height", `${count / largest * 100}%`);
          bar.style.height = `${count / largest * 100}%`;
          bar.setAttribute("aria-hidden", "true");
          item.append(bar, node("span", "arrival-count", count));
          bars.append(item);
        });
        const axis = node("div", "arrival-axis");
        axis.append(node("span", "", "60 min ago"), node("span", "", "Snapshot now"));
        replace(chart, [top, bars, axis, node("p", "arrival-caption", `Loaded reports only · backend received_at · 5-minute buckets. ${connected ? "" : "Last available snapshot. "}${state.truncated?.reports ? "Older reports may be outside the API window." : "Includes reports labeled as simulation in this workspace."}`)]);
      }
    }
    const feed = $("activity-feed");
    if (canReplace(feed)) {
      const entries = [...array(state.audit)].sort((a, b) => Number(b.at) - Number(a.at)).slice(0, 6);
      const list = node("ol", "activity-items");
      entries.forEach((entry) => {
        const item = node("li", "activity-item");
        const copy = node("div", "activity-copy");
        copy.append(node("strong", "", auditLabel(entry)), node("small", "", `${entry.actor || "Actor not recorded"}${entry.entity_id ? ` · ${shortId(entry.entity_id)}` : ""}`));
        item.append(copy, timeNode(entry.at));
        list.append(item);
      });
      replace(feed, entries.length ? [list] : [node("p", "detail-muted", connected ? "No recorded activity yet." : "Recorded activity unavailable.")]);
    }
  }

  function caseEvidenceSummary(incident, reports) {
    const summary = node("div", "case-evidence-summary");
    const reportCount = array(incident.report_ids).length;
    const origins = new Set(reports.map((report) => report.origin_id).filter(Boolean)).size;
    const facts = node("div", "case-evidence-counts");
    facts.append(node("strong", "", `${reportCount} original report${reportCount === 1 ? "" : "s"}`), node("span", "", `${origins}${reports.length === reportCount ? "" : "+"} claimed origin${origins === 1 ? "" : "s"}`), verificationTag(incident));
    summary.append(facts, node("p", "", "Source independence is unknown. Report agreement and AI analysis do not establish whether an emergency is real or false."), button("Review verification evidence →", "text-button", () => selectCaseTab("verification", true)));
    const selectedNeeds = [...new Set(reports.flatMap((report) => array(report.quick_needs)))];
    if (selectedNeeds.length) summary.append(node("p", "", `Selected needs across these reports: ${selectedNeeds.map((need) => quickNeedLabels[need] || humanField(need)).join(" · ")}. See each original report for its selections.`));
    if (reports.some((report) => report.message_source === "preset")) summary.append(node("p", "detail-muted", "Some reports have no typed message. Their emergency choices and selected needs are recorded separately from the app default text. Missing typed details does not imply a false report or low urgency."));
    return summary;
  }

  function selectCaseTab(id, focus = false, resetScroll = true) {
    if (!["overview", "verification", "reports", "activity"].includes(id)) return;
    selectedCaseTab = id;
    document.querySelectorAll("[data-case-tab]").forEach((tab) => {
      const selected = tab.dataset.caseTab === id;
      tab.setAttribute("aria-selected", String(selected));
      tab.tabIndex = selected ? 0 : -1;
      tab.classList.toggle("active", selected);
      const panel = $(tab.getAttribute("aria-controls"));
      if (panel) panel.hidden = !selected;
      if (selected && focus) tab.focus();
    });
    if (resetScroll && $("incident-dialog")) { $("incident-dialog").scrollTop = 0; $("detail-content").scrollTop = 0; }
  }

  function renderIncidents() {
    const term = $("incident-search").value.trim().toLocaleLowerCase();
    if ($("clear-incident-search")) $("clear-incident-search").hidden = !term;
    const incidents = activeIncidents().filter((incident) => {
      const filterMatch = selectedFilter === "all" || (selectedFilter === "resolved" ? incident.status === "resolved" : selectedFilter === "verification" ? incident.status !== "resolved" && ["unverified", "verification_requested"].includes(incident.verification_status || "unverified") : incident.status !== "resolved");
      const searchable = [incident.id, incident.title, incident.building, incident.zone, incident.verification_status, incident.triage?.summary, incident.team, ...reportsFor(incident).flatMap((report) => [report.text, report.location_text, report.floor, report.room, report.emergency_type])].filter(Boolean).join(" ").toLocaleLowerCase();
      return filterMatch && (!term || searchable.includes(term));
    }).sort((a, b) => incidentOrder(a, b));
    $("incident-count").textContent = incidents.length + (state.truncated?.incidents ? "+" : "");
    if (incidentResizeAnchor) {
      const anchorIndex = incidents.findIndex((incident) => incident.id === incidentResizeAnchor);
      if (anchorIndex >= 0) incidentPage = Math.floor(anchorIndex / incidentPageSize) + 1;
      incidentResizeAnchor = null;
    }
    incidentPage = Math.max(1, Math.min(incidentPage, Math.ceil(incidents.length / incidentPageSize) || 1));
    const visible = incidents.slice((incidentPage - 1) * incidentPageSize, incidentPage * incidentPageSize);
    if ($("queue-visible")) $("queue-visible").textContent = `${visible.length} of ${incidents.length} shown${state.truncated?.incidents ? " · loaded incidents only" : ""}`;
    renderPagination($("incident-pagination"), incidentPage, incidents.length, incidentPageSize, (page) => { incidentPage = page; renderIncidents(); });
    if (!incidents.length) {
      const noResults = empty(term ? "No matching incidents" : selectedFilter === "verification" ? "No incidents awaiting verification" : selectedFilter === "resolved" ? "No resolved incidents yet" : selectedFilter === "active" && state.incidents.length ? "No active incidents" : "Ready for the first signal", term ? "Try a different incident ID, location, or phrase from a report." : selectedFilter === "verification" ? "All loaded active incidents have a recorded human assessment. You can still review any incident from the full queue." : "Reports appear after a gateway uploads an SOS. Device roles and routes come from actual report and heartbeat records.", "⌁");
      if (term) noResults.append(button("Clear search", "button-outline small", () => clearIncidentSearch()));
      else if (selectedFilter === "verification") noResults.append(button("View all incidents", "button-outline small", () => { selectedFilter = "all"; incidentPage = 1; syncFilterButtons(); renderIncidents(); }));
      replace($("incidents-list"), [noResults]);
      return;
    }
    replace($("incidents-list"), visible.map((incident) => {
      const card = node("article", `incident-card level-${urgency(incident)}`);
      card.dataset.incidentId = incident.id;
      const reports = reportsFor(incident);
      const count = array(incident.report_ids).length;
      const completeReports = reports.length === count;
      const origins = new Set(reports.map((report) => report.origin_id).filter(Boolean)).size;
      const priority = node("div", "queue-priority");
      priority.append(node("strong", "", urgencyLabels[urgency(incident)] || "Unassessed"), node("small", "", mediaFindings(incident).length ? "AI media suggestion" : incident.triage ? "AI suggestion" : "Awaiting triage"));
      const identity = node("div", "queue-identity");
      const types = [...new Set(reports.map((report) => report.emergency_type).filter(Boolean))];
      identity.append(node("h3", "incident-title", types.length ? types.map((type) => sourceTypeLabels[type] || humanField(type)).join(" / ") : incident.title || "Emergency report"), node("p", "queue-location", location(incident)));
      const meta = node("div", "queue-meta");
      meta.append(node("span", "incident-id", `INC ${shortId(incident.id)}`), incidentMode(incident), node("small", "", types.length ? `Source-provided type${completeReports ? "" : " · loaded reports"}` : "Type not supplied"));
      identity.append(meta);
      if (reports.length) {
        const report = reports[0];
        const path = array(report.relay_path);
        const route = node("p", "queue-route", `SOS ${shortId(report.id)}${count > 1 ? ` · 1 of ${count} paths` : ""}: ${path.length ? `${path.join(" → ")} → BACKEND` : "No path supplied"}`);
        route.title = "Sender-reported original upload path; not a receipt return route";
        identity.append(route);
      }
      const evidence = node("div", "queue-evidence");
      evidence.append(verificationTag(incident), node("p", "queue-counts", `${count} report${count === 1 ? "" : "s"} · ${origins}${completeReports ? "" : "+"} claimed origin${origins === 1 && completeReports ? "" : "s"}`), node("small", "", "Independence unknown"));
      const status = node("div", "queue-status");
      status.append(node("strong", "", statusLabels[incident.status] || humanField(incident.status)), node("small", "", incident.team ? `Team: ${incident.team}` : "Team unassigned"));
      if (reports.length && completeReports && reports.every((report) => Array.isArray(report.receipts))) {
        const issued = reports.filter((report) => report.receipts.some((receipt) => receipt.type === "backend_received")).length;
        status.append(node("small", "queue-receipts", `${issued}/${count} backend receipts issued`));
      }
      const updated = node("div", "queue-updated");
      updated.append(timeNode(incident.created_at, true), node("small", "", "Received"));
      updated.title = `Last updated ${date(incident.updated_at || incident.created_at, true)}`;
      const actions = node("div", "queue-action");
      const open = button("Open →", "button-outline", () => openIncident(incident.id, currentView === "verification" ? "verification" : "overview"));
      open.dataset.action = "view-incident";
      open.setAttribute("aria-label", `Open incident ${shortId(incident.id)}`);
      actions.append(open);
      card.append(priority, identity, evidence, status, updated, actions);
      return card;
    }));
  }

  function reportCard(report, {compact = false, includeActions = false, summaryOnly = false} = {}) {
    const card = node("article", `report-card${summaryOnly ? " report-card-compact" : ""}`);
    card.dataset.reportId = report.id;
    const top = node("div", "report-top");
    top.append(node("span", "report-id", `SOS / ${shortId(report.id)}`), simulationTag(report), timeNode(report.received_at || report.created_at, true));
    const originalText = report.text || "No additional description supplied.";
    const excerpt = compact && originalText.length > 280;
    card.append(top);
    if (!compact && !summaryOnly && array(report.media).length) card.append(mediaEvidence(report));
    card.append(node("div", "raw-label", `${report.message_source === "preset" ? "APP DEFAULT TEXT / NO TYPED MESSAGE" : excerpt ? "ORIGINAL TEXT EXCERPT" : "ORIGINAL SOURCE TEXT"} / NOT INDEPENDENTLY VERIFIED`), node("p", "report-text", excerpt ? `${originalText.slice(0, 280)}…` : originalText));
    if (excerpt) {
      const original = node("details", "correlation-original-text");
      original.append(node("summary", "", "Read full original report"), node("p", "report-text", originalText));
      card.append(original);
    }
    const supplied = sourceFields(report);
    if (supplied && !summaryOnly) {
      if (compact) {
        const details = node("details", "correlation-source-fields");
        details.append(node("summary", "", "Source-provided details"), supplied);
        card.append(details);
      } else card.append(supplied);
    }
    if (summaryOnly) card.append(node("p", "report-location", [sourceTypeLabels[report.emergency_type], report.building, report.location_text, report.floor ? `Floor ${report.floor}` : "", report.room ? `Room ${report.room}` : "", report.zone].filter(Boolean).join(" / ") || "No structured location or type supplied"));
    if (!compact) card.append(relayPath(report));
    if (!compact) card.append(receiptSummary(report));
    const bottom = node("div", "report-bottom");
    bottom.append(node("span", "", `Source ${stringValue(report.origin_id)} · ${Number.isFinite(report.hop_count) ? `${report.hop_count} hop(s)` : "Hop count unavailable"}`), node("span", "", `Processing: ${humanField(report.ai_status || "pending")}`));
    card.append(bottom);
    if (summaryOnly && report.incident_id) card.append(button("Open report & delivery →", "button-outline small", () => openIncident(report.incident_id, "reports")));
    if (includeActions && ["failed", "unavailable"].includes(report.ai_status)) {
      if (report.ai_error) card.append(node("p", "form-message", report.ai_error));
      const retry = button("Retry AI analysis", "button-outline small", async () => {
        retry.disabled = true;
        try {
          await api(`/api/reports/${encodeURIComponent(report.id)}/reanalyze`, {method: "POST", body: "{}"});
          toast("Analysis queued. Original report retained.");
          await refresh({force: true});
        } catch (error) { toast(error.message); }
        finally { retry.disabled = false; }
      });
      const actions = node("div", "form-actions");
      actions.append(retry);
      card.append(actions);
    }
    return card;
  }

  function renderReports() {
    const term = $("report-search")?.value.trim().toLocaleLowerCase() || "";
    if ($("clear-report-search")) $("clear-report-search").hidden = !term;
    const reports = state.reports.filter((report) => !term || [report.id, report.origin_id, report.text, report.building, report.location_text, report.floor, report.room, report.zone, report.emergency_type, ...array(report.quick_needs).map((need) => quickNeedLabels[need] || need)].filter(Boolean).join(" ").toLocaleLowerCase().includes(term)).sort((a, b) => Number(b.received_at) - Number(a.received_at) || String(a.id).localeCompare(String(b.id)));
    if (reportResizeAnchor) {
      const anchorIndex = reports.findIndex((report) => report.id === reportResizeAnchor);
      if (anchorIndex >= 0) reportPage = Math.floor(anchorIndex / reportPageSize) + 1;
      reportResizeAnchor = null;
    }
    reportPage = Math.max(1, Math.min(reportPage, Math.ceil(reports.length / reportPageSize) || 1));
    renderPagination($("report-pagination"), reportPage, reports.length, reportPageSize, (page) => { reportPage = page; renderReports(); });
    if (!reports.length) {
      const noResults = empty(term ? "No matching reports" : "No messages received yet", term ? "Try a report ID, source ID, location, or phrase from the original message." : "Reports uploaded from Android appear here. Simulated reports remain explicitly labeled.");
      if (term) noResults.append(button("Clear search", "button-outline small", () => clearReportSearch()));
      replace($("reports-list"), [noResults]);
      return;
    }
    replace($("reports-list"), reports.slice((reportPage - 1) * reportPageSize, reportPage * reportPageSize).map((report) => reportListRow(report)));
  }

  function clearIncidentSearch() {
    $("incident-search").value = "";
    incidentPage = 1;
    renderIncidents();
    $("incident-search").focus();
  }

  function clearReportSearch() {
    if ($("report-search")) $("report-search").value = "";
    reportPage = 1;
    renderReports();
    $("report-search")?.focus();
  }

  function reportListRow(report) {
    const row = node("article", "report-list-row");
    row.dataset.reportId = report.id;
    const identity = node("div", "report-list-identity");
    identity.append(node("strong", "report-id", `SOS ${shortId(report.id)}`), simulationTag(report), timeNode(report.received_at || report.created_at, true));
    const source = node("div", "report-list-source");
    const sourceLocation = [report.building, report.location_text, report.floor ? `Floor ${report.floor}` : "", report.room ? `Room ${report.room}` : "", report.zone].filter(Boolean).join(" / ") || (Number.isFinite(report.location_context?.latitude) && Number.isFinite(report.location_context?.longitude) ? coordinateLabel(report.location_context, report.simulation) : "Location not supplied");
    const sourceLocationLabel = node("small", "", sourceLocation);
    sourceLocationLabel.title = sourceLocation;
    source.append(node("strong", "", stringValue(report.origin_id)), sourceLocationLabel);
    const preview = node("div", "report-list-preview");
    const sourceCategory = report.emergency_type === "other" ? "Other / unspecified emergency" : report.emergency_type ? sourceTypeLabels[report.emergency_type] || humanField(report.emergency_type) : "Emergency type not supplied";
    const selectedDetails = [array(report.quick_needs).map((need) => quickNeedLabels[need] || humanField(need)).join(" · "), Number.isInteger(report.people_affected) ? `${report.people_affected} ${report.people_affected === 1 ? "person" : "people"} affected` : ""].filter(Boolean);
    const previewText = [...selectedDetails, report.message_source === "preset" ? "No typed message" : report.text || "No description supplied"].join(" — ");
    const mediaReviews = mediaEntries(report);
    const reviewPreview = mediaReviews.map(({item, analysis}) => `${mediaLabels[item.kind] || "Attachment"} · ${mediaStateLabel(analysis)}: ${mediaAnalysisSummary(analysis)}`).join(" · ");
    const previewBody = node("p", mediaReviews.length ? "report-media-preview" : "", mediaReviews.length ? `AI media: ${reviewPreview}` : previewText);
    previewBody.title = mediaReviews.length ? `${reviewPreview}\nSource: ${previewText}` : previewText;
    const category = node("small", "", sourceCategory);
    const mediaBrief = mediaSummary(report);
    if (mediaBrief) {
      category.append(node("span", "media-brief", ` · ${mediaBrief}`));
      category.title = `${sourceCategory} · ${mediaBrief}`;
    }
    preview.append(category, previewBody);
    const delivery = node("div", "report-list-delivery");
    const receipts = array(report.receipts);
    delivery.append(node("strong", "", receipts.some((receipt) => receipt.type === "responder_acknowledged") ? "Responder ACK recorded" : receipts.some((receipt) => receipt.type === "backend_received") ? "Backend receipt issued" : "No receipt record"), node("small", "", "Return delivery unknown"));
    const actions = node("div", "report-list-action");
    const incident = state.incidents.find((item) => item.id === report.incident_id) || state.incidents.find((item) => array(item.report_ids).includes(report.id));
    const open = button(mediaReviews.length ? "Review media →" : "Open report →", "button-outline small", () => { if (incident) { openIncident(incident.id, "reports", report.id); if (mediaReviews.length) focusMediaReview(report.id, mediaReviews[0].item.id); } });
    open.setAttribute("aria-label", `Open report ${shortId(report.id)}`);
    open.disabled = !incident;
    actions.append(open);
    if (!incident) actions.append(node("small", "", "Incident outside loaded window"));
    row.append(identity, source, preview, delivery, actions);
    return row;
  }

  function mediaSummary(report) {
    return array(report.media).map((item) => `${mediaLabels[item.kind] || "Attachment"} ${item.status === "available" ? "available" : "pending"}`).join(" · ");
  }

  function mediaEntries(report) {
    return array(report.media).map((item) => ({item, analysis: array(report.media_analysis).find((entry) => entry.attachment_id === item.id) || {status: item.status === "available" ? "unknown" : "waiting_upload"}}));
  }

  function mediaStateLabel(analysis) {
    if (analysis.status === "complete" && !analysis.result) return "Result unavailable";
    return {waiting_upload: "Waiting for upload", queued: "Queued", running: "Processing", complete: "Complete", failed: "Failed", unavailable: "Unavailable", unknown: "Status not available"}[analysis.status] || humanField(analysis.status);
  }

  function mediaAnalysisSummary(analysis) {
    if (analysis.status === "complete") return analysis.result?.summary?.trim() || "No analysis summary was returned. Review the original media.";
    if (analysis.error) return analysis.error;
    return {waiting_upload: "Analysis starts after the attachment reaches the backend.", queued: "Waiting for the local model; no new result yet.", running: "The local model is analyzing this attachment.", failed: "No completed result from this attempt. Review the original or retry.", unavailable: "The local model is unavailable. Review the original or retry."}[analysis.status] || "No analysis state was supplied by the backend. Review the original media.";
  }

  function mediaTranscriptNote(item, result) {
    if (!["audio", "video"].includes(item.kind) || result.transcript?.trim()) return null;
    if (result.coverage?.audio_included === false) return "Audio was not included in this analysis. Review the original recording.";
    return "No intelligible speech was identified by the model. Review the original recording; this does not establish that it contains no speech.";
  }

  function mediaReviewSignature(reports) {
    return JSON.stringify(reports.map((report) => [report.id, report.media, report.media_analysis]));
  }

  function compactMediaReview(report, item, analysis, onReview) {
    const card = node("article", `media-overview-card media-state-${analysis.status}`);
    card.dataset.reportId = report.id;
    card.dataset.attachmentId = item.id;
    const top = node("div", "media-overview-top");
    top.append(node("strong", "", `${mediaLabels[item.kind] || "Attachment"} · SOS ${shortId(report.id)}`), tag(mediaStateLabel(analysis), ["failed", "unavailable"].includes(analysis.status) || (analysis.status === "complete" && !analysis.result) ? "warning" : "neutral"));
    card.append(top, node("p", "media-result-summary", mediaAnalysisSummary(analysis)));
    if (analysis.status === "complete" && analysis.result) {
      if (analysis.result.transcript?.trim()) card.append(node("p", "media-overview-transcript", `AI transcript: ${analysis.result.transcript}`));
      const transcriptNote = mediaTranscriptNote(item, analysis.result);
      if (transcriptNote) card.append(node("p", "media-transcript-empty", transcriptNote));
      const uncertainty = array(analysis.result.uncertainties)[0];
      card.append(node("p", "media-overview-uncertainty", uncertainty ? `Uncertainty: ${uncertainty}` : "Uncertainty details were not returned. The result is not independently verified."));
      card.append(node("small", "detail-muted", `${analysis.result.model || "Model not recorded"} · Urgency: ${humanField(analysis.result.suggested_urgency)} (AI suggestion)`));
    }
    const review = button(analysis.status === "complete" ? "Review AI result & original →" : "Review media & processing →", "button-outline small", onReview);
    review.dataset.action = "review-media-result";
    card.append(review);
    return card;
  }

  function focusMediaReview(reportId, attachmentId) {
    selectCaseTab("reports", true);
    const panel = $("case-panel-reports");
    const review = [...(panel?.querySelectorAll(".media-analysis") || [])].find((item) => item.dataset.reportId === reportId && item.dataset.attachmentId === attachmentId);
    if (review) { review.open = true; review.tabIndex = -1; review.scrollIntoView({block: "start", inline: "nearest", behavior: "auto"}); review.focus({preventScroll: true}); }
  }

  function incidentMediaOverview(reports) {
    const block = section("Media analysis · human review required");
    block.id = "incident-media-overview";
    block.dataset.signature = mediaReviewSignature(reports);
    const entries = reports.flatMap((report) => mediaEntries(report).map((entry) => ({report, ...entry})));
    block.hidden = !entries.length;
    if (!entries.length) return block;
    block.append(node("p", "detail-muted", "All received attachments appear here, including ordinary or unclear content. AI interpretation is not a verification of the event."));
    for (const {report, item, analysis} of entries) {
      block.append(compactMediaReview(report, item, analysis, () => focusMediaReview(report.id, item.id)));
    }
    return block;
  }

  function mediaEvidence(report) {
    const sectionEl = node("section", "media-evidence");
    sectionEl.dataset.reportId = report.id;
    sectionEl.dataset.signature = mediaReviewSignature([report]);
    sectionEl.append(node("h4", "", "Voice, photos & video"), node("p", "detail-muted", "Review the original alongside AI interpretation. Neither establishes whether an emergency is genuine."));
    mediaEntries(report).forEach(({item, analysis}) => {
      const row = node("div", "media-evidence-row");
      row.dataset.reportId = report.id;
      row.dataset.attachmentId = item.id;
      const copy = node("div", "media-evidence-copy");
      const length = Number.isFinite(item.duration_ms) ? ` · ${(item.duration_ms / 1000).toFixed(1)} sec` : "";
      copy.append(node("strong", "", `${mediaLabels[item.kind] || "Attachment"}${length}`), node("small", "", item.status === "available" ? `Available for review · ${Math.ceil(Number(item.byte_size) / 1024)} KB` : "SOS received; attachment pending"));
      row.append(copy);
      if (item.status === "available") {
        const action = {audio: "Listen to voice", image: "View photo", video: "Watch video"}[item.kind];
        if (action) row.append(button(action, "button-outline small", () => openMedia(report, item)));
      } else row.append(tag("PENDING", "warning"));
      sectionEl.append(row);
      sectionEl.append(mediaAnalysis(report, item, analysis));
    });
    return sectionEl;
  }

  function mediaAnalysis(report, item, analysis) {
    const box = node("details", "media-analysis");
    box.open = true;
    box.dataset.reportId = report.id;
    box.dataset.attachmentId = item.id;
    box.dataset.signature = JSON.stringify([item, analysis]);
    const result = analysis.result;
    box.append(node("summary", "", `AI media review · ${mediaStateLabel(analysis)}`));
    if (result) {
      if (analysis.status !== "complete") box.append(node("p", "human-notice", `Previous analysis shown below. The current attempt is ${humanField(analysis.status)}${analysis.error ? `: ${analysis.error}` : "."}`));
      box.append(node("p", "media-result-summary", result.summary?.trim() || "No analysis summary was returned. Review the original media."), node("p", "detail-muted", `${result.model || "Model not recorded"} · ${date(result.generated_at, true)} · Human review required`));
      if (result.transcript?.trim()) box.append(node("h5", "", `Speech transcription · ${result.language || "language unknown"}`), node("blockquote", "media-transcript", result.transcript));
      const transcriptNote = mediaTranscriptNote(item, result);
      if (transcriptNote) box.append(node("h5", "", "Speech transcription"), node("p", "media-transcript-empty", transcriptNote));
      box.append(node("h5", "", "Uncertainty & limitations"), evidenceList(result.uncertainties, "No uncertainty details were returned. The AI result is not independently verified."));
      box.append(node("p", "", `Suggested urgency: ${humanField(result.suggested_urgency)}. ${result.urgency_reason || "No reasoning was returned."}`));
      const observations = node("details", "media-observations");
      observations.append(node("summary", "", "Observations & suggested human checks"));
      for (const [label, values] of [["Visible observations", result.visual_observations], ["Audible observations", result.audible_observations], ["Human checks", result.requested_human_checks]]) {
        if (array(values).length) observations.append(node("h5", "", label), evidenceList(values, ""));
      }
      if (observations.children.length > 1) box.append(observations);
      const provenance = node("details", "media-provenance");
      provenance.append(node("summary", "", "Analysis coverage & integrity"), node("pre", "", JSON.stringify(result.coverage, null, 2)), node("small", "", `Source SHA-256: ${result.source_sha256}`));
      box.append(provenance);
    } else {
      box.append(node("p", "media-result-summary", mediaAnalysisSummary(analysis)));
      box.append(node("p", "detail-muted", `Configured model: ${state.ai.media?.model || state.ai.model || "Not reported"}. Original evidence remains available independently of AI.`));
    }
    if (item.status === "available" && (["failed", "unavailable", "unknown"].includes(analysis.status) || (analysis.status === "complete" && !result)) && account.role !== "viewer") {
      const message = node("p", "media-action-message");
      message.setAttribute("role", "status");
      const retry = button("Retry media analysis", "button-outline small", async () => {
        const session = authEpoch;
        retry.disabled = true;
        message.textContent = "Requesting another analysis…";
        try {
          await api(`/api/reports/${encodeURIComponent(report.id)}/attachments/${encodeURIComponent(item.id)}/analyze`, {method: "POST"});
          if (session !== authEpoch) return;
          message.textContent = "Analysis queued. Waiting for the local model.";
          await refresh({force: true});
        } catch (error) { if (session === authEpoch) { message.textContent = error.message; retry.disabled = false; } }
      });
      box.append(retry, message);
    }
    return box;
  }

  function closeMedia() { [...mediaDialogs].forEach((dialog) => dialog.close()); }

  function viewerMediaReview(report, item, dialog) {
    const review = node("section", "media-viewer-review");
    review.dataset.reportId = report.id;
    review.dataset.attachmentId = item.id;
    review.dataset.signature = mediaReviewSignature([report]);
    const analysis = mediaEntries(report).find((entry) => entry.item.id === item.id)?.analysis || {status: item.status === "available" ? "unknown" : "waiting_upload"};
    review.append(node("h3", "", "AI interpretation · unverified"), compactMediaReview(report, item, analysis, () => {
      dialog.close();
      const incident = state.incidents.find((entry) => entry.id === report.incident_id || array(entry.report_ids).includes(report.id));
      if (incident && selectedIncidentId !== incident.id) openIncident(incident.id, "reports", report.id);
      focusMediaReview(report.id, item.id);
    }));
    return review;
  }

  async function openMedia(report, item) {
    closeMedia();
    const dialog = node("dialog", "media-dialog");
    mediaDialogs.add(dialog);
    const title = node("h2", "", `${mediaLabels[item.kind]} · SOS ${shortId(report.id)}`);
    title.id = `media-title-${item.id}`;
    dialog.setAttribute("aria-labelledby", title.id);
    const top = node("div", "media-dialog-top");
    top.append(title, button("Close", "button-outline small", () => dialog.close()));
    const status = node("p", "detail-muted", "Loading attachment…");
    status.setAttribute("role", "status");
    const content = node("div", "media-player");
    dialog.dataset.reportId = report.id;
    dialog.dataset.attachmentId = item.id;
    const layout = node("div", "media-viewer-layout");
    layout.append(content, viewerMediaReview(report, item, dialog));
    dialog.append(top, node("p", "media-source-note", "Source-submitted media · unverified · AI interpretation is shown separately in this viewer"), status, layout);
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 30000);
    let url = null;
    let player = null;
    dialog.addEventListener("close", () => {
      controller.abort();
      window.clearTimeout(timeout);
      if (player && item.kind !== "image") player.pause();
      if (url) URL.revokeObjectURL(url);
      mediaDialogs.delete(dialog);
      dialog.remove();
    });
    document.body.append(dialog);
    dialog.showModal();
    try {
      const response = await fetch(`/api/reports/${encodeURIComponent(report.id)}/attachments/${encodeURIComponent(item.id)}`, {
        cache: "no-store", signal: controller.signal, headers: key ? {"X-API-Key": key} : {},
      });
      if (!response.ok) {
        const detail = await response.json().catch(() => ({}));
        throw new Error(typeof detail.detail === "string" ? detail.detail : `Attachment unavailable (${response.status}).`);
      }
      const blob = await response.blob();
      if (blob.size !== item.byte_size || blob.type.split(";", 1)[0] !== item.mime_type) throw new Error("The received attachment does not match its report. Try refreshing the case.");
      if (!dialog.open) return;
      url = URL.createObjectURL(blob);
      player = node(item.kind === "image" ? "img" : item.kind === "audio" ? "audio" : "video");
      if (item.kind === "image") player.alt = "Source-submitted emergency photo; content unverified";
      else {
        player.controls = true;
        player.preload = "metadata";
        player.setAttribute("aria-label", `${mediaLabels[item.kind]} from SOS ${shortId(report.id)}`);
        if (item.kind === "video") player.playsInline = true;
      }
      player.addEventListener("error", () => { status.textContent = "This browser could not display or play this recording. The received attachment is retained."; });
      player.src = url;
      content.append(player);
      status.textContent = item.kind === "image" ? "Review the photo alongside the original report and its location." : "Press play to review. Any AI transcript appears separately in this viewer and may contain errors; captions are not embedded.";
    } catch (error) {
      if (dialog.open) status.textContent = error.name === "AbortError" ? "Attachment loading timed out. Close this viewer and try again." : error.message;
    } finally { window.clearTimeout(timeout); }
  }

  function section(title) {
    const el = node("section", "detail-section");
    el.append(node("h3", "", title));
    return el;
  }
  function evidenceList(items, emptyText) {
    if (!array(items).length) return node("p", "detail-muted", emptyText);
    const list = node("ul", "evidence-list");
    items.forEach((item) => list.append(node("li", "", stringValue(item))));
    return list;
  }
  function field(labelText, id, {value = "", options, placeholder, hint} = {}) {
    const group = node("div", "form-field");
    const label = node("label", "", labelText);
    label.htmlFor = id;
    const input = node(options ? "select" : "input");
    input.id = id;
    input.name = id;
    input.autocomplete = "off";
    if (options) options.forEach(([val, label]) => { const option = node("option", "", label); option.value = val; input.append(option); });
    else { input.type = "text"; input.maxLength = 80; }
    if (placeholder) input.placeholder = placeholder;
    input.value = value ?? "";
    input.addEventListener("input", () => markCaseFieldDirty(input));
    input.addEventListener("change", () => markCaseFieldDirty(input));
    group.append(label, input);
    if (hint) group.append(node("span", "form-hint", hint));
    return group;
  }

  function markCaseFieldDirty(input) {
    if (input.id) dirtyCaseFields.add(input.id);
    formDirty = dirtyCaseFields.size > 0;
    rememberCaseDraft();
    updateDraftStatus();
  }

  function rememberCaseDraft() {
    if (!selectedIncidentId) return;
    const drafts = captureCaseDrafts();
    if (drafts.length) caseDrafts.set(selectedIncidentId, drafts);
    else caseDrafts.delete(selectedIncidentId);
  }

  function updateDraftStatus() {
    const notice = $("case-draft-notice");
    if (!notice) return;
    notice.hidden = !formDirty;
    const status = notice.querySelector(".draft-status");
    if (status) status.textContent = formDirty ? "Unsaved changes · kept while this page stays open" : "";
  }

  function discardCaseDraft() {
    if (!selectedIncidentId) return;
    caseDrafts.delete(selectedIncidentId);
    dirtyCaseFields.clear();
    formDirty = false;
    renderIncidentDetail();
    selectCaseTab(selectedCaseTab, true, false);
    toast("Unsaved changes discarded. The recorded incident is unchanged.");
  }

  function closeIncidentWorkspace() {
    closeMedia();
    rememberCaseDraft();
    selectedIncidentId = null;
    formDirty = false;
    dirtyCaseFields.clear();
  }

  function captureCaseDrafts(excludedForm = null) {
    return [...dirtyCaseFields].flatMap((id) => {
      const input = $(id);
      if (!input || !$("detail-content").contains(input) || excludedForm?.contains(input)) return [];
      return [{id, value: input.value, checked: input.checked}];
    });
  }

  function restoreCaseDrafts(drafts) {
    dirtyCaseFields.clear();
    drafts.forEach((draft) => {
      const input = $(draft.id);
      if (!input || !$("detail-content").contains(input)) return;
      input.value = draft.value;
      if (typeof draft.checked === "boolean") input.checked = draft.checked;
      dirtyCaseFields.add(draft.id);
    });
    formDirty = dirtyCaseFields.size > 0;
    syncVerificationRequirements();
    rememberCaseDraft();
    updateDraftStatus();
  }

  function syncVerificationRequirements() {
    const decisive = $("verification-action")?.value !== "request_verification";
    ["verification-notes", "verification-reviewer", "verification-method", "verification-reference", "verification-checked"].forEach((id) => { if ($(id)) $(id).required = decisive; });
  }

  function incidentVerification(incident) {
    const verification = section("Verification / human assessment");
    verification.dataset.section = "verification";
    const layout = node("div", `verification-layout${incident.merged_into ? " is-readonly" : ""}`);
    const evidence = node("div", "verification-evidence");
    layout.append(evidence);
    verification.append(layout);
    const current = node("div", "verification-current");
    current.append(verificationTag(incident), node("p", "detail-muted", incident.verification_updated_at ? `Human decision recorded ${date(incident.verification_updated_at, true)}` : "No human verification decision recorded."));
    if (incident.verification_notes) current.append(node("p", "verification-note", incident.verification_notes));
    evidence.append(current, node("p", "detail-muted", "Repeated messages, multiple source IDs, proximity, and similar wording are signals to investigate. Source independence is unknown; these do not establish whether a claim is true or false."));
    const human = incident.human_verification;
    if (human) {
      const recorded = node("div", "human-check-record");
      recorded.append(node("h4", "detail-subheading", "Recorded human check"));
      const fields = node("dl", "facts-grid");
      [["Reviewer label", human.reviewer_label || "Not recorded"], ["Check method", checkMethods[human.check_method] || "Method not recorded"], ["Evidence reference", human.evidence_reference || "Not recorded"], ["Observation time", human.checked_at ? date(human.checked_at, true) : "Not recorded"], ["Decision recorded", date(human.recorded_at, true)]].forEach(([label, value]) => fields.append(node("dt", "", label), node("dd", "", value)));
      if (human.authenticated_account) fields.append(node("dt", "", "Authenticated account"), node("dd", "", human.authenticated_account));
      recorded.append(fields, node("p", "detail-muted", human.authenticated_account ? "The account was authenticated. The reviewer label and referenced evidence remain self-reported and require independent verification." : "Reviewer identity is self-reported, not authenticated. Referenced evidence is recorded, not independently validated by ResQMesh."));
      evidence.append(recorded);
    }
    const steps = node("ol", "verification-workflow");
    [["Compare the source reports", "Read exact wording, declared locations and timestamps. Separate original reports from transport copies."], ["Seek an independent check", "Use an on-site responder, a known callback contact, or an evidence reference. Anonymous source IDs alone cannot establish independence."], ["Record a human decision", "Explain what was checked, by whom, when, and what remains uncertain. AI never makes the final true/false decision."]].forEach(([title, text]) => {
      const step = node("li", "verification-step");
      step.append(node("strong", "", title), node("p", "", text));
      steps.append(step);
    });
    const workflow = node("details", "verification-workflow-help");
    workflow.append(node("summary", "", "How to verify a report"), steps);
    evidence.append(workflow);
    const comparison = node("div", "verification-comparison");
    comparison.append(node("h4", "detail-subheading", "Source comparison"));
    const members = reportsFor(incident);
    const relatedIds = array(array(incident.verification_signals).find((signal) => signal.code === "source_claims")?.observed?.related_context_report_ids);
    const related = state.reports.filter((report) => relatedIds.includes(report.id) && !array(incident.report_ids).includes(report.id));
    [...members.map((report) => [report, false]), ...related.map((report) => [report, true])].forEach(([report, candidate]) => {
      const row = node("article", "comparison-source");
      const meta = node("div", "incident-meta");
      meta.append(node("strong", "", `SOS ${shortId(report.id)}`), tag(candidate ? "RELATED CONTEXT · NOT MERGED" : "INCIDENT MEMBER"), simulationTag(report));
      row.append(meta, node("p", "comparison-origin", `Claimed origin ${stringValue(report.origin_id)} · received ${date(report.received_at, true)}`), node("p", "", [report.building, report.location_text, report.floor ? `Floor ${report.floor}` : "", report.room ? `Room ${report.room}` : "", report.zone].filter(Boolean).join(" / ") || "No structured location supplied"), node("blockquote", "comparison-quote", report.text));
      comparison.append(row);
    });
    if (!members.length) comparison.append(node("p", "detail-muted", "Original member reports are outside the loaded API window."));
    if (related.length < relatedIds.length) comparison.append(node("p", "detail-muted", "Some related context reports are outside the loaded API window."));
    comparison.append(node("p", "detail-muted", "Related context supports pattern review only. It is not merged into this incident and does not add independent witnesses."));
    evidence.append(comparison);

    const signals = array(incident.verification_signals);
    const signalDetails = node("details", "verification-signals");
    signalDetails.append(node("summary", "", `Inspect computed report signals (${signals.length})`));
    if (!signals.length) signalDetails.append(node("p", "detail-muted", "No computed verification signals are available."));
    signals.forEach((signal) => {
      const block = node("div", "verification-signal");
      block.append(tag(humanField(signal.code || signal.id), signal.severity === "review" ? "warning" : "neutral"), node("p", "", signal.description || "No signal description supplied."));
      if (array(signal.report_ids).length) block.append(node("small", "detail-muted", `Reports: ${signal.report_ids.map(shortId).join(", ")}`));
      if (signal.observed && Object.keys(signal.observed).length) {
        const raw = node("details", "signal-basis");
        raw.append(node("summary", "", "Inspect observed input values"), node("pre", "", JSON.stringify(signal.observed, null, 2)));
        block.append(raw);
      }
      signalDetails.append(block);
    });
    evidence.append(signalDetails);

    const model = incident.verification;
    const support = node("div", "suggestion-box");
    support.append(node("h3", "", "AI VERIFICATION SUPPORT / NOT A VERDICT"));
    if (model) {
      support.append(node("p", "", model.summary || "No model summary supplied."), node("p", "", `Suggested evidence state: ${humanField(model.suggested_state)}. Human verification remains ${humanField(incident.verification_status || "unverified")}.`));
      array(model.signal_assessments).forEach((assessment) => {
        const block = node("div", "verification-assessment");
        block.append(node("strong", "", humanField(assessment.signal_id)), node("p", "", assessment.assessment), node("p", "detail-muted", `Why this is inconclusive: ${assessment.why_not_conclusive}`));
        support.append(block);
      });
      support.append(node("h4", "detail-subheading", "Limitations"), evidenceList(model.limitations, "No model limitations listed. This does not establish source reliability."));
      if (array(model.questions).length) support.append(node("h4", "detail-subheading", "Suggested verification questions"), evidenceList(model.questions, ""));
      if (array(model.evidence).length) support.append(node("h4", "detail-subheading", "Model-cited source quotes"), evidenceList(model.evidence.map((item) => `${shortId(item.report_id)}: “${item.quote}”`), ""));
      support.append(node("p", "detail-muted", `Source independence: unknown · ${model.model || state.ai.model || "local model"} · ${date(model.generated_at, true)}`));
    } else {
      const stage = incident.processing_stages?.verification;
      support.append(node("p", "", `Verification analysis is ${humanField(stage?.status || "unavailable")}. ${stage?.error || "The source reports remain available for direct human assessment."}`));
    }
    evidence.append(support);
    if (!incident.merged_into) {
      const controls = node("aside", "verification-controls");
      controls.setAttribute("aria-label", "Record a human verification check");
      const notice = node("div", "human-notice");
      notice.append(node("strong", "", "HUMAN CONFIRMATION REQUIRED"), node("span", "", "Record the action and your supporting evidence. Requesting verification records a request here; it does not prove the reporting device was contacted. Closing as false is a human decision and also resolves the incident."));
      const form = node("form");
      form.id = "verification-form";
      const grid = node("div", "form-grid");
      const actionField = field("Verification action", "verification-action", {value: "request_verification", options: [["request_verification", "Request verification"], ["corroborated", "Mark corroborated"], ["responder_verified", "Mark responder verified"], ["false_closed", "Close as false / human decision"]]});
      actionField.classList.add("full");
      const noteGroup = node("div", "form-field full");
      const label = node("label", "", "Verification notes / evidence");
      label.htmlFor = "verification-notes";
      const notes = node("textarea");
      notes.id = "verification-notes";
      notes.name = "verification_notes";
      notes.autocomplete = "off";
      notes.maxLength = 2000;
      notes.placeholder = "What did the check establish? Describe observations, contradictions, limitations, and what still needs confirmation.";
      notes.addEventListener("input", () => markCaseFieldDirty(notes));
      const hint = node("small", "form-hint", "A decisive check requires reviewer, method, evidence reference, observation time, and notes. These are human-entered records, not authenticated proof.");
      hint.id = "verification-notes-hint";
      notes.setAttribute("aria-describedby", hint.id);
      noteGroup.append(label, notes, hint);
      const reviewerField = field("Reviewer label / self-reported", "verification-reviewer", {placeholder: "Name or responder label"});
      const reviewer = reviewerField.querySelector("input");
      reviewer.maxLength = 120;
      const methodField = field("How was it checked?", "verification-method", {value: "", options: [["", "Select a check method"], ...Object.entries(checkMethods)]});
      const method = methodField.querySelector("select");
      const referenceField = field("Evidence reference", "verification-reference", {placeholder: "Observation log, callback reference, witness note…", hint: "Stored as your reference; no evidence is fetched or authenticated."});
      referenceField.classList.add("full");
      const reference = referenceField.querySelector("input");
      reference.maxLength = 1000;
      const checkedField = field("When was the observation made?", "verification-checked", {hint: "Your local time; separate from when this decision is recorded."});
      checkedField.classList.add("full");
      const checked = checkedField.querySelector("input");
      checked.type = "datetime-local";
      grid.append(actionField, reviewerField, methodField, referenceField, checkedField, noteGroup);
      const message = node("p", "form-message");
      message.id = "verification-action-error";
      message.setAttribute("role", "alert");
      const actions = node("div", "form-actions");
      const save = button("Record verification decision", "button-primary");
      save.type = "submit";
      save.dataset.action = "verify-incident";
      actions.append(save);
      const actionInput = actionField.querySelector("select");
      actionInput.addEventListener("change", syncVerificationRequirements);
      form.append(grid, actions, message);
      form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const action = actionInput.value;
        const text = notes.value.trim();
        if (action !== "request_verification" && !text) { message.textContent = "Add supporting notes before recording this human decision."; notes.focus(); return; }
        const checkedAt = checked.value ? new Date(checked.value).getTime() : null;
        if (checked.value && (!Number.isFinite(checkedAt) || checkedAt <= 0 || checkedAt > Date.now() + 300_000)) { message.textContent = "Enter a valid observation time, no more than five minutes in the future."; checked.focus(); return; }
        if (action !== "request_verification" && (!reviewer.value.trim() || !method.value || !reference.value.trim() || !checkedAt)) { message.textContent = "Record the reviewer, check method, evidence reference, and observation time for this decision."; return; }
        await mutateIncident(`/api/incidents/${encodeURIComponent(incident.id)}/verification`, "POST", {action, notes: text || null, reviewer_label: reviewer.value.trim() || null, check_method: method.value || null, evidence_reference: reference.value.trim() || null, checked_at: checkedAt}, "Human verification decision recorded. Source delivery remains unconfirmed; reviewer identity and evidence are not independently validated.", form, message);
      });
      controls.append(notice, form);
      layout.append(controls);
    }
    return verification;
  }

  function openIncident(id, tab = "overview", reportId = null) {
    rememberCaseDraft();
    selectedIncidentId = id;
    selectedCaseTab = ["overview", "verification", "reports", "activity"].includes(tab) ? tab : "overview";
    formDirty = false;
    dirtyCaseFields.clear();
    renderIncidentDetail();
    restoreCaseDrafts(caseDrafts.get(id) || []);
    if (!$("incident-dialog").open) $("incident-dialog").showModal();
    $("incident-dialog").scrollTop = 0;
    $("detail-content").scrollTop = 0;
    if (reportId) {
      const selectedReport = [...$("case-panel-reports").querySelectorAll(".report-card")].find((report) => report.dataset.reportId === reportId);
      if (selectedReport) {
        selectedReport.classList.add("report-card-selected");
        selectedReport.tabIndex = -1;
        selectedReport.scrollIntoView({block: "start", inline: "nearest", behavior: "auto"});
        selectedReport.focus({preventScroll: true});
      }
    }
  }

  function renderIncidentDetail() {
    const incident = state.incidents.find((item) => item.id === selectedIncidentId);
    const target = $("detail-content");
    if (!incident) { replace(target, [empty("Incident is not in the current state", "Refresh the board or select another incident.")]); return; }
    const reports = reportsFor(incident);
    detailSignature = JSON.stringify([incident, reports, state.audit]);
    $("detail-title").textContent = incident.title || `Incident ${shortId(incident.id)}`;
    const parts = [];
    const groups = {overview: [], verification: [], reports: [], activity: []};
    let overviewActions = null;
    const meta = node("div", "detail-meta");
    meta.append(node("span", "incident-id", `INC / ${shortId(incident.id)}`), tag(statusLabels[incident.status] || incident.status), incidentMode(incident), urgencyTag(incident), verificationTag(incident));
    parts.push(meta);
    const draftNotice = node("div", "case-draft-notice");
    draftNotice.id = "case-draft-notice";
    draftNotice.hidden = !formDirty;
    const draftStatus = node("span", "draft-status", formDirty ? "Unsaved changes · kept while this page stays open" : "");
    draftStatus.setAttribute("role", "status");
    draftNotice.append(draftStatus, button("Discard changes", "button-outline small discard-draft", discardCaseDraft));
    parts.push(draftNotice);
    if (incident.merged_into) {
      const notice = node("div", "human-notice", "This incident was merged after human confirmation. Open the retained incident to continue.");
      notice.append(button("Open retained incident", "button-outline small", () => openIncident(incident.merged_into)));
      parts.push(notice);
    }
    const details = node("dl", "facts-grid");
    [["Reported location", location(incident)], ["First received", date(incident.created_at, true)], ["Original reports", String(array(incident.report_ids).length)], ["Acknowledged", incident.acknowledged_at ? date(incident.acknowledged_at, true) : "Not yet acknowledged"], ["People affected", "Unique total unconfirmed"]].forEach(([label, value]) => details.append(node("dt", "", label), node("dd", "", value)));
    groups.overview.push(caseEvidenceSummary(incident, reports), details);
    groups.overview.push(incidentMediaOverview(reports));
    const pipeline = section("Four-stage processing");
    const stageList = processingStages(incident);
    stageList.id = "incident-pipeline";
    stageList.dataset.signature = JSON.stringify(incident.processing_stages || {});
    pipeline.append(stageList, node("p", "detail-muted", "Statuses reflect backend jobs. A completed verification analysis does not change the human verification state."));
    groups.activity.push(pipeline);

    const sourceSection = section("SOURCE PROVIDED / original declarations");
    sourceSection.append(node("p", "detail-muted", "These fields were supplied with each report. They remain visible when AI is unavailable and are not independently verified."));
    reports.forEach((report) => {
      const supplied = sourceFields(report);
      if (supplied) {
        const block = node("div", "source-declaration");
        block.append(node("h4", "detail-subheading", `SOS ${shortId(report.id)} · source ${report.origin_id}`), supplied);
        sourceSection.append(block);
      }
    });
    if (sourceSection.children.length === 2) sourceSection.append(node("p", "detail-muted", "No structured fields supplied; inspect Reports & delivery for the original text."));

    const suggestion = node("div", "suggestion-box");
    suggestion.append(node("h3", "", "✧ AI SUGGESTION / HUMAN REVIEW REQUIRED"));
    if (incident.triage) {
      const triage = incident.triage;
      suggestion.append(node("p", "", triage.summary || "No triage summary provided."));
      suggestion.append(node("p", "", `Suggested urgency: ${urgencyLabels[triage.suggested_urgency] || triage.suggested_urgency}. ${triage.urgency_reason || ""}`));
      suggestion.append(node("p", "", `Suggested response category: ${triage.response_category || "Not provided"}.`));
      suggestion.append(node("p", "detail-muted", `Generated by ${triage.model || state.ai.model || "local model"} · ${date(triage.generated_at, true)}`));
      if (array(triage.evidence).length) {
        const evidence = node("ul", "evidence-list");
        triage.evidence.forEach((item) => evidence.append(node("li", "", `${shortId(item.report_id)}: “${item.quote}”`)));
        suggestion.append(evidence);
      }
    } else suggestion.append(node("p", "", `Triage is ${humanField(incident.triage_status || "not available")}. ${incident.triage_error || "No AI recommendation is available; review the original evidence directly."}`));
    groups.overview.push(suggestion, sourceSection);

    const facts = section("AI EXTRACTED / report claims");
    facts.append(node("p", "detail-muted", "Reported claim, not independently verified. These are model-extracted meanings, separate from original source-provided fields and human verification decisions."));
    let factCount = 0;
    reports.forEach((report) => {
      array(report.intake?.facts).forEach((fact) => {
        factCount++;
        const item = node("div", "extracted-fact");
        item.append(node("strong", "", `${humanField(fact.field)}: ${stringValue(fact.value)}`), node("p", "", `“${fact.quote}”`), node("small", "detail-muted", `SOS ${shortId(fact.report_id || report.id)} · source: ${fact.source || "text"}`));
        facts.append(item);
      });
    });
    if (!factCount) facts.append(node("p", "detail-muted", "No extracted claims available. Original messages remain the source of evidence."));
    const counts = section("People counts · kept separate");
    counts.append(node("h4", "detail-subheading", "Source-provided counts"), evidenceList(reports.filter((report) => report.people_affected !== null && report.people_affected !== undefined).map((report) => `SOS ${shortId(report.id)}: ${report.people_affected} declared by source ${report.origin_id}`), "No structured people count was supplied."));
    counts.append(node("h4", "detail-subheading", "AI-extracted counts from text"), evidenceList(array(incident.reported_people_counts).filter((count) => count.source !== "user_provided").map((count) => `SOS ${shortId(count.report_id)}: ${count.value} extracted from “${count.quote}”`), "No explicitly extracted people counts available."), node("p", "detail-muted", incident.people_total_note || "Counts may overlap. A unique total requires human confirmation; counts are never summed automatically."));
    groups.overview.push(counts);

    const uncertainty = section("MISSING INFORMATION / uncertainty");
    const uncertain = reports.flatMap((report) => array(report.intake?.uncertain_interpretations).map((value) => `${shortId(report.id)}: ${value}`));
    const missing = [...new Set([...reports.flatMap((report) => array(report.intake?.missing_information)), ...array(incident.triage?.missing_information)])];
    uncertainty.append(node("h4", "detail-subheading", "Uncertain interpretations"), evidenceList(uncertain, factCount ? "No uncertainty was listed by the model. This does not verify the report." : "Analysis has not provided an uncertainty assessment."), node("h4", "detail-subheading", "Information to confirm"), evidenceList(missing, "No missing-information assessment available."));
    if (array(incident.triage?.questions).length) uncertainty.append(node("h4", "detail-subheading", "AI-suggested follow-up questions"), evidenceList(incident.triage.questions, ""));
    groups.verification.push(uncertainty);
    groups.verification.unshift(incidentVerification(incident));

    const pending = pendingCorrelations().filter((item) => [item.incident_a_id, item.incident_b_id].includes(incident.id));
    if (pending.length) {
      const correlations = section("Possible related incidents");
      correlations.append(node("p", "", `${pending.length} AI suggestion(s) await your decision. Reports are merged only when you confirm.`), button("Review related reports →", "button-outline", () => openCorrelations(incident.id)));
      groups.verification.push(correlations);
    }

    if (!incident.merged_into) {
      const control = section("Response details");
      const notice = node("div", "human-notice");
      notice.append(node("span", "", "Records the response here. No team is contacted or dispatched automatically."));
      control.append(notice);
      if (incident.triage?.acknowledgement_draft) {
        const draft = node("details", "suggestion-box acknowledgement-draft");
        draft.append(node("summary", "", "AI acknowledgement draft · not sent"), node("p", "", incident.triage.acknowledgement_draft));
        control.append(draft);
      }
      const form = node("form");
      form.id = "incident-form";
      const grid = node("div", "form-grid");
      grid.append(field("Status", "operator-status", {value: incident.status, options: Object.entries(statusLabels)}), field("Response category", "operator-category", {value: incident.category, placeholder: "Enter a human-selected category"}), field("Assigned team", "operator-team", {value: incident.team, placeholder: "Enter a confirmed team", hint: "Recording a team does not send a dispatch."}));
      form.append(grid);
      const message = node("p", "form-message");
      message.id = "incident-action-error";
      message.setAttribute("role", "alert");
      const actions = node("div", "form-actions");
      const save = button("Save response details", "button-primary");
      save.type = "submit";
      save.dataset.action = "save-incident";
      actions.append(save);
      const unacknowledged = array(incident.report_ids).filter((id) => !array(incident.acknowledged_report_ids).includes(id));
      if (unacknowledged.length) {
        const ack = button(`Acknowledge ${unacknowledged.length} report(s)`, "button-outline", async () => {
          await mutateIncident(`/api/incidents/${encodeURIComponent(incident.id)}/acknowledge`, "POST", {}, "Acknowledgement recorded by the backend. Check receipt state for delivery evidence.", form, message, {preserveSubmittedDraft: true});
        });
        ack.dataset.action = "acknowledge-incident";
        actions.append(ack);
      }
      form.append(actions, message);
      form.addEventListener("submit", async (event) => {
        event.preventDefault();
        await mutateIncident(`/api/incidents/${encodeURIComponent(incident.id)}`, "PATCH", {status: $("operator-status").value, category: $("operator-category").value.trim() || null, team: $("operator-team").value.trim() || null}, "Human decision saved. No team was contacted automatically.", form, message);
      });
      control.append(form);
      overviewActions = node("aside", "case-overview-actions");
      overviewActions.setAttribute("aria-label", "Response details and acknowledgement");
      overviewActions.append(control);
    }
    const originals = section("Original messages & relay evidence");
    originals.append(node("p", "detail-muted", "Paths and simulation flags are sender-reported telemetry, not verified radio measurements."));
    reports.forEach((report) => originals.append(reportCard(report, {includeActions: true})));
    groups.reports.push(originals);
    groups.reports.push(receiptEvidence(reports), facts);
    const history = array(state.audit).filter((entry) => entry.entity_id === incident.id || reports.some((report) => report.id === entry.entity_id)).sort((a, b) => Number(b.at) - Number(a.at)).slice(0, 20);
    if (history.length) {
      const audit = section("Recorded activity");
      const list = node("ul", "evidence-list");
      history.forEach((entry) => list.append(node("li", "", `${date(entry.at, true)} · ${auditLabel(entry)} · ${entry.actor}`)));
      audit.append(list);
      groups.activity.push(audit);
    }
    const tabs = node("div", "case-tabs");
    tabs.setAttribute("role", "tablist");
    tabs.setAttribute("aria-label", "Case workspace");
    const definitions = [["overview", "Overview"], ["verification", "Verification"], ["reports", "Reports & delivery"], ["activity", "Activity"]];
    if (overviewActions) {
      const layout = node("div", "case-overview-layout");
      const evidence = node("div", "case-overview-evidence");
      evidence.append(...groups.overview);
      layout.append(evidence, overviewActions);
      groups.overview = [layout];
    }
    const panels = [];
    definitions.forEach(([id, label], index) => {
      const tab = button(label, "case-tab", () => selectCaseTab(id));
      tab.id = `case-tab-${id}`;
      tab.dataset.caseTab = id;
      tab.setAttribute("role", "tab");
      tab.setAttribute("aria-controls", `case-panel-${id}`);
      tab.addEventListener("keydown", (event) => {
        let next;
        if (event.key === "ArrowRight") next = (index + 1) % definitions.length;
        if (event.key === "ArrowLeft") next = (index + definitions.length - 1) % definitions.length;
        if (event.key === "Home") next = 0;
        if (event.key === "End") next = definitions.length - 1;
        if (next !== undefined) { event.preventDefault(); selectCaseTab(definitions[next][0], true); }
      });
      tabs.append(tab);
      const panel = node("section", `case-panel case-panel-${id}`);
      panel.id = `case-panel-${id}`;
      panel.setAttribute("role", "tabpanel");
      panel.setAttribute("aria-labelledby", tab.id);
      panel.tabIndex = 0;
      panel.append(...groups[id]);
      panels.push(panel);
    });
    replace(target, [...parts, tabs, ...panels]);
    if (account.role === "viewer") {
      target.querySelectorAll("form button, form input, form textarea, form select").forEach((control) => { control.disabled = true; });
    }
    selectCaseTab(selectedCaseTab, false, false);
  }

  async function mutateIncident(path, method, payload, success, form, message, {preserveSubmittedDraft = false} = {}) {
    const session = authEpoch;
    const caseId = selectedIncidentId;
    message.textContent = "";
    const controls = [...form.querySelectorAll("button,input,select,textarea")];
    const submit = form.querySelector('button[type="submit"]');
    const submitText = submit?.textContent;
    if (submit) submit.textContent = "Recording decision…";
    form.setAttribute("aria-busy", "true");
    controls.forEach((item) => { item.disabled = true; });
    try {
      const updated = await api(path, {method, body: JSON.stringify(payload)});
      if (session !== authEpoch) return;
      mutationVersion++;
      if (updated?.id) state.incidents = state.incidents.map((item) => item.id === updated.id ? updated : item);
      const sameCase = selectedIncidentId === caseId;
      const drafts = sameCase ? captureCaseDrafts(preserveSubmittedDraft ? null : form) : [];
      toast(success);
      renderSummary();
      renderIncidents();
      renderOperationsRail();
      if (sameCase) {
        formDirty = false;
        dirtyCaseFields.clear();
        detailSignature = "";
        renderIncidentDetail();
        restoreCaseDrafts(drafts);
        selectCaseTab(selectedCaseTab, true, false);
      }
      await refresh({force: true});
    } catch (error) { if (session === authEpoch) message.textContent = error.message; }
    finally {
      controls.forEach((item) => { item.disabled = false; });
      form.setAttribute("aria-busy", "false");
      if (submit) submit.textContent = submitText;
    }
  }

  function scopedCorrelations() {
    return pendingCorrelations().filter((item) => !correlationScope || [item.incident_a_id, item.incident_b_id].includes(correlationScope))
      .sort((a, b) => Number(b.created_at) - Number(a.created_at) || String(a.id).localeCompare(String(b.id)));
  }
  function openCorrelations(incidentId = null) {
    correlationScope = incidentId;
    selectedCorrelationId = null;
    correlationPage = 1;
    renderCorrelations({resetScroll: true});
    if (!$("correlation-dialog").open) $("correlation-dialog").showModal();
    $("correlation-dialog").scrollTop = 0;
  }
  function renderCorrelations({resetScroll = false} = {}) {
    if (correlationDecisionPending) return;
    const correlations = scopedCorrelations();
    correlationSignature = JSON.stringify([state.correlations, state.reports, state.incidents]);
    const target = $("correlation-content");
    if (!correlations.length) {
      selectedCorrelationId = null;
      replace(target, [empty("No correlations awaiting review", "Reports remain separate unless a human confirms a relationship.", "⇄")]);
      return;
    }
    let selectedIndex = correlations.findIndex((item) => item.id === selectedCorrelationId);
    if (selectedIndex < 0) { selectedIndex = 0; selectedCorrelationId = correlations[0].id; }
    correlationPage = Math.floor(selectedIndex / correlationPageSize) + 1;
    const oldScroll = target.querySelector(".correlation-detail")?.scrollTop || 0;
    const workspace = node("div", "correlation-workspace");
    const sidebar = node("aside", "correlation-sidebar");
    sidebar.setAttribute("aria-label", "Pending correlation pairs");
    const heading = node("div", "correlation-list-heading");
    heading.append(node("strong", "", `${correlations.length} pending pair${correlations.length === 1 ? "" : "s"}`), node("small", "", correlationScope ? "For this incident" : "Loaded suggestions · human review"));
    const list = node("div", "correlation-pair-list");
    const choose = (index) => {
      if (index < 0 || index >= correlations.length || correlationDecisionPending) return;
      selectedCorrelationId = correlations[index].id;
      renderCorrelations({resetScroll: true});
      [...target.querySelectorAll(".correlation-pair-item")].find((item) => item.dataset.correlationId === selectedCorrelationId)?.focus({preventScroll: true});
    };
    correlations.slice((correlationPage - 1) * correlationPageSize, correlationPage * correlationPageSize).forEach((item, offset) => {
      const index = (correlationPage - 1) * correlationPageSize + offset;
      const entry = button("", `correlation-pair-item${item.id === selectedCorrelationId ? " active" : ""}`, () => choose(index));
      entry.dataset.correlationId = item.id;
      entry.setAttribute("aria-current", String(item.id === selectedCorrelationId));
      const locations = [item.incident_a_id, item.incident_b_id].map((id) => { const incident = state.incidents.find((candidate) => candidate.id === id); return incident ? location(incident) : `Incident ${shortId(id)}`; });
      entry.append(node("span", "pair-location", locations[0] === locations[1] ? locations[0] : locations.join(" ↔ ")));
      entry.append(node("strong", "", `${shortId(item.incident_a_id)} ↔ ${shortId(item.incident_b_id)}`));
      if (Number.isFinite(item.confidence)) entry.append(node("span", "pair-confidence", `${Math.round(item.confidence * 100)}% model confidence`));
      entry.append(node("span", "pair-reason", item.reason ? `${item.reason.slice(0, 150)}${item.reason.length > 150 ? "…" : ""}` : "No reason supplied."));
      list.append(entry);
    });
    const pagination = node("div", "correlation-list-pagination");
    pagination.id = "correlation-list-pagination";
    renderPagination(pagination, correlationPage, correlations.length, correlationPageSize, (page) => choose((page - 1) * correlationPageSize));
    sidebar.append(heading, list, pagination);
    const detail = node("section", "correlation-detail");
    detail.setAttribute("aria-label", "Selected correlation evidence");
    const toolbar = node("div", "correlation-detail-toolbar");
    const previous = button("← Previous pair", "button-outline small", () => choose(selectedIndex - 1));
    previous.disabled = selectedIndex === 0;
    const next = button("Next pair →", "button-outline small", () => choose(selectedIndex + 1));
    next.disabled = selectedIndex === correlations.length - 1;
    toolbar.append(previous, node("span", "", `Pair ${selectedIndex + 1} of ${correlations.length}`), next);
    const intro = node("div", "human-notice");
    intro.append(node("strong", "", "HUMAN CORRELATION DECISION"), node("span", "", "Confirm merges these incident records. Reject keeps them separate. Model confidence is not a verified probability."));
    detail.append(toolbar, intro, correlationCard(correlations[selectedIndex], selectedIndex));
    workspace.append(sidebar, detail);
    replace(target, [workspace]);
    detail.scrollTop = resetScroll ? 0 : oldScroll;
    if (resetScroll) { $("correlation-dialog").scrollTop = 0; target.scrollTop = 0; }
  }
  function correlationCard(correlation, selectedIndex) {
      const card = node("article", "correlation-card");
      card.dataset.correlationId = correlation.id;
      const meta = node("div", "incident-meta");
      meta.append(tag("AI SUGGESTION", "good"));
      if (Number.isFinite(correlation.confidence)) meta.append(node("span", "confidence-label", `${Math.round(correlation.confidence * 100)}% model confidence`));
      card.append(meta, node("h3", "", `${shortId(correlation.incident_a_id)} ↔ ${shortId(correlation.incident_b_id)}`), node("p", "", correlation.reason || "No reason supplied."), node("p", "detail-muted", `${correlation.model || state.ai.model || "Local model"} · ${date(correlation.created_at, true)}`));
      const involved = state.incidents.filter((incident) => [correlation.incident_a_id, correlation.incident_b_id].includes(incident.id));
      const sources = node("div", "correlation-sources");
      involved.forEach((incident) => {
        const block = node("div", "correlation-source");
        block.append(node("h4", "detail-subheading", `${shortId(incident.id)} / ${location(incident)}`));
        reportsFor(incident).forEach((report) => block.append(reportCard(report, {compact: true})));
        sources.append(block);
      });
      card.append(sources);
      if (array(correlation.evidence).length) {
        const details = node("details", "correlation-evidence");
        details.append(node("summary", "", "Inspect model-cited source quotes"));
        details.append(evidenceList(correlation.evidence.map((item) => `${shortId(item.report_id)}: “${item.quote}”`), ""));
        card.append(details);
      }
      const actions = node("div", "form-actions correlation-actions");
      const error = node("p", "form-message");
      error.setAttribute("role", "alert");
      async function decide(decision) {
        if (correlationDecisionPending || correlationRefreshRequired || account.role === "viewer") return;
        const session = authEpoch;
        correlationDecisionPending = true;
        const buttonStates = [...actions.querySelectorAll("button")].map((item) => [item, item.disabled]);
        buttonStates.forEach(([item]) => { item.disabled = true; });
        error.textContent = "";
        try {
          const result = await api(`/api/correlations/${encodeURIComponent(correlation.id)}/decision`, {method: "POST", body: JSON.stringify({decision})});
          if (session !== authEpoch) return;
          mutationVersion++;
          if (result.correlation?.id) state.correlations = state.correlations.map((item) => item.id === result.correlation.id ? result.correlation : item);
          toast(decision === "confirm" ? "Correlation confirmed by a human. Original reports retained; fresh triage queued." : "Correlation rejected. Incidents remain separate.");
          try {
            const fresh = await api("/api/state");
            if (session !== authEpoch) return;
            if (!Array.isArray(fresh.reports) || !Array.isArray(fresh.incidents) || !Array.isArray(fresh.correlations)) throw new Error("Updated queue response is incomplete");
            state = {...initialState, ...fresh};
            stateReceivedAt = Date.now();
            correlationRefreshRequired = false;
            setConnection(true);
            renderSummary(); renderAI(); renderOperationsRail(); renderIncidents(); renderReports();
          } catch {
            if (session !== authEpoch) return;
            correlationRefreshRequired = true;
            toast("Decision recorded. The remaining queue could not refresh; further decisions wait for a fresh backend snapshot.");
          }
          const remaining = scopedCorrelations();
          selectedCorrelationId = remaining[Math.min(selectedIndex, remaining.length - 1)]?.id || null;
          correlationDecisionPending = false;
          renderCorrelations({resetScroll: true});
        } catch (exception) { if (session === authEpoch) error.textContent = exception.message; }
        finally {
          if (session === authEpoch) correlationDecisionPending = false;
          buttonStates.forEach(([item, disabled]) => { item.disabled = disabled; });
        }
      }
      const confirm = button("Confirm & merge", "button-primary", () => decide("confirm"));
      confirm.dataset.action = "confirm-correlation";
      if (involved.length !== 2 || involved.some((incident) => reportsFor(incident).length !== array(incident.report_ids).length)) {
        confirm.disabled = true;
        error.textContent = "Some original reports are outside the current API window. Confirm is unavailable until the complete source evidence can be reviewed.";
      }
      const reject = button("Reject relationship", "button-outline", () => decide("reject"));
      reject.dataset.action = "reject-correlation";
      if (correlationRefreshRequired) {
        confirm.disabled = true;
        reject.disabled = true;
        error.textContent = "A decision was recorded, but the latest queue is unavailable. Waiting for a fresh backend snapshot before another decision.";
      }
      actions.append(confirm, reject);
      if (account.role === "viewer") { confirm.disabled = true; reject.disabled = true; error.textContent = "Read-only account. A responder must record correlation decisions."; }
      card.append(actions, error);
      return card;
  }

  function replaceMediaRegion(current, next) {
    const active = document.activeElement;
    const restoreFocus = current.contains(active);
    let scope = active;
    if (restoreFocus) {
      while (scope !== current && !scope.dataset?.attachmentId) scope = scope.parentNode;
    }
    const detailKey = (details, container) => {
      let owner = details;
      while (owner !== container && !owner.dataset?.attachmentId) owner = owner.parentNode;
      return `${owner.dataset.reportId || ""}:${owner.dataset.attachmentId || ""}:${details.className}`;
    };
    const expansion = new Map([...current.querySelectorAll("details")].map((details) => [detailKey(details, current), details.open]));
    next.querySelectorAll("details").forEach((details) => { const id = detailKey(details, next); if (expansion.has(id)) details.open = expansion.get(id); });
    current.replaceWith(next);
    if (!restoreFocus) return;
    // A focused read-only details panel must not freeze progress. Retain the same
    // attachment/control focus when possible; a completed retry returns to its review.
    const candidates = [next, ...next.querySelectorAll("[data-attachment-id]")];
    const replacementScope = scope === current ? next : candidates.find((candidate) => candidate.dataset.reportId === scope.dataset.reportId && candidate.dataset.attachmentId === scope.dataset.attachmentId && candidate.tagName === scope.tagName && candidate.className.split(" ")[0] === scope.className.split(" ")[0]) || next;
    const controls = [...replacementScope.querySelectorAll("button,summary,a,input,textarea,select")];
    const replacementControl = active === scope ? replacementScope :
      (active.dataset?.action && controls.find((control) => control.dataset.action === active.dataset.action)) ||
      controls.find((control) => control.tagName === active.tagName && control.textContent === active.textContent) || replacementScope;
    if (!["BUTTON", "SUMMARY", "A", "INPUT", "TEXTAREA", "SELECT"].includes(replacementControl.tagName)) replacementControl.tabIndex = -1;
    replacementControl.focus({preventScroll: true});
  }

  function maybeRefreshDialogs() {
    const selection = window.getSelection();
    const interacting = (container) => container.contains(document.activeElement) || (selection && !selection.isCollapsed && container.contains(selection.anchorNode));
    const selecting = (container) => selection && !selection.isCollapsed && container.contains(selection.anchorNode);
    // Media progress is independent of unsaved responder forms and active players.
    // Replace only read-only review regions; never reload a playing attachment.
    if ($("incident-dialog").open) {
      const incident = state.incidents.find((item) => item.id === selectedIncidentId);
      if (incident) {
        const reports = reportsFor(incident);
        const overview = $("incident-media-overview");
        if (overview && !selecting(overview) && overview.dataset.signature !== mediaReviewSignature(reports)) replaceMediaRegion(overview, incidentMediaOverview(reports));
        $("detail-content").querySelectorAll(".media-evidence").forEach((evidence) => {
          const report = reports.find((item) => item.id === evidence.dataset.reportId);
          if (!report || selecting(evidence) || evidence.dataset.signature === mediaReviewSignature([report])) return;
          const next = mediaEvidence(report);
          replaceMediaRegion(evidence, next);
        });
      }
    }
    for (const dialog of mediaDialogs) {
      const report = state.reports.find((entry) => entry.id === dialog.dataset.reportId);
      const item = array(report?.media).find((entry) => entry.id === dialog.dataset.attachmentId);
      const review = dialog.querySelector(".media-viewer-review");
      if (report && item && review && !selecting(review) && review.dataset.signature !== mediaReviewSignature([report])) replaceMediaRegion(review, viewerMediaReview(report, item, dialog));
    }
    // Stage progress remains live while an operator edits a separate form.
    if ($("incident-dialog").open) {
      const incident = state.incidents.find((item) => item.id === selectedIncidentId);
      const stages = $("incident-pipeline");
      const signature = JSON.stringify(incident?.processing_stages || {});
      if (incident && stages && !interacting(stages) && stages.dataset.signature !== signature) {
        const nextStages = processingStages(incident);
        nextStages.id = stages.id;
        nextStages.dataset.signature = signature;
        stages.replaceWith(nextStages);
      }
    }
    if ($("incident-dialog").open && !formDirty && !interacting($("detail-content"))) {
      const incident = state.incidents.find((item) => item.id === selectedIncidentId);
      if (incident && JSON.stringify([incident, reportsFor(incident), state.audit]) !== detailSignature) renderIncidentDetail();
    }
    if ($("correlation-dialog").open && !interacting($("correlation-content")) && JSON.stringify([state.correlations, state.reports, state.incidents]) !== correlationSignature) renderCorrelations();
  }

  async function enterKey() {
    let config;
    try { config = await api("/api/auth/config"); } catch (error) { toast(error.message); return; }
    if (config.mode === "local_demo") return toast("This local demo has no individual accounts configured. Production mode requires responder accounts.");
    const accounts = config.mode === "accounts";
    const dialog = node("dialog", "auth-dialog");
    dialog.setAttribute("aria-labelledby", "auth-title");
    const title = node("h2", "", accounts ? "Sign in to ResQMesh" : "Connect to protected backend");
    title.id = "auth-title";
    const form = node("form");
    const usernameLabel = node("label", "form-field", "Username");
    const username = node("input"); username.name = "username"; username.autocomplete = "username"; username.required = accounts; usernameLabel.append(username);
    const label = node("label", "form-field", accounts ? "Password" : "API access key");
    const input = node("input");
    input.type = "password";
    input.name = "api_key";
    input.spellcheck = false;
    input.autocomplete = accounts ? "current-password" : "off";
    input.required = true;
    label.append(input);
    const actions = node("div", "form-actions");
    const submit = button("Connect", "button-primary");
    submit.type = "submit";
    actions.append(submit, button("Cancel", "button-outline", () => dialog.close()));
    const errorText = node("p", "form-error"); errorText.setAttribute("role", "alert");
    if (accounts) form.append(usernameLabel);
    form.append(label, node("p", "detail-muted", accounts ? "Your role controls access. Sessions expire after eight hours." : "The key is kept in memory for this page only."), errorText, actions);
    form.addEventListener("submit", async (event) => {
      event.preventDefault(); submit.disabled = true;
      try {
        if (accounts) await api("/api/auth/login", {method: "POST", body: JSON.stringify({username: username.value.trim(), password: input.value})});
        else key = input.value.trim();
        authEpoch++;
        closeMedia(); dialog.close(); refresh({force: true});
      } catch (error) { errorText.textContent = error.message; submit.disabled = false; }
    });
    dialog.append(title, form);
    dialog.addEventListener("close", () => dialog.remove());
    document.body.append(dialog);
    dialog.showModal();
  }

  document.querySelectorAll("[data-filter]").forEach((filter) => filter.addEventListener("click", () => {
    selectedFilter = filter.dataset.filter;
    incidentPage = 1;
    syncFilterButtons();
    renderIncidents();
  }));
  document.querySelectorAll("[data-close]").forEach((close) => close.addEventListener("click", () => $(close.dataset.close).close()));
  $("incident-dialog").addEventListener("close", closeIncidentWorkspace);
  $("incident-search").addEventListener("input", () => { incidentPage = 1; renderIncidents(); });
  $("clear-incident-search")?.addEventListener("click", clearIncidentSearch);
  $("report-search")?.addEventListener("input", () => { reportPage = 1; renderReports(); });
  $("clear-report-search")?.addEventListener("click", clearReportSearch);
  $("incident-sort")?.addEventListener("change", (event) => {
    selectedSort = ["priority", "newest", "oldest"].includes(event.target.value) ? event.target.value : "priority";
    incidentPage = 1;
    renderIncidents();
  });
  document.querySelectorAll("button[data-view]").forEach((item) => item.addEventListener("click", () => setView(item.dataset.view)));
  $("review-button").addEventListener("click", () => openCorrelations());
  $("review-nav").addEventListener("click", () => openCorrelations());
  $("refresh-button").addEventListener("click", () => refresh({force: true}));
  $("account-button")?.addEventListener("click", async () => {
    if (account.role === "demo") return enterKey();
    try { await api("/api/auth/logout", {method: "POST"}); key = ""; account = {name: "Signed out", role: "demo"}; clearWorkspace(); await refresh({force: true}); } catch (error) { toast(error.message); }
  });
  $("notifications-button")?.addEventListener("click", async () => {
    if (!("Notification" in window)) return toast("This browser does not support notifications. Alerts remain visible in this workspace.");
    const permission = await Notification.requestPermission();
    toast(permission === "granted" ? "Media review notifications enabled while this workspace is open." : "Notifications are off. Review alerts remain visible in this workspace.");
  });
  $("retry-button").addEventListener("click", () => $("retry-button").dataset.authRequired === "true" ? enterKey() : refresh({force: true}));
  document.addEventListener("visibilitychange", () => { if (!document.hidden) refresh(); });
  window.addEventListener("pagehide", closeMedia);
  const tick = () => { $("clock").textContent = new Date().toLocaleTimeString(undefined, {hour: "2-digit", minute: "2-digit"}); };
  setView("overview");
  observePaneCapacity();
  tick();
  window.setInterval(tick, 30000);
  refresh();
})();
