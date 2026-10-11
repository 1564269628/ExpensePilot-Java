#!/usr/bin/env node
/**
 * Browser-level acceptance test of the actual Spring Boot hosted frontend.
 * Uses real Groq and local MySQL/RocketMQ backends; no mocked HTTP responses.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const BASE = process.env.EXPENSEPILOT_BASE_URL || 'http://127.0.0.1:8080';
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+cmBsAAAAASUVORK5CYII=',
  'base64'
);
const log = text => console.log('BROWSER PASS: ' + text);

async function main() {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  page.setDefaultTimeout(25000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));

  try {
    await page.goto(BASE, { waitUntil: 'networkidle', timeout: 40000 });
    await page.locator('h1').first().waitFor();
    assert.match(await page.title(), /ExpensePilot/);
    await page.locator('#metrics .metric strong').first().waitFor();
    log('Homepage HTML, CSS and client-side JavaScript rendered in Chromium');

    // Exercise the real DOM forms, not just the REST endpoints.
    await page.locator('.nav-item[data-tab="documents"]').click();
    const doc = page.locator('#documentForm');
    await doc.locator('[name="sourceType"]').selectOption('DRIVE');
    await doc.locator('[name="category"]').selectOption('OTHER');
    await doc.locator('[name="fileName"]').fill('browser-smoke-document.pdf');
    await doc.locator('[name="city"]').fill('苏州');
    await doc.locator('[name="issuedOn"]').fill('2026-10-02');
    await doc.locator('[name="amount"]').fill('32.50');
    await doc.locator('button[type="submit"]').click();
    await page.locator('#documentList').getByText('browser-smoke-document.pdf').waitFor();
    log('Document reference entry form persisted and rendered');

    const upload = page.locator('#uploadForm');
    await upload.locator('input[name="file"]').setInputFiles({
      name: 'browser-smoke.png', mimeType: 'image/png', buffer: PNG
    });
    await upload.locator('input[name="city"]').fill('苏州');
    await upload.locator('select[name="category"]').selectOption('OTHER');
    await upload.locator('input[name="issuedOn"]').fill('2026-10-02');
    await upload.locator('input[name="amount"]').fill('13.00');
    await upload.locator('button[type="submit"]').click();
    await page.locator('#documentList').getByText('browser-smoke.png').waitFor();
    log('Multipart document upload via browser form persisted');

    await page.locator('.nav-item[data-tab="trips"]').click();
    const trip = page.locator('#tripForm');
    await trip.locator('[name="city"]').fill('无锡');
    await trip.locator('[name="purpose"]').fill('Browser UI smoke test');
    await trip.locator('[name="startDate"]').fill('2026-10-01');
    await trip.locator('[name="endDate"]').fill('2026-10-03');
    await trip.locator('button[type="submit"]').click();
    await page.locator('#tripList').getByText('Browser UI smoke test').waitFor();
    log('Travel record form persisted and rendered');

    await page.locator('.nav-item[data-tab="policies"]').click();
    const policy = page.locator('#policyForm');
    await policy.locator('[name="city"]').fill('无锡');
    await policy.locator('[name="category"]').selectOption('HOTEL');
    await policy.locator('[name="maxAmount"]').fill('499');
    await policy.locator('[name="description"]').fill('Browser smoke policy');
    await policy.locator('button[type="submit"]').click();
    await page.locator('#policyList').getByText('Browser smoke policy').waitFor();
    log('Local policy rule form persisted and rendered');

    await page.locator('.nav-item[data-tab="overview"]').click();
    await page.locator('#seedDemo').click();
    await page.locator('[data-panel="tasks"]:visible').waitFor();
    await page.locator('#requestText').inputValue().then(v =>
      assert.ok(v.includes('上海'), 'Demo data should prefill the natural-language request'));
    log('Demo seed button initialized task form');

    const [response] = await Promise.all([
      page.waitForResponse(r =>
        r.url().endsWith('/api/v1/expense-tasks') &&
        r.request().method() === 'POST', { timeout: 50000 }),
      page.locator('#taskForm button[type="submit"]').click()
    ]);
    assert.equal(response.status(), 202);
    const created = await response.json();
    assert.equal(typeof created.taskId, 'string',
                 '64-bit task IDs must remain strings in JSON');
    const taskId = created.taskId;
    assert.ok(taskId.length >= 16);
    await page.locator('#selectedTaskId').filter({ hasText: '#' + taskId }).waitFor();
    await page.locator('#taskList [data-task-id="' + taskId + '"]').waitFor();
    log('Natural-language task created and full 64-bit ID selected without rounding');

    await page.waitForFunction(() => {
      const status = document.getElementById('selectedTaskStatus')?.textContent?.trim();
      return ['WAITING_APPROVAL','WAITING_INPUT','WAITING_MATERIAL','SUCCEEDED'].includes(status);
    }, null, { timeout: 110000 });
    let status = (await page.locator('#selectedTaskStatus').textContent()).trim();
    assert.equal(status, 'WAITING_APPROVAL',
      'Complete demo data should produce a real submit approval: ' + status);
    log('Real Groq planner executed, Graph reached approval checkpoint');

    // One or more approvals may be shown during asynchronous checkpoint resume.
    for (let attempt = 0; attempt < 4 && status !== 'SUCCEEDED'; attempt++) {
      await page.locator('#taskActions button[data-decision="approve"]').waitFor({ timeout: 35000 });
      await page.locator('#taskActions button[data-decision="approve"]').click();
      await page.waitForTimeout(4000);
      status = (await page.locator('#selectedTaskStatus').textContent()).trim();
      if (status !== 'SUCCEEDED' && status !== 'WAITING_APPROVAL') {
        await page.waitForFunction(() => {
          const st = document.getElementById('selectedTaskStatus')?.textContent?.trim();
          return st === 'SUCCEEDED' || st === 'WAITING_APPROVAL';
        }, null, { timeout: 35000 });
        status = (await page.locator('#selectedTaskStatus').textContent()).trim();
      }
    }
    await page.waitForFunction(() =>
      document.getElementById('selectedTaskStatus')?.textContent?.trim() === 'SUCCEEDED',
      null, { timeout: 45000 });
    log('Actual browser approval button advanced Graph to successful submission');

    await page.locator('.nav-item[data-tab="results"]').click();
    await page.waitForFunction(() =>
      document.querySelectorAll('#submissionList tbody tr').length > 0,
      null, { timeout: 35000 });
    await page.waitForFunction(() =>
      document.querySelectorAll('#notificationList tbody tr').length > 0,
      null, { timeout: 110000 });
    log('Business number and RocketMQ notification displayed in results dashboard');

    fs.mkdirSync('artifacts', { recursive: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('.nav-item[data-tab="overview"]').click();
    await page.locator('.hero h1').waitFor();
    await page.screenshot({
      path: path.join('artifacts', 'expensepilot-mobile.png'),
      fullPage: true
    });
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.screenshot({
      path: path.join('artifacts', 'expensepilot-desktop.png'),
      fullPage: true
    });
    log('Responsive mobile and desktop layouts rendered in real Chromium');

    assert.deepEqual(errors, [], 'Uncaught browser errors: ' + errors.join('; '));
    console.log('BROWSER END-TO-END RESULT: PASS');
  } finally {
    await browser.close();
  }
}

main().catch(error => {
  console.error('BROWSER END-TO-END RESULT: FAIL', error.message);
  process.exitCode = 1;
});
