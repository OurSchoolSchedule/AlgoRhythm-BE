// Driven by PasskeyBrowserTest. Browser pages are fulfilled locally, including the fixed HTTPS origin.
const assert = require('node:assert/strict');
const { readFile } = require('node:fs/promises');
const { randomUUID, createHash } = require('node:crypto');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

async function main() {
  const [, , port, mailbox, email, schoolId, schoolCode] = process.argv;
  const api = 'http://127.0.0.1:' + port;
  const origin = 'https://algo-rhythm-git-fe-develop-sihyes-projects.vercel.app';
  const browser = await chromium.launch({executablePath: process.env.CHROMIUM_EXECUTABLE || undefined});
  try {
    const context = await browser.newContext();
    await context.route('**/*', route => route.fulfill({contentType: 'text/html', body: '<title>Passkey test</title>'}));
    const page = await context.newPage();
    await page.goto(origin);
    const cdp = await context.newCDPSession(page);
    await cdp.send('WebAuthn.enable');
    async function device() {
      return (await cdp.send('WebAuthn.addVirtualAuthenticator', {options: {
        protocol:'ctap2', transport:'internal', hasResidentKey:true, hasUserVerification:true,
        isUserVerified:true, automaticPresenceSimulation:true
      }})).authenticatorId;
    }
    async function removeDevice(id) { await cdp.send('WebAuthn.removeVirtualAuthenticator', {authenticatorId:id}); }
    async function request(path, {body, token, method = 'POST', status = 200} = {}) {
      const res = await fetch(api + path, {method, headers: {
        'Content-Type':'application/json', ...(token ? {Authorization:'Bearer ' + token} : {})
      }, body: body ? JSON.stringify(body) : undefined});
      const data = await res.text();
      assert.equal(res.status, status, method + ' ' + path + ' expected ' + status + ', got ' + res.status);
      return data && JSON.parse(data);
    }
    const pk = (path, opt) => request('/api/auth/passkeys' + path, opt);
    async function otp(address=email, schoolCode) {
      await request('/api/auth/email-verification/send',{body:{email:address}});
      return request('/api/auth/email-verification/verify',{body:{email:address,schoolCode,code:await readFile(mailbox,'utf8')}});
    }
    async function response(options, type) {
      const credential = await page.evaluate(async ({publicKey,type}) => {
        publicKey = type === 'create' ? PublicKeyCredential.parseCreationOptionsFromJSON(publicKey)
                                      : PublicKeyCredential.parseRequestOptionsFromJSON(publicKey);
        return (await navigator.credentials[type]({publicKey,signal:AbortSignal.timeout(7000)})).toJSON();
      }, {publicKey:options.publicKey,type});
      return {ceremonyId:options.ceremonyId,credential};
    }
    const regFinish = (body,token,status=200) => pk('/registration/finish',{body,token,status});
    const authFinish = (body,token,status=200) => pk('/authentication/finish',{body,token,status});
    async function login() {
      return authFinish(await response(await pk('/authentication/options'),'get'));
    }
    async function approval(token,operation,credentialId) {
      const opts = await pk('/reauthentication/options',{token,body:{operation,credentialId}});
      return (await authFinish(await response(opts,'get'),token)).approvalToken;
    }
    async function usable(token,status=200) {
      await request('/api/mypage/teacher/schools',{method:'GET',token,status});
    }

    const firstDevice = await device();
    const first = await otp(email, schoolCode);
    const rosterPath = '/api/schools/' + schoolId + '/roster/me';
    assert.equal(first.schoolMembership.employmentStatus,'HIRED');
    await request(rosterPath,{method:'GET',token:first.flowToken,status:403});
    assert.equal(first.nextStep,'REGISTER');
    assert.ok(!first.accessToken && !first.refreshToken);
    await usable(first.flowToken,403);
    const pending = await pk('/registration/options',{body:first});
    assert.equal(pending.publicKey.rp.id, new URL(origin).hostname);
    assert.equal(pending.publicKey.authenticatorSelection.userVerification,'required');
    await pk('/registration/options',{body:first,status:409});
    const firstResponse = await response(pending,'create');
    const session = await regFinish(firstResponse);
    assert.ok(session.recoveryCode);
    await regFinish(firstResponse,undefined,409);
    const key1 = firstResponse.credential.id;
    const token = session.accessToken;
    await usable(token);
    assert.equal((await request(rosterPath,{method:'GET',token})).displayName,'명부 교사');
    await usable(session.refreshToken,403);
    const refreshed = await request('/api/auth/refresh-token',{token:session.refreshToken});
    await usable(refreshed.accessToken);
    await usable((await login()).accessToken);
    const repeat = await otp(email,'A2-UNKNOWN');
    assert.equal(repeat.nextStep,'AUTHENTICATE');
    assert.ok(!repeat.schoolMembership && !repeat.accessToken);
    await pk('/registration/options',{body:await otp(),status:403});
    await pk('/credentials',{method:'GET',status:403});
    assert.equal((await pk('/credentials',{method:'GET',token})).length,1);
    const del = await approval(token,'DELETE',key1);
    assert.equal((await pk('/credentials/'+key1,{method:'DELETE',token,body:{approvalToken:del},status:409})).code,'LAST_PASSKEY');
    await pk('/credentials/options',{token,body:{approvalToken:del},status:409});

    const valid = await response(await pk('/authentication/options'),'get');
    const other = await pk('/authentication/options');
    await authFinish({...valid,ceremonyId:other.ceremonyId},undefined,400);
    const altered = structuredClone(valid);
    const sig = Buffer.from(altered.credential.response.signature,'base64url');
    sig[sig.length-1] ^= 1;
    altered.credential.response.signature = sig.toString('base64url');
    await authFinish(altered,undefined,400);
    await authFinish(valid,undefined,409);

    // Authentic signatures for independent negative checks (browser origin, RP ID, UV).
    await page.goto(origin.replace('https://','https://child.'));
    const wrongOrigin = await response(await pk('/authentication/options'),'get');
    await authFinish(wrongOrigin,undefined,400);
    await page.goto(origin);
    const noUV = await pk('/authentication/options');
    await cdp.send('WebAuthn.setUserVerified',{authenticatorId:firstDevice,isUserVerified:false});
    noUV.publicKey.userVerification='discouraged';
    await authFinish(await response(noUV,'get'),undefined,400);
    await cdp.send('WebAuthn.setUserVerified',{authenticatorId:firstDevice,isUserVerified:true});

    const extra = await otp('a2-' + randomUUID() + '@ewha.ac.kr');
    const registration = await pk('/registration/options',{body:extra});
    const wrongRp = await response(registration,'create');
    // Keep the allowed origin and challenge intact; change only the RP hash in the none-attestation fixture.
    const attestation = Buffer.from(wrongRp.credential.response.attestationObject,'base64url');
    const rpHash = createHash('sha256').update(registration.publicKey.rp.id).digest();
    const offset = attestation.indexOf(rpHash);
    assert.notEqual(offset,-1);
    attestation[offset] ^= 1;
    wrongRp.credential.response.attestationObject = attestation.toString('base64url');
    await regFinish(wrongRp,undefined,400);

    const add = await approval(token,'ADD');
    const addOptions = await pk('/credentials/options',{token,body:{approvalToken:add}});
    await pk('/credentials/options',{token,body:{approvalToken:add},status:409});
    await removeDevice(firstDevice);
    const secondDevice = await device();
    const secondResponse = await response(addOptions,'create');
    const second = await regFinish(secondResponse,token);
    assert.equal((await pk('/credentials',{method:'GET',token:second.accessToken})).length,2);
    await usable((await login()).accessToken);
    const removeFirst = await approval(second.accessToken,'DELETE',key1);
    await pk('/credentials/'+key1,{method:'DELETE',token:second.accessToken,body:{approvalToken:removeFirst},status:204});
    await usable(token,403);
    await request('/api/auth/refresh-token',{token:session.refreshToken,status:401});
    await usable(second.accessToken);
    const rotate = await approval(second.accessToken,'ROTATE');
    const {recoveryCode} = await pk('/recovery-code',{token:second.accessToken,body:{approvalToken:rotate}});
    const recovery = async (code,status=200) => pk('/recovery/options',{body:{flowToken:(await otp()).flowToken,recoveryCode:code},status});
    await recovery(session.recoveryCode,400);
    await removeDevice(secondDevice);
    await device();
    const replacement = await regFinish(await response(await recovery(recoveryCode),'create'));
    await usable(replacement.accessToken);
    await usable(second.accessToken,403);
    await recovery(recoveryCode,400);
    assert.equal((await pk('/credentials',{method:'GET',token:replacement.accessToken})).length,1);
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error.message); process.exitCode=1; });
