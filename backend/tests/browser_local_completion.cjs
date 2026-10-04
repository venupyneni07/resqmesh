/* Real browser + real isolated HTTP API; model data is explicitly synthetic. */
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const projectRoot = path.resolve(__dirname, '../..');
const output = path.join(projectRoot, 'artifacts/local-completion/browser');
fs.mkdirSync(output, {recursive: true});
(async () => {
  const browser = await chromium.launch({headless: true,
    ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ? {executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH} : {})});
  const page = await browser.newPage({viewport: {width: 1440, height: 1000}});
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    await page.goto(process.env.RESQMESH_BROWSER_TEST_URL || 'http://127.0.0.1:8011');
    await page.waitForLoadState('networkidle');
    fs.writeFileSync(path.join(output, 'initial-buttons.json'), JSON.stringify(await page.getByRole('button').allTextContents(), null, 2));
    await page.locator('#account-button').click();
    await page.getByLabel('Username', {exact: true}).fill('test-responder');
    await page.getByLabel('Password', {exact: true}).fill('synthetic-browser-password');
    await page.getByRole('button', {name: 'Connect', exact: true}).click();
    await page.getByText('Authenticated workspace', {exact: true}).waitFor();
    await page.locator('[data-action="view-incident"]').first().waitFor();
    await page.screenshot({path: path.join(output, '01-authenticated-workspace.png'), fullPage: true});
    await page.locator('[data-action="view-incident"]').first().click();
    await page.getByText('Media analysis · human review required', {exact: true}).waitFor();
    await page.getByRole('button', {name: 'Review AI result & original →', exact: true}).click();
    assert.equal(await page.locator('.media-analysis').evaluate(element => element.open), true);
    await page.getByText('<script>test text remains inert</script>', {exact: true}).waitFor();
    assert.equal(await page.locator('.media-transcript script').count(), 0);
    await page.screenshot({path: path.join(output, '02-media-review.png'), fullPage: true});
    await page.getByRole('button', {name: 'View photo', exact: true}).click();
    await page.locator('.media-player img').waitFor();
    await page.waitForFunction(() => document.querySelector('.media-player img')?.naturalWidth > 0);
    await page.locator('.media-viewer-review').getByText('SYNTHETIC TEST ONLY: sample media review', {exact: true}).waitFor();
    await page.screenshot({path: path.join(output, '03-original-photo.png'), fullPage: true});
    await page.locator('.media-dialog').getByRole('button', {name: 'Close', exact: true}).click();
    await page.getByRole('button', {name: 'Close incident detail', exact: true}).click();
    await page.locator('#account-button').click();
    await page.getByRole('button', {name: 'Sign in', exact: true}).waitFor();
    assert.equal(await page.getByText('SYNTHETIC TEST ONLY: sample media review', {exact: true}).count(), 0);
    await page.locator('#account-button').click();
    await page.getByLabel('Username', {exact: true}).fill('test-viewer');
    await page.getByLabel('Password', {exact: true}).fill('synthetic-browser-password');
    await page.getByRole('button', {name: 'Connect', exact: true}).click();
    await page.getByText('Read-only access', {exact: true}).waitFor();
    const denial = await page.evaluate(async () => {
      const state = await (await fetch('/api/state')).json();
      return (await fetch(`/api/incidents/${state.incidents[0].id}/acknowledge`, {method: 'POST'})).status;
    });
    assert.equal(denial, 403);
    await page.setViewportSize({width: 390, height: 844});
    await page.screenshot({path: path.join(output, '04-mobile-viewer.png'), fullPage: true});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false);
    assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({passed: true, account_roles: true, real_http: true, original_photo_decodes: true, transcript_inert: true, logout_clears_sensitive_dom: true, mobile_no_horizontal_overflow: true, js_errors: errors, model_fixture: 'TEST-ONLY-STUB'}, null, 2));
    console.log('PASS: real browser login/logout, role denial, AI media review, inert transcript, authenticated photo decoding, mobile fit. Synthetic fixture only.');
  } finally { await browser.close(); }
})().catch(error => {console.error(error); process.exitCode = 1;});
