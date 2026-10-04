#!/usr/bin/env python3
"""Exercise the real local API with clearly marked synthetic simulation reports.

Use a fresh test database/server to keep these reports out of a presentation.
This verifies API/AI integration, not Android transport or physical radio delivery.
"""
from __future__ import annotations

import argparse
import json
import os
import time
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def call(base, path, method='GET', body=None):
    headers = {'Content-Type': 'application/json'}
    token = os.environ.get('RESQMESH_API_TOKEN')
    if token:
        headers['X-API-Key'] = token
    request = Request(base + path, method=method,
                      data=json.dumps(body).encode() if body is not None else None,
                      headers=headers)
    with urlopen(request, timeout=15) as response:
        return response.status, json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8001')
    parser.add_argument('--wait-ai', action='store_true')
    parser.add_argument('--timeout', type=int, default=600)
    args = parser.parse_args()
    now = int(time.time() * 1000)
    packets = []
    for text, zone in [
        ('Ground floor lo water fast ga vastundi. Three people trapped. One elderly person.', 'Ground floor'),
        ('Basement water level increasing. Elderly person needs help.', 'Basement'),
    ]:
        packets.append(dict(schema_version=1, id=str(uuid.uuid4()), origin_id='A',
            created_at=now, expires_at=now + 3600000, text=text,
            building='ResQMesh synthetic test apartment', zone=zone, hop_count=3,
            max_hops=8, relay_path=['A','B','C','D'], simulation=True))
    receipts = []
    for packet in packets:
        status, receipt = call(args.url, '/api/reports', 'POST', packet)
        assert status == 202 and receipt['report_id'] == packet['id'], receipt
        assert receipt['duplicate'] is False
        receipts.append(receipt)
    _, duplicate = call(args.url, '/api/reports', 'POST', packets[0])
    assert duplicate['duplicate'] is True
    assert duplicate['incident_id'] == receipts[0]['incident_id']
    try:
        call(args.url, '/api/reports', 'POST', {**packets[0], 'text': 'Changed text under the same UUID'})
        raise AssertionError('Conflicting UUID was accepted')
    except HTTPError as error:
        assert error.code == 409, error.code
    _, state = call(args.url, '/api/state')
    ids = {p['id'] for p in packets}
    assert len([r for r in state['reports'] if r['id'] in ids]) == 2
    print('PASS: durable ingest, duplicate idempotence, conflicting UUID rejection', flush=True)
    if args.wait_ai:
        deadline = time.monotonic() + args.timeout
        while time.monotonic() < deadline:
            _, state = call(args.url, '/api/state')
            reports = [r for r in state['reports'] if r['id'] in ids]
            bad = [r for r in reports if r['ai_status'] in ('failed','unavailable')]
            if bad and state['ai']['queue'].get('queued', 0) == 0 and state['ai']['queue'].get('running', 0) == 0:
                raise AssertionError([(r['ai_status'],r.get('ai_error')) for r in bad])
            if len(reports) == 2 and all(r['ai_status'] == 'complete' for r in reports):
                incident_ids = {r['incident_id'] for r in reports}
                relevant = [i for i in state['incidents'] if i['id'] in incident_ids]
                if all(i['triage_status'] == 'complete' for i in relevant) and not state['ai'].get('active_job') and not state['ai']['queue'].get('queued'):
                    print('PASS: actual model intake + triage complete for both reports', flush=True)
                    for report in reports:
                        print(json.dumps({'report_id':report['id'],'intake':report['intake']}, ensure_ascii=False), flush=True)
                    suggestions = [c for c in state['correlations'] if {c['incident_a_id'],c['incident_b_id']} <= incident_ids]
                    print('Actual model correlation suggestions:', json.dumps(suggestions), flush=True)
                    break
            time.sleep(3)
        else:
            raise AssertionError('AI pipeline did not finish before the deadline')
    incident = receipts[0]['incident_id']
    _, acknowledged = call(args.url, f'/api/incidents/{incident}/acknowledge', 'POST', {})
    assert acknowledged['acknowledged_at'] is not None
    _, assigned = call(args.url, f'/api/incidents/{incident}', 'PATCH', {
        'status':'in_progress', 'category':'Flood rescue assessment', 'team':'Demo response team'})
    assert assigned['team'] == 'Demo response team'
    _, resolved = call(args.url, f'/api/incidents/{incident}', 'PATCH', {'status':'resolved'})
    assert resolved['status'] == 'resolved'
    print('PASS: human acknowledgement, assignment, status and resolution', flush=True)
    print(json.dumps({'report_ids':sorted(ids),'incident_ids':[r['incident_id'] for r in receipts]}), flush=True)


if __name__ == '__main__':
    main()
