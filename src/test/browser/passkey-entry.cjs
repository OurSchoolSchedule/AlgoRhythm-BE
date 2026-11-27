// Actual FE build + Spring API, isolated DB/Redis and Chromium virtual authenticators.
const assert = require('node:assert/strict');
const { readFile, mkdir } = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

async function main() {
  const [, , port, mailbox, email, schoolId, schoolCode] = process.argv;
  const api = 'http://127.0.0.1:' + port;
  const origin = 'https://algo-rhythm-git-fe-develop-sihyes-projects.vercel.app';
  const dist = path.resolve(process.env.PASSKEY_FRONTEND_DIST);
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_EXECUTABLE || undefined });
  const calls = [];
  const pageErrors = [];
  const evidence = process.env.PASSKEY_UI_EVIDENCE;
  async function request(url, token, body, method = 'POST') {
    const res = await fetch(api + url, { method, headers: {
      'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}),
    }, body: body ? JSON.stringify(body) : undefined });
    assert.equal(res.status, 200, method + ' ' + url);
    return res.headers.get('content-type')?.includes('application/json') ? res.json() : res.text();
  }
  async function device(automaticPresenceSimulation = true) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    await context.route('**/*', async route => {
      const req = route.request();
      const url = new URL(req.url());
      if (url.origin !== origin) return route.abort();
      if (url.pathname.startsWith('/api/')) {
        calls.push({ path: url.pathname, body: req.postDataJSON() });
        const response = await route.fetch({ url: api + url.pathname + url.search });
        return route.fulfill({ response });
      }
      let filename = path.resolve(dist, '.' + decodeURIComponent(url.pathname));
      if (!filename.startsWith(dist + path.sep) || url.pathname === '/') filename = path.join(dist, 'index.html');
      const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.webp': 'image/webp', '.ico': 'image/x-icon' };
      try { await route.fulfill({ body: await readFile(filename), contentType: mime[path.extname(filename)] || 'application/octet-stream' }); }
      catch { await route.fulfill({ body: await readFile(path.join(dist, 'index.html')), contentType: 'text/html' }); }
    });
    const page = await context.newPage();
    page.on('pageerror', error => pageErrors.push(error.message));
    const cdp = await context.newCDPSession(page);
    await cdp.send('WebAuthn.enable');
    const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', { options: {
      protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true,
      isUserVerified: true, automaticPresenceSimulation,
    } });
    await page.goto(origin);
    return { context, page, cdp, authenticatorId };
  }
  async function emailStep(page, nextStep = 'AUTHENTICATE') {
    await page.getByRole('textbox', { name: '이메일', exact: true }).fill(email);
    await page.getByRole('button', { name: '인증 코드 받기', exact: true }).click();
    await page.getByRole('textbox', { name: '인증 코드', exact: true }).waitFor();
    await page.getByRole('textbox', { name: '인증 코드', exact: true }).fill(await readFile(mailbox, 'utf8'));
    const verified = page.waitForResponse(res => res.url().endsWith('/email-verification/verify'));
    await page.getByRole('button', { name: '인증하기', exact: true }).click();
    const proof = await (await verified).json();
    assert.equal(proof.nextStep, nextStep);
    assert.ok(!proof.accessToken && !proof.refreshToken);
    await page.getByRole('heading', { name: '이메일 인증 완료', exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '새 패스키 발급', exact: true }).isEnabled(), true);
    assert.equal(await page.getByLabel('기존 복구 코드', { exact: true }).count(), 0);
    assert.equal(await page.evaluate(() => localStorage.getItem('accessToken')), null);
    return proof;
  }
  async function enroll(page) {
    const finished = page.waitForResponse(res => res.url().endsWith('/registration/finish'));
    await page.getByRole('button', { name: '새 패스키 발급', exact: true }).click();
    const response = await finished;
    assert.equal(response.status(), 200);
    const session = await response.json();
    assert.equal(session.recoveryCode, null);
    await page.waitForFunction(() => Boolean(localStorage.getItem('accessToken')));
    assert.equal(await page.evaluate(() => localStorage.getItem('userId')), String(session.userId));
    return session;
  }
  try {
    const original = await device();
    const first = await emailStep(original.page, 'REGISTER');
    const initial = await enroll(original.page);
    assert.equal(initial.userId, first.userId);
    await original.page.getByRole('heading', { name: '학교 연결', exact: true }).waitFor();
    await request('/api/auth/onboarding', initial.accessToken, { role: 'TEACHER', schoolCode, displayName: '명부 교사' });
    const memberships = await request('/api/mypage/teacher/schools', initial.accessToken, null, 'GET');
    assert.ok(JSON.stringify(memberships).includes(schoolId));
    assert.equal((await request('/api/auth/passkeys/credentials', initial.accessToken, null, 'GET')).length, 1);
    await original.context.unrouteAll({ behavior: 'wait' });
    await original.context.close(); // Simulate losing access to every saved credential.

    const replacement = await device(false);
    const { page, cdp, authenticatorId } = replacement;
    const repeated = await emailStep(page);
    assert.equal(repeated.userId, initial.userId);
    assert.equal(repeated.newUser, false);
    if (evidence) {
      await mkdir(evidence, { recursive: true });
      for (const width of [1440, 768, 390]) {
        await page.setViewportSize({ width, height: 1000 });
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
        await page.screenshot({ path: path.join(evidence, 'email-reissue-' + width + '.png'), fullPage: true });
      }
    }
    const started = page.waitForResponse(res => res.url().endsWith('/registration/options'));
    await page.getByRole('button', { name: '새 패스키 발급', exact: true }).click();
    assert.equal((await started).status(), 200);
    await page.getByRole('button', { name: '인증 취소', exact: true }).click();
    await page.getByText('패스키 인증을 취소했습니다.', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '새 패스키 발급', exact: true }).isDisabled(), true);
    assert.equal(await page.evaluate(() => localStorage.getItem('accessToken')), null);
    assert.equal((await request('/api/auth/passkeys/credentials', initial.accessToken, null, 'GET')).length, 1);

    await cdp.send('WebAuthn.setAutomaticPresenceSimulation', { authenticatorId, enabled: true });
    await page.getByRole('button', { name: '이메일 입력으로 돌아가기', exact: true }).click();
    const fresh = await emailStep(page);
    assert.notEqual(fresh.flowToken, repeated.flowToken);
    const recovered = await enroll(page);
    assert.equal(recovered.userId, initial.userId);
    assert.equal((await request('/api/auth/passkeys/credentials', recovered.accessToken, null, 'GET')).length, 2);
    assert.deepEqual(await request('/api/mypage/teacher/schools', recovered.accessToken, null, 'GET'), memberships);
    await page.waitForFunction(() => !document.querySelector('.entry-auth-title'));
    assert.equal(await page.getByRole('heading', { name: '학교 연결', exact: true }).count(), 0);
    const credentials = (await cdp.send('WebAuthn.getCredentials', { authenticatorId })).credentials;
    assert.equal(credentials.length, 1);
    assert.equal(credentials[0].userName, email);

    await request('/api/auth/logout', recovered.accessToken);
    await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
    await page.reload();
    const emailCount = calls.filter(call => call.path.includes('/email-verification/')).length;
    const login = page.waitForResponse(res => res.url().endsWith('/authentication/finish'));
    await page.getByRole('button', { name: '패스키로 로그인', exact: true }).click();
    assert.equal((await (await login).json()).userId, initial.userId);
    await page.waitForFunction(() => Boolean(localStorage.getItem('accessToken')));
    assert.equal(calls.filter(call => call.path.includes('/email-verification/')).length, emailCount);
    assert.equal(calls.some(call => call.path.includes('/recovery/')), false);
    assert.equal(calls.filter(call => call.path.endsWith('/registration/finish')).length, 2);
    assert.ok(calls.filter(call => call.path.endsWith('/registration/finish')).every(call => call.body.includeRecoveryCode === false));
    assert.deepEqual(pageErrors, []);
    console.log('PASS actual FE/API: existing account + empty device; cancel/fresh email/reissue; same account/school; no recovery code; new-key login; desktop/tablet/mobile');
  } finally {
    for (const context of browser.contexts()) await context.unrouteAll({ behavior: 'wait' });
    await browser.close();
  }
}
main().catch(error => { console.error(error); process.exit(1); });
