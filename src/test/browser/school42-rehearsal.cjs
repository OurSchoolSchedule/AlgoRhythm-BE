// Disposable Spring server, real frontend build and virtual platform passkeys.
// Email delivery is captured by the test harness. Never send requests to the preview host.
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const { setTimeout: delay } = require('node:timers/promises')
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')

const schoolId = 540001
const termId = 547001
const origin = 'https://algo-rhythm-git-fe-develop-sihyes-projects.vercel.app'
const dates = ['2026-11-23', '2026-11-24', '2026-11-25', '2026-11-26', '2026-11-27']
const roles = {
  operator: { email: 'eceun@ewha.ac.kr' },
  admin: { email: 'zieyou52@ewha.ac.kr' },
  teacher: { email: 'reslc0307@gmail.com', name: '최도현', subject: '공통국어2', teachingId: 542004 },
  responder: { email: 'aaaalgorythm@gmail.com', name: '강태영', subject: '공통국어2', teachingId: 542006 },
}
const base = '/api/v2/terms/' + termId
const rosterPath = '/api/schools/' + schoolId + '/roster'
const minePath = '/api/timetable/me/year/2026/semester/2?weekOf=' + dates[0] + '&termId=' + termId
const memberships = '/api/mypage/teacher/schools'
const [, , port, mailbox, pin] = process.argv
assert.match(port || '', /^\d{1,5}$/)
const api = 'http://127.0.0.1:' + port
const dist = path.resolve(process.env.PASSKEY_FRONTEND_DIST)
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.webp': 'image/webp',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json' }

async function request(url, token, body, method = 'GET', expected = 200, headers = {}) {
  assert.ok(url.startsWith('/api/') && !url.includes('://'))
  const isForm = body instanceof FormData
  const res = await fetch(api + url, { method, redirect: 'error', headers: {
    ...(body != null && !isForm && { 'Content-Type': 'application/json' }),
    ...(token && { Authorization: 'Bearer ' + token }), ...headers,
  }, body: body == null ? undefined : isForm ? body : JSON.stringify(body) })
  const result = res.headers.get('content-type')?.includes('json') ? await res.json() : null
  if (!(Array.isArray(expected) ? expected : [expected]).includes(res.status)) {
    throw Error(method + ' ' + url.split('?')[0] + ' HTTP ' + res.status + ' ' + (result?.code ?? ''))
  }
  return result
}

async function waitFile(name) {
  for (let i = 0; i < 240; i++) {
    try {
      const value = (await fs.readFile(path.join(mailbox, name), 'utf8')).trim()
      if (value) return value
    } catch (error) {
      if (error.code !== 'ENOENT') throw error
    }
    await delay(250)
  }
  throw Error('Local fixture handshake did not finish')
}

// Screenshots use synthetic sessions. Mask the join code, visible emails and authentication fields.
function evidenceWriter(schoolCode) {
  const folder = process.env.SCHOOL42_UI_EVIDENCE
  if (!folder) return null
  return async (page, name) => {
    const masks = [page.getByText(schoolCode, { exact: true }), page.locator('input[type=password], input[type=email], input[autocomplete=one-time-code], input[name=schoolCode]'),
      page.getByRole('combobox', { name: /가입 신청자/ }), page.getByText(/인증 이메일 ·/)]
    for (const { email } of Object.values(roles)) masks.push(page.getByText(email, { exact: false }))
    await fs.mkdir(folder, { recursive: true })
    await page.screenshot({ path: path.join(folder, name + '.png'), animations: 'disabled', mask: masks, maskColor: '#66717c' })
  }
}

let closingBrowser = false
async function device(browser, role) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'ko-KR', timezoneId: 'Asia/Seoul' })
  async function serveLocal(route) {
    const url = new URL(route.request().url())
    if (url.origin !== origin) return route.abort()
    if (url.pathname.startsWith('/api/')) {
      const response = await route.fetch({ url: api + url.pathname + url.search, maxRedirects: 0 })
      return route.fulfill({ response })
    }
    const file = path.resolve(dist, '.' + (url.pathname === '/' ? '/index.html' : url.pathname))
    if (!file.startsWith(dist + path.sep)) return route.abort()
    try { return await route.fulfill({ body: await fs.readFile(file), contentType: mime[path.extname(file)] || 'application/octet-stream' }) }
    catch { return route.fulfill({ status: 404, body: '' }) }
  }

  await context.route('**/*', async route => {
    try {
      if (closingBrowser) return await route.abort()
      await serveLocal(route)
    } catch (error) {
      // The final login starts background reads. Chromium may close during a loopback response.
      if (!closingBrowser) throw error
    }
  })
  const page = await context.newPage()
  // The fixture has two terms; keep the browser's initial week and term deterministic on later dates.
  await page.clock.setFixedTime(new Date('2026-11-26T09:00:00+09:00'))
  page.setDefaultTimeout(15000)
  const cdp = await context.newCDPSession(page)
  await cdp.send('WebAuthn.enable')
  await cdp.send('WebAuthn.addVirtualAuthenticator', { options: {
    protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true,
    isUserVerified: true, automaticPresenceSimulation: true,
  } })
  await page.goto(origin)
  await page.getByRole('textbox', { name: '이메일', exact: true }).fill(role.email)
  await page.getByRole('button', { name: '인증 코드 받기', exact: true }).click()
  await page.getByRole('textbox', { name: '인증 코드', exact: true }).fill(await waitFile(role.email))
  await page.getByRole('button', { name: '인증하기', exact: true }).click()
  const finish = page.waitForResponse(r => r.url().endsWith('/registration/finish'))
  await page.getByRole('button', { name: '새 패스키 발급', exact: true }).click()
  const response = await finish
  assert.equal(response.status(), 200, 'passkey enrollment')
  const { userId, accessToken } = await response.json()
  assert.ok(userId && accessToken)
  await page.waitForFunction(() => Boolean(localStorage.getItem('accessToken')))
  return { page, ...role, userId, token: accessToken }
}

async function loginAgain(account) {
  await request('/api/auth/logout', account.token, null, 'POST')
  await account.page.evaluate(() => { localStorage.clear(); sessionStorage.clear() })
  await account.page.reload()
  const finish = account.page.waitForResponse(r => r.url().endsWith('/authentication/finish'))
  await account.page.getByRole('button', { name: '패스키로 로그인', exact: true }).click()
  const res = await finish
  assert.equal(res.status(), 200, 'passkey login')
  const session = await res.json()
  assert.equal(session.userId, account.userId)
  account.token = session.accessToken
  await account.page.waitForFunction(() => Boolean(localStorage.getItem('accessToken')))
}

let step = 'initial registration'
async function joinAndApprove(account, admin, schoolCode, capture, rejectFirst = false) {
  const { page, teachingId } = account
  step = 'teacher joins existing school'
  assert.equal((await request(memberships, account.token)).some(m => m.schoolId === schoolId), false)
  await page.getByLabel('학교 코드', { exact: true }).first().fill(schoolCode)
  await page.getByLabel('본인 이름', { exact: true }).first().fill(account.name)
  await page.getByLabel(/담당 과목 \(선택\)/).first().fill(account.subject)
  await page.getByRole('button', { name: '학교·이름 확인', exact: true }).first().click()
  const posted = page.waitForResponse(r => r.request().method() === 'POST' &&
    (r.url().endsWith('/onboarding') || r.url().endsWith(memberships)))
  await page.getByRole('button', { name: '학교 가입 요청', exact: true }).first().click()
  const join = await posted
  assert.equal(join.status(), join.url().endsWith(memberships) ? 202 : 200, 'teacher join')
  assert.equal((await join.json()).employmentStatus, 'PENDING')
  await request(base + '/lessons?date=' + dates[0], account.token, null, 'GET', [403, 404])
  const pending = (await request(rosterPath, admin.token)).filter(r => r.authEmail === account.email && r.status === 'PENDING')
  assert.equal(pending.length, 1)
  const entry = pending[0]
  step = 'open admin join review'
  const onReviewResponse = r => {
    const url = new URL(r.url())
    if (url.pathname.startsWith(rosterPath) || url.pathname === '/api/v2/terms')
      console.log('Admin review', r.request().method(), url.pathname, r.status())
  }
  admin.page.on('response', onReviewResponse)
  await admin.page.reload()
  await admin.page.getByRole('button', { name: '관리자 도구', exact: true }).first().click()
  await admin.page.getByRole('tab', { name: '가입 승인', exact: true }).click()
  step = 'select pending applicant'
  await admin.page.getByRole('combobox', { name: /가입 신청자/ }).selectOption(String(entry.rosterId))
  const approve = admin.page.getByRole('button', { name: '명부 연결 및 가입 승인', exact: true })
  await approve.waitFor()
  assert.ok(await approve.isDisabled(), 'manual choice required')
  const options = await request(rosterPath + '/' + entry.rosterId + '/match-options?termId=' + termId, admin.token)
  assert.equal(options.verifiedEmail, account.email)
  assert.equal(options.subjectHint, account.subject)
  const selected = options.candidates.find(c => c.teacherSchoolUserId === teachingId)
  assert.equal(selected?.linked, false, 'teaching identity is available')
  assert.equal(selected.displayName, account.name)
  if (selected.weeklyPeriods > 0) assert.ok(selected.subjects.includes(account.subject), 'subject hint matches published loads')
  if (rejectFirst) {
    const reject = admin.page.getByRole('button', { name: '가입 거절', exact: true })
    admin.page.once('dialog', dialog => dialog.dismiss())
    await reject.click()
    assert.equal((await request(rosterPath + '/me', account.token)).status, 'PENDING')
    admin.page.once('dialog', dialog => dialog.accept())
    const result = admin.page.waitForResponse(r => r.url().endsWith('/' + entry.rosterId + '/reject'))
    await reject.click()
    assert.equal((await result).status(), 204)
    await admin.page.getByText(account.name + ' 가입 거절 완료', { exact: true }).waitFor()
    admin.page.off('response', onReviewResponse)
    await loginAgain(account)
    return joinAndApprove(account, admin, schoolCode, capture)
  }
  step = 'select verified teaching candidate'
  await admin.page.getByRole('combobox', { name: /연결할 기존 교사 명부/ }).selectOption(String(teachingId))
  if (capture) await capture(admin.page, 'admin-roster-match')
  const approved = admin.page.waitForResponse(r => r.url().endsWith('/' + entry.rosterId + '/approve'))
  step = 'approve selected teaching identity'
  await approve.click()
  const response = await approved
  assert.equal(response.status(), 200)
  const linking = await response.json()
  assert.equal(linking.teachingSchoolUserId, teachingId)
  assert.equal(linking.employmentStatus, 'HIRED')
  const own = await request(rosterPath + '/me', account.token)
  assert.equal(own.teachingSchoolUserId, teachingId)
  assert.equal(own.authEmail, account.email)
  admin.page.off('response', onReviewResponse)
  step = 'open linked teacher home'
  await page.reload()
  const review = page.getByRole('button', { name: '확인하고 업무 홈으로', exact: true })
  await review.click()
}

async function generateAndPublish(token) {
  step = 'validate, generate and publish'
  async function upload(file, expected) {
    const body = new FormData()
    body.append('file', new Blob([await fs.readFile(path.join(dist, 'school42-files', file))]), file)
    return request(base + '/load-imports/validate?forGeneration=true', token, body, 'POST', expected)
  }
  const invalid = await upload('wrong_total_42.xlsx', 422)
  assert.ok(invalid.fieldErrors.length, 'malformed sheet must have concrete field errors')
  const load = await upload('teaching_load_42.xlsx', 200)
  assert.equal(load.totalPeriods, 672)
  assert.equal(load.demands.length, 252)
  const settings = JSON.parse(await fs.readFile(path.join(dist, 'school42-files/generate_request.json')))
  const job = await request(base + '/generations', token, { ...settings, validatedLoadId: load.validatedLoadId }, 'POST', 202)
  const until = Date.now() + 100000
  for (;;) {
    const done = await request(job.statusUrl, token)
    if (['QUEUED', 'RUNNING'].includes(done.state)) {
      assert.ok(Date.now() < until, 'generation timeout'); await delay(400); continue
    }
    assert.equal(done.state, 'READY')
    const candidates = done.result.candidates
    assert.ok(candidates.length >= 1 && candidates.length <= 4)
    assert.ok(candidates.every(c => c.placements.length === 672))
    const published = await request(base + '/publications', token, {
      jobId: job.jobId, candidateId: candidates[0].candidateId, effectiveFrom: dates[0], expectedPublishRev: job.expectedPublishRev,
    }, 'POST', 201)
    assert.equal(published.weeklyLessons, 672)
    console.log('Published', candidates.length, 'candidate(s),', published.weeklyLessons, 'lessons')
    return
  }
}

async function week(token, teacherId) {
  const all = await Promise.all(dates.map(d => request(base + '/lessons?date=' + d, token)))
  return all.flat().filter(r => r.actual.teacherId === teacherId && !r.actual.cancelled)
}

async function reset(operator, round, capture) {
  step = 'operator preview and full reset'
  const page = operator.page
  await page.reload()
  await page.getByRole('button', { name: 'AI 도우미 열기' }).click()
  await page.getByPlaceholder('말로 하거나 명령을 입력하세요').fill('Algorhythm')
  await page.getByRole('button', { name: '명령 보내기' }).click()
  const dryRun = page.waitForResponse(r => r.url().includes('/api/demo-reset?mode=FULL'))
  await page.getByLabel('초기화 범위').selectOption('FULL')
  const before = await (await dryRun).json()
  assert.equal(before.schoolId, schoolId)
  assert.equal(before.termId, termId)
  assert.equal(before.round, round)
  assert.equal(before.mode, 'FULL')
  assert.ok(before.jobs > 0 && before.versions > 0 && before.requests > 0)
  assert.equal(before.applicant.teachingId, roles.teacher.teachingId)
  if (capture) await capture(page, 'operator-full-reset')
  await page.getByLabel('초기화를 진행하려면 비밀번호를 입력하세요').fill(pin)
  const action = page.waitForResponse(r => r.url().endsWith('/api/demo-reset') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '전체 시연 초기화', exact: true }).click()
  const response = await action
  assert.equal(response.status(), 200, 'scoped reset with fresh operator passkey')
  // Success reloads the page immediately; verify the committed server state independently.
  const after = await request('/api/demo-reset', operator.token)
  assert.equal(after.round, round + 1)
  for (const key of ['jobs', 'versions', 'requests', 'weeklyLessons', 'datedLessons']) assert.equal(after[key], 0)
}

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_EXECUTABLE || undefined })
  try {
    const accounts = []
    for (const role of Object.values(roles)) accounts.push(await device(browser, role))
    const [operator, admin, teacher, responder] = accounts
    assert.equal(new Set(accounts.map(a => a.userId)).size, 4)
    await fs.writeFile(path.join(mailbox, 'accounts-ready'), '')
    const schoolCode = await waitFile('roles-ready')
    for (const account of accounts) await loginAgain(account)
    assert.equal((await request('/api/mypage/active-school', operator.token)).schoolId, 740002)
    assert.equal((await request('/api/mypage/active-school', admin.token)).schoolId, schoolId)
    await joinAndApprove(responder, admin, schoolCode, null, true)
    const originalCredentials = await Promise.all(accounts.map(a => request('/api/auth/passkeys/credentials', a.token)))
    const initialRound = (await request('/api/demo-reset', operator.token)).round
    for (let i = 0; i < 3; i++) {
      const round = initialRound + i
      const capture = i === 0 ? evidenceWriter(schoolCode) : null
      console.log('Round', round, 'start')
      await generateAndPublish(admin.token)
      if (capture) {
        await admin.page.reload()
        await admin.page.getByLabel('수업 날짜', {exact:true}).fill(dates[0])
        const classSelector = admin.page.getByLabel('시간표 학급')
        await classSelector.selectOption({index:1})
        await admin.page.locator('.desk-week .desk-cell small').first().waitFor()
        await classSelector.scrollIntoViewIfNeeded()
        await capture(admin.page, 'published-class-week')
      }
      await joinAndApprove(teacher, admin, schoolCode, capture)
      const lessons = await week(teacher.token, teacher.teachingId)
      const responderLessons = await week(responder.token, responder.teachingId)
      assert.equal(lessons.length, 15)
      assert.equal((await request(minePath, teacher.token)).length, lessons.length)
      assert.ok(responderLessons.length > 0)
      assert.equal((await request(minePath, responder.token)).length, responderLessons.length)
      step = 'group cover and exchange approvals'
      const { rehearseChanges } = require('./rehearse-changes.cjs')
      const { lesson, coverLesson, swapLessons } = await rehearseChanges({ request, termId, adminToken: admin.token, teacherToken: teacher.token,
        responderToken: responder.token, teacherId: teacher.teachingId, responderId: responder.teachingId,
        lessons, responderLessons, round, capture, uiRound: i === 0,
        teacherPage: teacher.page, adminPage: admin.page, responderPage: responder.page })
      const [periods, rooms, subjects] = await Promise.all([
        request('/api/school-setting/periods', admin.token), request('/api/school/classes?academicYear=2026', admin.token),
        request('/api/school/subjects', admin.token),
      ])
      for (const [index, id] of [coverLesson, ...swapLessons].entries()) {
        const row = [...lessons, ...responderLessons].find(l => l.id === id)
        const room = rooms.find(r => r.id === row.classId)
        const subject = subjects.find(s => s.id === row.subjectId)
        const period = periods.find(p => p.id === row.actual.periodId)
        assert.ok(room && subject && period)
        console.log(['보결', '교환 요청', '교환 상대'][index], row.date, period.periodNumber + '교시',
          room.grade + '학년 ' + room.classNumber + '반', subject.name)
      }
      step = 'AI confirmed transaction'
      const { rehearseAI } = require('./rehearse-ai.cjs')
      await rehearseAI({ capture, page: teacher.page, date: lesson.date, teacherId: teacher.teachingId, termId,
        teacherName: responder.name, request, teacherToken: teacher.token, lessons: await week(teacher.token, teacher.teachingId) })
      for (const account of [admin, teacher, responder])
        await request('/api/demo-reset?mode=FULL', account.token, null, 'GET', 403)
      await reset(operator, round, capture)
      assert.equal((await request(memberships, teacher.token)).some(r => r.schoolId === schoolId), false)
      assert.equal((await request(rosterPath + '/me', responder.token)).teachingSchoolUserId, responder.teachingId)
      assert.equal((await request(base + '/lesson-changes', admin.token)).length, 0)
      assert.equal((await request(minePath, responder.token)).length, 0)
      for (let ix = 0; ix < accounts.length; ix++) {
        const account = accounts[ix]
        await loginAgain(account)
        const credentials = await request('/api/auth/passkeys/credentials', account.token)
        assert.ok(credentials.length === originalCredentials[ix].length &&
          credentials.every(c => originalCredentials[ix].some(prior => prior.id === c.id)), 'credential set retained')
      }
      console.log('Round', round, 'passed. Teacher 1 detached, teacher 2 retained, all four original keys signed in.')
    }
  } finally {
    closingBrowser = true
    await browser.close()
  }
}
main().catch(error => {
  // Do not print Playwright request objects, response bodies, codes, PIN or credentials.
  const message = String(error.message).split('Call log:')[0].slice(0, 650)
  const source = String(error.stack).split('\n').filter(line => /at .*\.cjs:\d+:\d+/.test(line)).slice(0, 3)
  console.error(step, message, source.join('\n'))
  process.exitCode = 1
})
