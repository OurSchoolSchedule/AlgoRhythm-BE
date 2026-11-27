const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')

// Four profiles have already signed in, the second teacher has a roster link, and the term is published.
async function rehearseChanges({ request, adminToken, teacherToken, responderToken, termId,
  lessons, responderLessons, teacherId, responderId, uiRound, adminPage, teacherPage, responderPage, capture }) {
  const term = '/api/v2/terms/' + termId
  const changes = term + '/lesson-changes'
  const candidates = (row, kind, date) => request(
    changes + '/' + row.id + '/candidates?' + new URLSearchParams({
      firstRev: row.rev, kind, ...(date && { date }),
    }), teacherToken)
  const current = async (date, id) => {
    const row = (await request(term + '/lessons?date=' + date, teacherToken)).find(r => r.id === id)
    assert.ok(row, 'missing effective lesson ' + id + ' on ' + date)
    return row
  }
  const approve = (row, body) => request(changes + '/' + row.id + '/approval', adminToken,
    { expectedRequestRev: row.rev, expectedLessonRev: row.firstRev, ...body },
    'POST', 200, { 'Idempotency-Key': randomUUID() })

  async function openDesk(page) {
    await page.reload()
    await page.getByRole('button', { name: '보결·교환 업무', exact: true }).first().click()
    await page.getByRole('heading', { name: '변경 신청 목록' }).waitFor()
  }
  async function submit(page, path, action, status = 200) {
    const [response] = await Promise.all([
      page.waitForResponse(r => new URL(r.url()).pathname === path && r.request().method() === 'POST'),
      action.click(),
    ])
    assert.equal(response.status(), status, 'workflow button ' + path)
    return response.json()
  }
  async function selectSource(row) {
    await openDesk(teacherPage)
    await teacherPage.getByLabel('첫 수업 날짜').fill(row.date)
    await teacherPage.getByRole('combobox', { name: /내가 담당하는 수업/ }).selectOption(row.id + '/' + row.rev)
  }
  async function decide(page, row, accept, extraApproval) {
    if (!uiRound) return accept
      ? request(changes + '/' + row.id + '/response', responderToken,
          { accept: true, expectedRequestRev: row.rev }, 'POST')
      : approve(row, extraApproval)
    await openDesk(page)
    const label = row.kind === 'SUBSTITUTE' ? '보결' : '교환'
    const item = page.getByRole('listitem').filter({ has: page.getByText(label + ' #' + row.id, { exact: true }) })
    await item.getByRole('button', { name: '요청 상세', exact: true }).click()
    await item.getByText(/20\d\d-\d\d-\d\d.*교시/).last().waitFor()
    return submit(page, changes + '/' + row.id + (accept ? '/response' : '/approval'),
      item.getByRole('button', { name: accept ? '수락' : '승인 확정', exact: true }))
  }

  const own = lessons.filter(row => row.actual.teacherId === teacherId && !row.actual.cancelled)
  const partners = responderLessons.filter(row => row.actual.teacherId === responderId && !row.actual.cancelled)
  assert.ok(own.length && partners.length, 'both teachers need published lessons')
  let cover, extra
  for (const row of own) {
    const free = await candidates(row, 'SUBSTITUTE')
    if (!free.includes(responderId)) continue
    const other = free.find(id => id !== responderId && id !== teacherId)
    if (other) { cover = row; extra = other; break }
  }
  assert.ok(cover, 'no free cover pair for this publication')
  const payload = { firstId: cover.id, firstRev: cover.rev, substituteTeacherIds: [responderId, extra] }
  let group
  if (uiRound) {
    await selectSource(cover)
    const form = teacherPage.getByRole('form', { name: '보결 신청' })
    for (const id of payload.substituteTeacherIds) {
      await form.locator('input[type="checkbox"][value="' + id + '"]').check()
    }
    const displayed = await form.getByRole('checkbox').evaluateAll(
      boxes => boxes.map(box => Number(box.value)).sort((a, b) => a - b))
    assert.deepEqual(displayed, (await candidates(cover, 'SUBSTITUTE')).sort((a, b) => a - b))
    if (capture) await capture(teacherPage, 'teacher-group-cover')
    group = await submit(teacherPage, changes + '/substitutes',
      form.getByRole('button', { name: '선택한 교사 2명에게 보결 요청' }), 201)
  } else group = await request(changes + '/substitutes', teacherToken, payload, 'POST', 201)
  assert.deepEqual(group.map(r => r.responder), [responderId, extra])
  const duplicate = await request(changes + '/substitutes', teacherToken, payload, 'POST', 409)
  assert.equal(duplicate.code, 'DUPLICATE_REQUEST')
  const notifications = await request('/api/notifications', responderToken)
  assert.ok(notifications.some(n => n.type === 'SUBSTITUTE_REQUEST_INVITE' && n.message.endsWith('#' + group[0].id)))
  const accepted = await decide(responderPage, group[0], true)
  assert.equal(accepted.state, 'ACCEPTED')
  const siblings = await request(changes, adminToken)
  assert.equal(siblings.find(r => r.id === group[1].id)?.state, 'WITHDRAWN')
  assert.deepEqual((await current(cover.date, cover.id)).actual, cover.actual)
  const receipt = await decide(adminPage, accepted, false)
  assert.equal(receipt.lessons.length, 1)
  const covered = await current(cover.date, cover.id)
  assert.equal(covered.actual.teacherId, responderId)
  assert.deepEqual(covered.planned, cover.planned)

  let first, second
  for (const row of own.filter(r => r.id !== cover.id)) {
    const sameClass = partners.filter(r => r.classId === row.classId)
    for (const date of new Set(sameClass.map(r => r.date))) {
      const eligible = await candidates(row, 'SWAP', date)
      second = sameClass.find(r => r.date === date && eligible.includes(r.id))
      if (second) break
    }
    if (second) { first = row; break }
  }
  assert.ok(first && second, 'no feasible separate exchange for the chosen teachers')
  let swap
  if (uiRound) {
    await selectSource(first)
    const form = teacherPage.getByRole('form', { name: '교환 신청' })
    if (second.date !== first.date) {
      await form.getByRole('checkbox', { name: '다른 날짜의 수업 선택' }).check()
      await form.getByLabel('교환 상대 수업 날짜').fill(second.date)
    }
    const counterpart = form.getByRole('combobox', { name: /^상대 교사/ })
    await counterpart.selectOption(String(responderId))
    const eligibleIds = new Set(await candidates(first, 'SWAP', second.date))
    const day = await request(term + '/lessons?date=' + second.date, teacherToken)
    const eligibleTeachers = new Set(day.filter(row => eligibleIds.has(row.id)).map(row => row.actual.teacherId))
    const offeredTeachers = await counterpart.locator('option').evaluateAll(options =>
      options.filter(option => option.value).map(option => Number(option.value)))
    assert.deepEqual(new Set(offeredTeachers), eligibleTeachers)
    await form.getByRole('combobox', { name: /교환할 상대 수업/ }).selectOption(second.id + '/' + second.rev)
    if (capture) await capture(teacherPage, 'teacher-exchange-preview')
    swap = await submit(teacherPage, changes, form.getByRole('button', { name: '교환 신청', exact: true }), 201)
  } else swap = await request(changes, teacherToken, { kind: 'SWAP', firstId: first.id,
    firstRev: first.rev, secondId: second.id, secondRev: second.rev }, 'POST', 201)
  assert.equal(swap.responder, responderId)
  const swapAccepted = await decide(responderPage, swap, true)
  assert.equal(swapAccepted.state, 'ACCEPTED')
  const exchange = await decide(adminPage, swapAccepted, false, { expectedSecondRev: swap.secondRev })
  assert.equal(exchange.lessons.length, 2)
  const movedFirst = await current(second.date, first.id)
  const movedSecond = await current(first.date, second.id)
  assert.deepEqual(movedFirst.actual, { ...first.actual, periodId: second.actual.periodId })
  assert.deepEqual(movedSecond.actual, { ...second.actual, periodId: first.actual.periodId })
  return { coverId: accepted.id, swapId: swap.id, coverLesson: cover.id, swapLessons: [first.id, second.id],
    lesson: own.find(row => row.id !== cover.id && row.id !== first.id) }
}

module.exports = { rehearseChanges }
