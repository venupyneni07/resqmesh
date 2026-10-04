/**
 * Run: node backend/tests/dashboard_interactions.cjs (from resqmesh/).
 * Also works from any working directory using this script's absolute path.
 * Executes the actual dashboard functions in an isolated minimal DOM with synthetic
 * test data and mocked HTTP responses. No browser, server, model, or live database
 * is read or changed. This verifies logic; it does not replace real browser layout
 * and API integration checks. The production source contains no test fixture data.
 */
const fs=require('fs'),vm=require('vm'),assert=require('assert/strict'),path=require('path');
const projectRoot=path.resolve(__dirname,'../..');
console.log(`ResQMesh dashboard DOM regression · ${process.version} · ${new Date().toISOString()}`);
class Element {
 constructor(tag){this.tagName=tag.toUpperCase();this.children=[];this.parentNode=null;this.attributes={};this.dataset={};this.className='';this.value='';this.handlers={};this.style={setProperty(k,v){this[k]=v}};this._text='';this.hidden=false;this.classList={add:(...names)=>{this.className=[...new Set([...this.className.split(' '),...names])].join(' ').trim()},toggle:(name,on)=>{const set=new Set(this.className.split(' '));const next=on===undefined?!set.has(name):on;if(next)set.add(name);else set.delete(name);this.className=[...set].join(' ');return next}}}
 set textContent(v){this._text=String(v);this.children.forEach(x=>x.parentNode=null);this.children=[]}get textContent(){return this._text+this.children.map(x=>x.textContent).join('')}
 append(...xs){for(const x of xs){if(x.parentNode)x.remove();this.children.push(x);x.parentNode=this}}
 replaceChildren(...xs){this.children.forEach(x=>x.parentNode=null);this.children=[];this._text='';this.append(...xs)}
 remove(){if(this.parentNode){this.parentNode.children=this.parentNode.children.filter(x=>x!==this);this.parentNode=null}}
 replaceWith(x){const p=this.parentNode,i=p.children.indexOf(this);p.children[i]=x;x.parentNode=p;this.parentNode=null}
 setAttribute(k,v){this.attributes[k]=String(v)}getAttribute(k){return this.attributes[k]??null}
 removeAttribute(k){delete this.attributes[k]}addEventListener(k,f){(this.handlers[k]??=[]).push(f)}async dispatch(k,props={}){for(const f of this.handlers[k]||[])await f({target:this,preventDefault(){},...props})}
 contains(x){return this===x||this.children.some(c=>c.contains(x))}focus(){document.activeElement=this}showModal(){this.open=true}close(){this.open=false}scrollIntoView(){}
 querySelectorAll(s){let found=[];for(const x of this.children){if(s.split(',').some(t=>matches(x,t.trim())))found.push(x);found.push(...x.querySelectorAll(s))}return found}querySelector(s){return this.querySelectorAll(s)[0]??null}
}
function matches(x,s){if(s.startsWith('.'))return x.className.split(' ').includes(s.slice(1));const m=s.match(/^(\w+)?\[([^=\]]+)(?:="([^"]*)")?\]$/);if(m){if(m[1]&&x.tagName!==m[1].toUpperCase())return false;const key=m[2];let v=key.startsWith('data-')?x.dataset[key.slice(5).replace(/-([a-z])/g,(_,c)=>c.toUpperCase())]:x.attributes[key]??x[key];return m[3]===undefined?v!==undefined:v===m[3]}return x.tagName===s.toUpperCase()}
const document={body:new Element('body'),activeElement:null,createElement:t=>new Element(t),getElementById:id=>[document.body,...document.body.querySelectorAll('*')].find(x=>x.id===id),querySelectorAll:s=>document.body.querySelectorAll(s)};
const oldMatches=matches;matches=(x,s)=>s==='*'||oldMatches(x,s);
for(const id of ['incident-search','incident-count','queue-visible','incidents-list','detail-title','detail-content','incident-dialog','correlation-dialog','correlation-content','verification-desk','arrival-chart','activity-feed','workspace-main','overview-summary','workspace-grid','reports-section','system-section','page-title','page-description','incident-pagination','report-pagination','reports-list','report-search','clear-report-search','clear-incident-search']){const x=new Element('div');x.id=id;document.body.append(x)}
for(const match of fs.readFileSync(path.join(projectRoot,'backend/static/index.html'),'utf8').matchAll(/\bid="([^"]+)"/g)){if(!document.getElementById(match[1])){const element=new Element('div');element.id=match[1];document.body.append(element)}}
const window={getSelection:()=>null,setTimeout:()=>1,clearTimeout(){},matchMedia:()=>({matches:true})};let request;
const context={document,window,Date,JSON,Set,Map,console,AbortController,fetch:async(path,opts)=>{request={path,body:JSON.parse(opts.body)};return{ok:false,status:422,json:async()=>({detail:'intentional isolated mock rejection'})}}};
let src=fs.readFileSync(path.join(projectRoot,'backend/static/app.js'),'utf8');src=src.slice(0,src.lastIndexOf('  document.querySelectorAll("[data-filter]").forEach((filter)'));
src+='globalThis.test={setState(value){state={...initialState,...value};account=value.account||{name:"Local demo",role:"demo"};connected=true},snapshot(){return{state,account,connected,authEpoch,correlationDecisionPending,correlationRefreshRequired}},refresh,clearWorkspace,enterKey,renderIncidents,renderOperationsRail,openIncident,selectCaseTab,maybeRefreshDialogs,incidentOrder,renderReports,setView,openCorrelations,renderCorrelations,fitPanePages,observePaneCapacity,closeIncidentWorkspace,discardCaseDraft,clearReportSearch,clearIncidentSearch,setSort(value){selectedSort=value},setDirty(value){formDirty=value}};})();';vm.runInNewContext(src,context);const t=context.test,$=document.getElementById;
const now=Date.now();const reports=[{id:'report-critical',origin_id:'RQM-X',text:'<script>inert report</script>',simulation:true,relay_path:['RQM-X','RQM-Y'],received_at:now-1000,people_affected:3,emergency_type:'flood',room:'12',receipts:[{type:'backend_received'}],intake:{facts:[]}}, {id:'report-low',origin_id:'RQM-Z',text:'Second report',simulation:true,relay_path:['RQM-Z'],received_at:now-3600001,receipts:[]}];
const stages=Object.fromEntries(['intake','correlation','verification','triage'].map(s=>[s,{status:'complete'}]));
const incidents=[{id:'low',title:'Low case',created_at:1,updated_at:3,status:'new',report_ids:['report-low'],triage:{suggested_urgency:'low'},verification_status:'unverified',processing_stages:stages},{id:'critical',title:'Critical case',created_at:2,updated_at:4,status:'new',report_ids:['report-critical'],triage:{suggested_urgency:'critical'},verification_status:'unverified',processing_stages:stages,verification_signals:[]}];
t.setState({reports,incidents,audit:[{at:now,action:'report.accepted',actor:'gateway',entity_id:'report-critical'}],server_time:now});t.renderIncidents();assert.equal($('incidents-list').children[0].dataset.incidentId,'critical');assert.equal($('incidents-list').children[0].children.length,6);
t.setSort('oldest');t.renderIncidents();assert.equal($('incidents-list').children[0].dataset.incidentId,'low');t.setSort('newest');t.renderIncidents();assert.equal($('incidents-list').children[0].dataset.incidentId,'critical');
assert.ok(t.incidentOrder({...incidents[1],id:'newer',created_at:10}, {...incidents[1],id:'older',created_at:2}, 'priority') < 0);assert.equal($('incidents-list').children[0].querySelector('.incident-title').textContent,'Flooding');t.renderOperationsRail();assert.equal($('verification-desk').querySelector('.verification-guidance').tagName,'DETAILS');assert.equal($('verification-desk').querySelector('.verification-guidance').children[0].textContent,'How verification works');assert.equal($('arrival-chart').querySelector('.arrival-chart-summary').children[0].textContent,'1');assert.equal($('arrival-chart').querySelectorAll('.arrival-bucket').length,12);assert.match($('activity-feed').textContent,/SOS accepted by backend/);
t.openIncident('critical');assert.equal($('case-panel-overview').hidden,false);assert.equal($('case-panel-verification').hidden,true);t.selectCaseTab('verification');const notes=$('verification-notes');notes.value='Draft observation';notes.dispatch('input');t.selectCaseTab('overview');t.selectCaseTab('verification');assert.equal($('verification-notes'),notes);assert.equal(notes.value,'Draft observation');
incidents[1].processing_stages={...stages,verification:{status:'failed',error:'test validation error'}};t.maybeRefreshDialogs();assert.equal($('verification-notes'),notes);assert.match($('incident-pipeline').textContent,/failed/);
(async()=>{await $('case-tab-verification').dispatch('keydown',{key:'ArrowRight'});assert.equal($('case-tab-reports').attributes['aria-selected'],'true');assert.equal($('case-panel-reports').hidden,false);t.selectCaseTab('verification');$('verification-action').value='corroborated';await $('verification-action').dispatch('change');assert.equal($('verification-reviewer').required,true);$('verification-reviewer').value='Test operator';$('verification-method').value='on_site';$('verification-reference').value='Synthetic observation log';$('verification-checked').value='2026-01-01T10:00';await $('verification-form').dispatch('submit');assert.equal(request.body.check_method,'on_site');assert.equal(request.body.reviewer_label,'Test operator');assert.equal(request.body.evidence_reference,'Synthetic observation log');assert.equal(typeof request.body.checked_at,'number');assert.equal($('verification-notes').value,'Draft observation');assert.match($('verification-action-error').textContent,/intentional isolated/);

let serverState={reports,incidents:structuredClone(incidents),correlations:[],audit:[],server_time:now,stats:{},ai:{}};
context.fetch=async(path,options={})=>{if(path==='/api/state')return{ok:true,json:async()=>serverState};const payload=JSON.parse(options.body);serverState.incidents=serverState.incidents.map(item=>{if(item.id!=='critical')return item;if(path.endsWith('/acknowledge'))return{...item,status:'acknowledged',acknowledged_at:now,acknowledged_report_ids:item.report_ids};if(path.endsWith('/verification'))return{...item,verification_status:'verification_requested',verification_notes:payload.notes,verification_updated_at:now};return{...item,...payload,team:'Persisted team'};});return{ok:true,json:async()=>serverState.incidents.find(item=>item.id==='critical')}};
t.setState(serverState);t.openIncident('critical','verification');t.discardCaseDraft();$('verification-notes').value='Unsaved independent-check notes';await $('verification-notes').dispatch('input');$('verification-reviewer').value='Draft reviewer';await $('verification-reviewer').dispatch('input');t.selectCaseTab('overview');$('operator-team').value='Team input before canonical response';await $('operator-team').dispatch('input');await $('incident-form').dispatch('submit');assert.equal($('verification-notes').value,'Unsaved independent-check notes');assert.equal($('verification-reviewer').value,'Draft reviewer');assert.equal($('operator-team').value,'Persisted team');
$('operator-team').value='Still-unsaved assignment';await $('operator-team').dispatch('input');$('operator-category').value='Still-unsaved category';await $('operator-category').dispatch('input');await $('incident-form').querySelector('[data-action="acknowledge-incident"]').dispatch('click');assert.equal($('operator-team').value,'Still-unsaved assignment');assert.equal($('operator-category').value,'Still-unsaved category');assert.equal($('verification-notes').value,'Unsaved independent-check notes');assert.equal($('verification-reviewer').value,'Draft reviewer');t.selectCaseTab('verification');await $('verification-form').dispatch('submit');assert.equal($('verification-notes').value,'');assert.equal($('verification-reviewer').value,'');assert.equal($('operator-team').value,'Still-unsaved assignment');assert.equal($('operator-category').value,'Still-unsaved category');console.log('PASS: successful assignment preserves unsaved verification fields, acknowledgement preserves both forms, verification save preserves unsaved assignment; successfully submitted fields use fresh server values rather than stale draft restoration.');
const manyReports=Array.from({length:100},(_,i)=>({...reports[0],id:`report-${i}`,incident_id:`case-${i}`,text:`Synthetic scale report ${i}`,received_at:now-i*1000}));
const manyIncidents=manyReports.map((r,i)=>({...incidents[1],id:`case-${i}`,report_ids:[r.id],created_at:now-i*1000}));
const pairs=Array.from({length:50},(_,i)=>({id:`pair-${i}`,incident_a_id:`case-${i*2}`,incident_b_id:`case-${i*2+1}`,created_at:now-i,status:'pending',confidence:.6,reason:`Synthetic same-incident comparison ${i}`,evidence:[]}));
t.setState({reports:manyReports,incidents:manyIncidents,correlations:pairs,server_time:now});t.setView('overview');assert.equal($('incidents-list').children.length,5);assert.equal($('reports-section').hidden,true);assert.equal($('workspace-grid').hidden,false);assert.match($('incident-pagination').textContent,/Page 1 of 20/);await $('incident-pagination').querySelector('[data-page="next"]').dispatch('click');assert.match($('incident-pagination').textContent,/Page 2 of 20/);t.setView('reports');assert.equal($('workspace-grid').hidden,true);assert.equal($('reports-section').hidden,false);assert.equal($('reports-list').querySelectorAll('.report-list-row').length,6);assert.equal($('reports-list').querySelectorAll('.report-card').length,0);t.setView('network');assert.equal($('system-section').hidden,false);assert.equal($('reports-section').hidden,true);
t.openCorrelations();assert.equal($('correlation-content').querySelectorAll('.correlation-pair-item').length,8);assert.equal($('correlation-content').querySelectorAll('.correlation-card').length,1);assert.equal($('correlation-content').querySelectorAll('.report-card').length,2);assert.equal($('correlation-content').querySelector('.correlation-sources').children.length,2);assert.ok($('correlation-content').querySelector('.correlation-actions'));assert.ok($('correlation-content').querySelector('.correlation-source-fields'));$('incidents-list').scrollTop=200;$('reports-list').scrollTop=300;$('system-section').scrollTop=400;t.setView('reports');assert.equal($('incidents-list').scrollTop,0);assert.equal($('reports-list').scrollTop,0);assert.equal($('system-section').scrollTop,0);assert.match($('correlation-list-pagination').textContent,/Page 1 of 7/);await $('correlation-list-pagination').querySelector('[data-page="next"]').dispatch('click');assert.match($('correlation-list-pagination').textContent,/Page 2 of 7/);assert.equal($('correlation-content').querySelectorAll('.correlation-card').length,1);assert.equal($('correlation-content').querySelector('.correlation-card').dataset.correlationId,'pair-8');$('incident-dialog').scrollTop=900;t.selectCaseTab('reports');assert.equal($('incident-dialog').scrollTop,0);t.observePaneCapacity();t.setView('overview');await $('incident-pagination').querySelector('[data-page="next"]').dispatch('click');const beforeResize=$('incidents-list').children[0].dataset.incidentId;$('incidents-list').clientHeight=344;t.fitPanePages();assert.equal($('incidents-list').children.length,4);assert.ok($('incidents-list').children.some(row=>row.dataset.incidentId===beforeResize));$('incidents-list').clientHeight=237;t.fitPanePages();assert.equal($('incidents-list').children.length,3);$('incidents-list').clientHeight=0;t.fitPanePages();assert.equal($('incidents-list').children.length,3);$('incidents-list').clientHeight=1400;t.fitPanePages();assert.equal($('incidents-list').children.length,10);t.setView('reports');$('reports-list').clientHeight=344;t.fitPanePages();assert.equal($('reports-list').children.length,4);t.renderReports();assert.equal($('reports-list').children.length,4);console.log('PASS: adaptive capacities 344px→4 rows, 237px→3 rows, zero-height ignored, max10; prior first item stays visible; reports fit4 and retain capacity across rerender; absent ResizeObserver tolerated.');console.log('PASS: 100-record views/pagination (5 incidents, 6 compact reports); 50 correlations create 8 summaries + 1 pair + 2 source cards only; page navigation and tab scroll reset.');

// New operator-task regressions: entirely isolated synthetic data, no live API calls.
t.setState({reports:manyReports,incidents:manyIncidents,correlations:pairs,server_time:now});
t.openIncident('case-0','verification');
$('verification-notes').value='Case zero unsaved check';await $('verification-notes').dispatch('input');
$('operator-team').value='Case zero unsaved assignment';await $('operator-team').dispatch('input');
assert.equal($('case-draft-notice').hidden,false);
t.closeIncidentWorkspace();t.openIncident('case-1');assert.equal($('operator-team').value,'');
$('operator-team').value='Case one separate assignment';await $('operator-team').dispatch('input');
t.closeIncidentWorkspace();t.openIncident('case-0','verification');
assert.equal($('verification-notes').value,'Case zero unsaved check');assert.equal($('operator-team').value,'Case zero unsaved assignment');
assert.match($('case-draft-notice').textContent,/Unsaved changes/);
await $('case-draft-notice').querySelector('.discard-draft').dispatch('click');
assert.equal($('verification-notes').value,'');assert.equal($('operator-team').value,'');assert.equal($('case-draft-notice').hidden,true);
t.closeIncidentWorkspace();t.openIncident('case-1');assert.equal($('operator-team').value,'Case one separate assignment');
assert.ok($('case-panel-overview').querySelector('.case-overview-actions'));assert.ok($('case-panel-overview').querySelector('.case-overview-evidence'));
assert.equal($('case-panel-verification').querySelector('.verification-workflow-help').tagName,'DETAILS');
t.setView('verification');await $('incidents-list').querySelector('[data-action="view-incident"]').dispatch('click');
assert.equal($('case-panel-verification').hidden,false);
const queryReport=(query)=>{$('report-search').value=query;t.renderReports();};
queryReport('report-99');assert.equal($('reports-list').querySelectorAll('.report-list-row').length,1);assert.equal($('reports-list').children[0].dataset.reportId,'report-99');
queryReport('synthetic scale report 52');assert.equal($('reports-list').querySelectorAll('.report-list-row').length,1);
queryReport('rQm-X');assert.match($('report-pagination').textContent,/of 100/);
queryReport('no matching location');assert.match($('reports-list').textContent,/No matching reports/);
await $('reports-list').querySelector('button').dispatch('click');assert.equal($('report-search').value,'');assert.match($('report-pagination').textContent,/Page 1 of/);
$('incident-search').value='no matching case';t.renderIncidents();assert.match($('incidents-list').textContent,/No matching incidents/);
await $('incidents-list').querySelector('button').dispatch('click');assert.equal($('incident-search').value,'');
t.setView('overview');const jump=$('incident-pagination').querySelector('.page-jump');jump.value='4';await jump.dispatch('change');assert.match($('incident-pagination').textContent,/Page 4 of/);
t.openCorrelations();assert.ok($('correlation-content').querySelector('.pair-location'));assert.match($('correlation-content').querySelector('.pair-location').textContent,/Room 12/);
console.log('PASS: per-case memory drafts survive close/reopen and stay isolated; explicit discard restores recorded fields; task-specific verification entry; action/evidence layout; collapsed verification help; source report search/clear; incident clear; direct page jump; human-readable pair location.');

// New schema3 declarations remain visible without making them authenticated facts.
const quickReport={...reports[0],id:'quick-sos',schema_version:3,incident_id:'quick-case',text:'Help needed; details unavailable.',message_source:'preset',quick_needs:['cannot_speak','cannot_move'],created_at:now,room:null,people_affected:null,location_context:{source:'saved',observed_at:now-86400000,latitude:12.9716,longitude:77.5946,accuracy_m:200}};
const quickCase={...incidents[1],id:'quick-case',report_ids:[quickReport.id],triage:null};
t.setState({reports:[quickReport],incidents:[quickCase],server_time:now});
t.openIncident('quick-case','overview');
assert.match($('case-panel-overview').textContent,/Cannot speak/);assert.match($('case-panel-overview').textContent,/Cannot move/);
assert.match($('case-panel-overview').textContent,/24 hour\(s\) old at SOS/);assert.match($('case-panel-overview').textContent,/current position unconfirmed/);
assert.match($('case-panel-overview').textContent,/12\.9716, 77\.5946/);assert.match($('case-panel-overview').textContent,/Reported ±200 m/);
t.selectCaseTab('reports');assert.match($('case-panel-reports').textContent,/APP DEFAULT TEXT \/ NO TYPED MESSAGE/);
t.setView('reports');queryReport('cannot speak');assert.equal($('reports-list').querySelectorAll('.report-list-row').length,1);assert.match($('reports-list').textContent,/No typed message/);
quickReport.location_context={source:'unknown',observed_at:null,latitude:null,longitude:null,accuracy_m:null};
t.setState({reports:[quickReport],incidents:[quickCase],server_time:now});t.openIncident('quick-case','overview');
assert.match($('case-panel-overview').textContent,/Unknown · no position attached/);assert.doesNotMatch($('case-panel-overview').textContent,/12\.9716/);
console.log('PASS: quick SOS preset is labeled; selected needs visible and searchable; saved location preserves original observation age and accuracy; unknown position stays unknown.');

// Deferred network responses exercise session boundaries without a real server.
const defer=()=>{let resolve;const promise=new Promise(done=>{resolve=done});return{promise,resolve}};
const response=(data,status=200)=>({ok:status>=200&&status<300,status,json:async()=>data});
const turn=()=>new Promise(done=>setImmediate(done));
const staleWorkspace={reports:manyReports,incidents:manyIncidents,correlations:pairs,audit:[],server_time:now,account:{name:'previous.operator',role:'responder'}};
const newWorkspace={reports:[quickReport],incidents:[quickCase],correlations:[],audit:[],server_time:now,account:{name:'new.viewer',role:'viewer'}};
$('report-search').value='';$('incident-search').value='';document.activeElement=null;
t.clearWorkspace();t.setState(staleWorkspace);
let delayed=defer();context.fetch=()=>delayed.promise;
let waiting=t.refresh({force:true});
t.clearWorkspace();delayed.resolve(response(staleWorkspace));await waiting;
assert.equal(t.snapshot().state.reports.length,0);
assert.doesNotMatch($('reports-list').textContent,/Synthetic scale report/);

// An old unauthorized poll must not clear or disconnect a newer login.
t.setState(staleWorkspace);delayed=defer();
context.fetch=async(path)=>path==='/api/state'?delayed.promise:path==='/api/auth/config'?response({mode:'accounts'}):response({name:'new.viewer',role:'viewer'});
waiting=t.refresh({force:true});t.clearWorkspace();
const beforeLogin=t.snapshot().authEpoch;
await t.enterKey();
const auth=document.querySelectorAll('.auth-dialog').at(-1);
auth.querySelector('input[name="username"]').value='new.viewer';
auth.querySelector('input[name="api_key"]').value='synthetic-test-password';
await auth.querySelector('form').dispatch('submit');
assert.ok(t.snapshot().authEpoch>beforeLogin,'successful login changes the auth generation');
// Represents a workspace accepted from the new session; the old response arrives last.
t.setState(newWorkspace);t.renderIncidents();t.renderReports();
delayed.resolve(response({detail:'old session expired'},401));await waiting;
assert.equal(t.snapshot().state.reports[0].id,quickReport.id);
assert.equal(t.snapshot().account.name,'new.viewer');assert.equal(t.snapshot().connected,true);

// A decision already authorized on the server can finish after local sign-out.
t.clearWorkspace();t.setState(staleWorkspace);t.openIncident('case-0');
delayed=defer();let unexpectedRefresh=0;
context.fetch=async(path)=>{if(path==='/api/state'){unexpectedRefresh++;return response(staleWorkspace)}return delayed.promise};
waiting=$('incident-form').dispatch('submit');t.clearWorkspace();
delayed.resolve(response({...manyIncidents[0],team:'old request result'}));await waiting;
assert.equal(t.snapshot().state.incidents.length,0);assert.equal(unexpectedRefresh,0);

// Cover both the correlation POST and its independent state-fetch continuation.
t.setState(staleWorkspace);t.openCorrelations();delayed=defer();unexpectedRefresh=0;
context.fetch=async(path)=>{if(path==='/api/state'){unexpectedRefresh++;return response(staleWorkspace)}return delayed.promise};
waiting=$('correlation-content').querySelector('[data-action="confirm-correlation"]').dispatch('click');
t.clearWorkspace();delayed.resolve(response({correlation:{...pairs[0],status:'confirmed'}}));await waiting;
assert.equal(t.snapshot().state.reports.length,0);assert.equal(unexpectedRefresh,0);
for(const lateStatus of [200,401]){
 t.setState(staleWorkspace);t.openCorrelations();delayed=defer();let stateRequested=false;
 context.fetch=async(path)=>{if(path==='/api/state'){stateRequested=true;return delayed.promise}return response({correlation:{...pairs[0],status:'rejected'}})};
 waiting=$('correlation-content').querySelector('[data-action="reject-correlation"]').dispatch('click');
 await turn();assert.equal(stateRequested,true);
 t.clearWorkspace();if(lateStatus===401)t.setState(newWorkspace);
 delayed.resolve(response(lateStatus===200?staleWorkspace:{detail:'old session expired'},lateStatus));await waiting;
 assert.equal(t.snapshot().state.reports.length,lateStatus===200?0:1);
 if(lateStatus===401)assert.equal(t.snapshot().state.reports[0].id,quickReport.id);
 assert.equal(t.snapshot().correlationDecisionPending,false);assert.equal(t.snapshot().correlationRefreshRequired,false);
 assert.equal($('correlation-content').children.length,0);
}
console.log('PASS: deferred pre-logout poll/decision responses cannot restore a cleared workspace; stale 401 cannot clear/disconnect a newer login; correlation follow-up state success/error cannot restore old reports or lock a new session.');

console.log('PASS: urgency/newest/oldest queue; six cells; 12 real arrival buckets/window; audit labels; tab visibility+keyboard; dirty values survive tabs/poll; live pipeline; structured verification payload and API error retention.');})().catch(e=>{console.error(e);process.exitCode=1});
