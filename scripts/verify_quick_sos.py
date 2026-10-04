#!/usr/bin/env python3
"""Exercise schema-3 quick SOS against an isolated real HTTP/Gemma backend.

Creates synthetic reports only. This is neither an emergency submission nor a radio test.
"""
import argparse
import json
from pathlib import Path
import time
import uuid
from urllib.error import HTTPError

from smoke_api import call


def run(base, output, timeout):
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    now = int(time.time() * 1000)
    batch = uuid.uuid4().hex[:8].upper()
    packets, accepted = [], []
    for index in range(2):
        origin = f"RQM-QUICK-{batch}-{index}"
        packet = dict(schema_version=3, id=str(uuid.uuid4()), origin_id=origin,
                      created_at=now, expires_at=now+3_600_000, text=" \t ",
                      building=None, zone=None, emergency_type="other", people_affected=None,
                      hop_count=1, max_hops=8, relay_path=[origin, f"RQM-GATE-{batch}"],
                      simulation=True, quick_needs=[],
                      location_context=dict(source="unknown", observed_at=None, latitude=None,
                                            longitude=None, accuracy_m=None))
        status, result = call(base, '/api/reports', 'POST', packet)
        assert status == 202 and not result['duplicate']
        assert result['receipt']['type'] == 'backend_received'
        packets.append(packet)
        accepted.append(result)
    # A relay can resend the normalized packet through another gateway safely.
    retry = {**packets[0], 'text': 'Help needed; details unavailable.', 'message_source': 'preset',
             'relay_path': [packets[0]['origin_id'], f'RQM-OTHER-{batch}']}
    _, repeated = call(base, '/api/reports', 'POST', retry)
    assert repeated['duplicate'] and repeated['receipt'] == accepted[0]['receipt']
    try:
        call(base, '/api/reports', 'POST', {**retry, 'quick_needs': ['cannot_move']})
        raise AssertionError('Changed immutable quick needs accepted for the same UUID')
    except HTTPError as error:
        assert error.code == 409
    ids = {p['id'] for p in packets}
    output.with_suffix('.ids.json').write_text(json.dumps({'report_ids': sorted(ids)}, indent=2))
    print('PASS: blank SOS accepted, normalized replay deduplicated, changed metadata rejected', flush=True)
    previous = None
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        _, state = call(base, '/api/state')
        reports = [r for r in state['reports'] if r['id'] in ids]
        incidents = [i for i in state['incidents'] if set(i['report_ids']) & ids]
        summary = {i['id'][:8]: {k: v['status'] for k, v in i['processing_stages'].items()} for i in incidents}
        if summary != previous:
            print(json.dumps(summary), flush=True)
            previous = summary
        output.write_text(json.dumps(state, indent=2, ensure_ascii=False))
        if len(incidents) == 2 and all(all(s['status'] == 'complete' for s in i['processing_stages'].values()) for i in incidents):
            break
        if state['ai']['queue'].get('failed') and not any(state['ai']['queue'].get(k) for k in ('running', 'queued')):
            raise AssertionError('Actual model processing failed; original reports retained in evidence JSON')
        time.sleep(3)
    else:
        raise AssertionError('Actual model pipeline exceeded deadline')
    assert len(reports) == 2 and len(incidents) == 2
    assert all(r['message_source'] == 'preset' and r['text'] == 'Help needed; details unavailable.' for r in reports)
    assert all(r['people_affected'] is None and r['location_context']['source'] == 'unknown' for r in reports)
    assert all(i['verification_status'] == 'unverified' and i['people_total'] is None for i in incidents)
    assert all(not any(s['code'] == 'repeated_text' for s in i['verification_signals']) for i in incidents)
    assert all(i['verification']['suggested_state'] != 'suspicious_reporting_pattern' for i in incidents)
    assert not any(c['incident_a_id'] in {i['id'] for i in incidents} and
                   c['incident_b_id'] in {i['id'] for i in incidents} for c in state['correlations'])
    assert all(not any(f['field'] in ('people_affected', 'location') for f in r['intake']['facts']) for r in reports)
    print('PASS: real Gemma handles missing details, keeps human truth unverified, invents no count/location, and does not correlate matching presets', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8003')
    parser.add_argument('--output', default='artifacts/zero-typing-sos/live-quick-state.json')
    parser.add_argument('--timeout', type=int, default=600)
    args = parser.parse_args()
    run(args.url, args.output, args.timeout)
