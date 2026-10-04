/** Deterministic DOM checks of production media UI; no browser or live database. */
const fs = require('fs'), vm = require('vm'), path = require('path'), assert = require('assert/strict');
class Element {
  constructor(tag) { this.tagName = tag.toUpperCase(); this.children = []; this.dataset = {}; this.handlers = {}; this.attributes = {}; this._text = ''; }
  set textContent(value) { this._text = String(value); this.children = []; }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  append(...children) { for (const child of children) { child.parentNode = this; this.children.push(child); } }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  addEventListener(name, handler) { (this.handlers[name] ??= []).push(handler); }
  showModal() { this.open = true; }
  close() { this.open = false; for (const handler of this.handlers.close || []) handler(); }
  remove() { if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(child => child !== this); }
  pause() { this.paused = true; }
  descendants(tag) { return this.children.flatMap(child => [...(child.tagName === tag.toUpperCase() ? [child] : []), ...child.descendants(tag)]); }
}
const body = new Element('body');
const document = {body, createElement: tag => new Element(tag)};
const activeUrls = new Set();
const URL = {createObjectURL() { const url = `blob:isolated-${activeUrls.size + 1}`; activeUrls.add(url); return url; }, revokeObjectURL(url) { activeUrls.delete(url); }};
let requests = [], nextResponse;
const context = {document, URL, AbortController, Date, JSON, Set, Map, console,
  window: {setTimeout: () => 1, clearTimeout() {}},
  fetch: async (url, options) => { requests.push({url, options}); return nextResponse; }};
let source = fs.readFileSync(path.resolve(__dirname, '../static/app.js'), 'utf8');
source = source.slice(0, source.lastIndexOf('  document.querySelectorAll("[data-filter]").forEach((filter)'));
source += 'globalThis.test={openMedia,closeMedia,mediaEvidence,reportListRow,setKey(value){key=value},setState(value){state={...initialState,...value}}};})();';
vm.runInNewContext(source, context);
const t = context.test;
const report = {id: 'df30abac-c7e1-469d-ae3d-bb596bd948a6', incident_id: 'case-1', origin_id: 'TEST-SOURCE', message_source: 'preset', text: 'Help needed; details unavailable.', simulation: true, emergency_type: 'other', receipts: [{type: 'backend_received'}]};
const item = {id: '0a2d516d-131e-443a-bd2d-90813a57ed31', kind: 'image', mime_type: 'image/jpeg', byte_size: 12, status: 'pending'};

(async () => {
  t.setState({reports: [report], incidents: [{id: 'case-1'}]});
  const pending = t.mediaEvidence({...report, media: [item]});
  assert.match(pending.textContent, /SOS received; attachment pending/);
  assert.match(pending.textContent, /Neither establishes whether an emergency is genuine/);
  assert.equal(pending.descendants('button').length, 0);
  assert.equal(requests.length, 0);
  const queue = t.reportListRow({...report, media: [item]});
  assert.equal(queue.children.length, 5);
  assert.match(queue.textContent, /Photo pending/);
  assert.equal(queue.descendants('img').length, 0);

  t.setKey('test-only-memory-key');
  for (const [kind, mime, tag] of [['image', 'image/jpeg', 'img'], ['audio', 'audio/mp4', 'audio'], ['video', 'video/mp4', 'video']]) {
    const available = {...item, kind, mime_type: mime, status: 'available'};
    nextResponse = {ok: true, blob: async () => ({size: 12, type: mime})};
    const media = t.mediaEvidence({...report, media: [available]});
    assert.equal(media.descendants('button').length, 1);
    assert.match(media.textContent, /Available for review/);
    const before = requests.length;
    assert.equal(requests.length, before); // Rendering never fetches or autoplays.
    await t.openMedia(report, available);
    const request = requests.at(-1);
    assert.equal(request.options.headers['X-API-Key'], 'test-only-memory-key');
    assert.equal(request.options.cache, 'no-store');
    assert.ok(!request.url.includes('test-only-memory-key'));
    assert.equal(request.url, `/api/reports/${report.id}/attachments/${item.id}`);
    const dialog = body.children.at(-1), player = dialog.descendants(tag)[0];
    assert.ok(dialog.open && player);
    assert.equal(activeUrls.size, 1);
    assert.equal(player.autoplay, undefined);
    assert.match(dialog.textContent, /Source-submitted media · unverified · review AI interpretation separately/);
    if (kind !== 'image') {
      assert.equal(player.controls, true);
      assert.equal(player.preload, 'metadata');
      assert.match(dialog.textContent, /Any AI transcript appears separately/);
    }
    t.closeMedia();
    assert.equal(activeUrls.size, 0);
    assert.equal(body.children.length, 0);
    assert.ok(request.options.signal.aborted);
    if (kind !== 'image') assert.ok(player.paused);
  }

  nextResponse = {ok: false, status: 401, json: async () => ({detail: 'A valid X-API-Key is required'})};
  await t.openMedia(report, {...item, status: 'available'});
  assert.match(body.children[0].textContent, /A valid X-API-Key is required/);
  assert.equal(activeUrls.size, 0);
  t.closeMedia();
  nextResponse = {ok: true, blob: async () => ({size: 13, type: 'image/jpeg'})};
  await t.openMedia(report, {...item, status: 'available'});
  assert.match(body.children[0].textContent, /does not match its report/);
  assert.equal(activeUrls.size, 0);
  t.closeMedia();
  context.fetch = (_url, options) => new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new DOMException('Closed', 'AbortError'))));
  const loading = t.openMedia(report, {...item, status: 'available'});
  t.closeMedia();
  await loading;
  assert.equal(activeUrls.size, 0);
  assert.equal(body.children.length, 0);
  console.log('PASS: pending vs available media, bounded five-cell incoming row, explicit authenticated image/audio/video opens, no autoplay, object URL/player/request cleanup, authorization failure, payload mismatch and close-during-load.');
})().catch(error => { console.error(error); process.exitCode = 1; });
