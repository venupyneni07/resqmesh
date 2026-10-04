from backend.security import current_actor
from backend.store import Store
from backend.tests.test_backend import packet


def test_authenticated_account_is_distinct_from_self_reported_reviewer(tmp_path):
    store = Store(str(tmp_path / "identity.db"))
    incident = store.accept(packet())["incident_id"]
    token = current_actor.set("test.responder")
    try:
        decision = store.verification_action(incident, "responder_verified", "Checked by local test operator",
            reviewer_label="Self-reported team label", check_method="direct_observation",
            evidence_reference="Synthetic validation record")
    finally:
        current_actor.reset(token)
    review = decision["human_verification"]
    assert review["authenticated_account"] == "test.responder"
    assert review["reviewer_label"] == "Self-reported team label"
    assert review["identity_assurance"] == "authenticated_account_self_reported_reviewer"
    assert review["evidence_validation"] == "not_independently_validated"
    event = next(row for row in store.snapshot()["audit"] if row["action"] == "verification.responder_verified")
    assert event["actor"] == "test.responder"
    assert current_actor.get() is None


def test_legacy_review_does_not_claim_an_authenticated_account(tmp_path):
    store = Store(str(tmp_path / "identity.db"))
    incident = store.accept(packet())["incident_id"]
    review = store.verification_action(incident, "request_verification", "Needs review")["human_verification"]
    assert review["authenticated_account"] is None
    assert review["identity_assurance"] == "self_reported_not_authenticated"
