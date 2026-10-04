#!/usr/bin/env python3
"""Real HTTP + local-model rehearsal; use a separate test backend/database.

This does not exercise Android radios. Every report is explicitly simulated.
"""
import argparse
import json
from pathlib import Path
import time
import uuid
from urllib.error import HTTPError
from smoke_api import call


def run(base, timeout, output, resume=False):
    if resume:
        saved = json.loads(Path(output).with_suffix('.ids.json').read_text())
        accepted = saved['accepted']
        _, state = call(base, '/api/state')
        by_id = {r['id']: r for r in state['reports']}
        packets = [by_id[rid] for rid in saved['report_ids']]
        print('Resuming checks for the existing synthetic batch; no new reports created', flush=True)
    else:
        now = int(time.time() * 1000)
        batch = uuid.uuid4().hex[:6]
        source_ids = [f'RQM-{uuid.uuid4().hex[:8].upper()}' for _ in range(5)]
        gateway = f'RQM-{uuid.uuid4().hex[:8].upper()}'
        relay = f'RQM-{uuid.uuid4().hex[:8].upper()}'
        _, presence = call(base, f'/api/gateways/{gateway}/heartbeat', 'POST', {'simulation': True})
        print('PASS: actual gateway heartbeat', presence, flush=True)
        packets, accepted = [], []
        samples = [
            ('Ground floor lo water fast ga vastundi. Three people trapped. One elderly person.', f'Synthetic River Court {batch}', 'Ground', 3),
            ('Basement water level increasing. Elderly person needs help.', f'Synthetic River Court {batch}', 'Basement', None),
            *[('Smoke near the east stairwell. Help needed.', f'Synthetic Cedar Hall {batch}', '2', None)] * 3,
        ]
        for index, (text, building, floor, count) in enumerate(samples):
            path = [source_ids[index], relay, gateway] if index < 2 else [source_ids[index], gateway]
            packet = dict(schema_version=2, id=str(uuid.uuid4()), origin_id=source_ids[index],
                          created_at=now+index, expires_at=now+3600000, text=text, building=building,
                          zone=None, hop_count=len(path)-1, max_hops=8, relay_path=path, simulation=True,
                          emergency_type='flood' if index < 2 else 'fire', location_text=None, floor=floor,
                          room=None, people_affected=count, vulnerability='Elderly person' if index < 2 else None)
            status, result = call(base, '/api/reports', 'POST', packet)
            assert status == 202 and result['receipt']['report_id'] == packet['id']
            assert result['receipt']['type'] == 'backend_received'
            assert result['receipt']['relay_path'] == path
            packets.append(packet); accepted.append(result)
        _, repeated = call(base, '/api/reports', 'POST', packets[0])
        assert repeated['duplicate'] and repeated['receipt'] == accepted[0]['receipt']
        alternate = {**packets[0], 'relay_path': [source_ids[0], 'RQM-ALTERNATE'], 'hop_count': 1}
        _, other = call(base, '/api/reports', 'POST', alternate)
        assert other['duplicate'] and other['receipt'] == accepted[0]['receipt']
        try:
            call(base, '/api/reports', 'POST', {**packets[0], 'people_affected': 12})
            raise AssertionError('UUID collision accepted changed structured payload')
        except HTTPError as e:
            assert e.code == 409
        print('PASS: structured ingest, immutable receipts, duplicate multi-gateway upload, UUID collision rejection', flush=True)
        Path(output).parent.mkdir(parents=True, exist_ok=True)
        Path(output).with_suffix('.ids.json').write_text(json.dumps({'report_ids':[p['id'] for p in packets], 'accepted':accepted}, indent=2))
    ids = {p['id'] for p in packets}
    deadline = time.monotonic() + timeout
    previous = ''
    while time.monotonic() < deadline:
        _, state = call(base, '/api/state')
        reports = [r for r in state['reports'] if r['id'] in ids]
        incidents = [i for i in state['incidents'] if set(i['report_ids']) & ids and not i['merged_into']]
        summary = json.dumps({i['id'][:8]: {k:v['status'] for k,v in i['processing_stages'].items()} for i in incidents})
        if summary != previous: print(summary, flush=True); previous=summary
        if len(incidents) == 5 and all(all(s['status']=='complete' for s in i['processing_stages'].values()) for i in incidents):
            break
        if state['ai']['queue'].get('failed') and not state['ai']['queue'].get('running') and not state['ai']['queue'].get('queued'):
            Path(output).write_text(json.dumps(state, indent=2, ensure_ascii=False))
            raise AssertionError('AI stage failed; inspect evidence JSON')
        time.sleep(3)
    else:
        raise AssertionError('Actual model pipeline exceeded deadline')
    copy_ids = {p['id'] for p in packets[2:]}
    copy_incidents = [i for i in incidents if set(i['report_ids']) & copy_ids]
    assert all(i['verification']['source_independence']=='unknown' for i in copy_incidents)
    assert any(any(s['code']=='repeated_text' for s in i['verification_signals']) for i in copy_incidents)
    assert all(i['verification_status']=='unverified' for i in incidents)
    assert all(i['people_total'] is None for i in incidents)
    assert any(c['status']=='pending' for c in state['correlations'])
    print('PASS: real Gemma intake, correlation, verification and triage; copy-pattern review before merge; human state unchanged', flush=True)
    incident=accepted[0]['incident_id']
    _, ack=call(base,f'/api/incidents/{incident}/acknowledge','POST',{})
    _, feed=call(base,f"/api/receipts?report_id={packets[0]['id']}")
    assert {r['type'] for r in feed['receipts']} == {'backend_received','responder_acknowledged'}
    try:
        call(base,f'/api/incidents/{incident}/verification','POST',{'action':'responder_verified'})
        raise AssertionError('Human verification without notes accepted')
    except HTTPError as e:
        assert e.code==422
    _, review=call(base,f'/api/incidents/{incident}/verification','POST',{'action':'request_verification','notes':'Synthetic test: request an on-site check.'})
    _, human=call(base,f'/api/incidents/{incident}/verification','POST',{'action':'responder_verified','notes':'Synthetic test only: operator records a rehearsal verification.'})
    assert human['verification_status']=='responder_verified'
    _, state=call(base,'/api/state')
    Path(output).write_text(json.dumps(state,indent=2,ensure_ascii=False))
    print('PASS: separate responder receipt, explicit note-backed human verification; dashboard-origin delivery remains unknown',flush=True)
    print(json.dumps({'report_ids':sorted(ids),'output':str(output)}),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url',default='http://127.0.0.1:8001')
    parser.add_argument('--timeout',type=int,default=900)
    parser.add_argument('--output',default='artifacts/dynamic-product/live-api-state.json')
    parser.add_argument('--resume', action='store_true', help='Resume the saved synthetic batch after repairing a backend failure')
    args=parser.parse_args(); run(args.url,args.timeout,args.output,args.resume)
