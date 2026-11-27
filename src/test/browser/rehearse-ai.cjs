// Used only by the local School42 full-stack rehearsal. All principals are ordinary signed-in users.
const assert = require('node:assert/strict')

async function rehearseAI({ page, request, teacherToken, lessons, termId, teacherId, capture }) {
  const changesPath = '/api/v2/terms/' + termId + '/lesson-changes'
  const read = path => request(path, teacherToken)
  const before = await read(changesPath)
  const touched = new Set(before.flatMap(row => [row.firstId, row.secondId]))
  const own = lessons.filter(row => row.actual.teacherId === teacherId && !row.actual.cancelled && !touched.has(row.id))
  let first
  for (const row of own) {
    const candidates = await read(changesPath + '/' + row.id + '/candidates?kind=SUBSTITUTE&firstRev=' + row.rev)
    if (candidates.length) { first = row; break }
  }
  assert.ok(first, 'a fresh owned lesson with an available cover candidate is required')
  const dialog = page.getByRole('dialog', { name: 'AI 도우미' })
  if (!(await dialog.isVisible())) await page.getByRole('button', { name: 'AI 도우미 열기' }).click()
  const writePaths = []
  const observe = req => {
    if (req.method() === 'POST' && req.url().includes('/lesson-changes')) writePaths.push(new URL(req.url()).pathname)
  }
  page.on('request', observe)
  async function send(text) {
    await dialog.getByPlaceholder('말로 하거나 명령을 입력하세요').fill(text)
    const [response] = await Promise.all([
      page.waitForResponse(r => new URL(r.url()).pathname === '/api/voice-commands/preview'),
      dialog.getByRole('button', { name: '명령 보내기' }).click(),
    ])
    assert.equal(response.status(), 200, 'server must parse the teacher command')
    const body = await response.json()
    const draft = body.data || body
    assert.equal(draft.draftToken, null, 'an informational intent must never issue a write token')
    return draft.intentType
  }

  try {
    assert.equal(await send(first.date + ' 기준 이번 주 내 시간표를 알려줘'), 'MY_WEEK')
    await dialog.getByLabel('조회할 주간의 날짜').fill(first.date)
    await dialog.getByRole('listitem').first().waitFor()

    assert.equal(await send(first.date + '에 공강 교사를 찾아줘'), 'COVER_OPTIONS')
    await dialog.getByLabel('내 수업', { exact: true }).selectOption(first.id + '/' + first.rev)
    await dialog.getByRole('checkbox').first().check()
    const second = dialog.getByRole('checkbox').nth(1)
    if (await second.isVisible()) await second.check()
    await dialog.getByRole('button', { name: '요청 내용 확인' }).click()
    await dialog.getByRole('button', { name: '돌아가기' }).click()
    assert.deepEqual(writePaths, [])
    assert.equal((await read(changesPath)).length, before.length, 'opening/cancelling preview left the database untouched')
    await dialog.getByPlaceholder('말로 하거나 명령을 입력하세요').fill('선택한 교사들에게 보결 요청을 보내줘')
    await dialog.getByRole('button', { name: '명령 보내기' }).click()
    assert.deepEqual(writePaths, [])
    const [receipt] = await Promise.all([
      page.waitForResponse(r => new URL(r.url()).pathname === changesPath + '/substitutes' && r.request().method() === 'POST'),
      dialog.getByRole('button', { name: '확인하고 요청 보내기' }).click(),
    ])
    assert.equal(receipt.status(), 201, 'confirmed cover uses the actual dated-lesson API')
    const created = await receipt.json()
    assert.ok(created.length)
    await dialog.getByText(/접수 완료 · 응답 대기/).waitFor()
    if (capture) await capture(page, 'ai-receipt')
    assert.deepEqual(writePaths, [changesPath + '/substitutes'])
    const persisted = await read(changesPath)
    for (const row of created) {
      const dbRow = persisted.find(r => r.id === row.id)
      assert.ok(dbRow && dbRow.requester === teacherId && dbRow.firstId === first.id && dbRow.state === 'PENDING')
    }
    assert.equal(await send('내가 요청한 보결의 처리 상태를 알려줘'), 'CHANGE_STATUS')
    await dialog.getByText(new RegExp('#' + created[0].id + ' 보결.*응답 대기')).waitFor()
    // Find a different feasible pair for this particular generated timetable.
    let swapFirst, swapDate, allowed
    const dates = [...new Set(lessons.map(l => l.date))]
    for (const row of own.filter(row => row.id !== first.id)) {
      for (const date of dates) {
        const matches = await read(changesPath + '/' + row.id + '/candidates?kind=SWAP&firstRev=' + row.rev + '&date=' + date)
        if (matches.length) { swapFirst = row; swapDate = date; allowed = matches; break }
      }
      if (swapFirst) break
    }
    assert.ok(swapFirst, 'another owned lesson with a feasible exchange is required')
    assert.equal(await send(swapFirst.date + ' 교환 가능한 수업을 찾아줘'), 'SWAP_OPTIONS')
    await dialog.getByLabel('내 수업', { exact: true }).selectOption(swapFirst.id + '/' + swapFirst.rev)
    await dialog.getByLabel('상대 수업 날짜').fill(swapDate)
    await dialog.getByRole('radio').first().check()
    await dialog.getByRole('button', { name: '요청 내용 확인' }).click()
    assert.deepEqual(writePaths, [changesPath + '/substitutes'])
    const [swapResponse] = await Promise.all([
      page.waitForResponse(r => new URL(r.url()).pathname === changesPath && r.request().method() === 'POST'),
      dialog.getByRole('button', { name: '확인하고 요청 보내기' }).click(),
    ])
    assert.equal(swapResponse.status(), 201)
    const swap = await swapResponse.json()
    assert.ok(swap.kind === 'SWAP' && swap.firstId === swapFirst.id && allowed.includes(swap.secondId))
    assert.ok((await read(changesPath)).some(row => row.id === swap.id && row.state === 'PENDING'))
    assert.deepEqual(writePaths, [changesPath + '/substitutes', changesPath])
    console.log('AI rehearsal verified weekly lessons, cancelled preview, confirmed cover receipts ' +
      created.map(r => r.id).join(',') + ', request status and confirmed swap receipt ' + swap.id)
  } finally {
    page.off('request', observe)
    await dialog.getByRole('button', { name: '질문 닫기' }).click()
  }
}
module.exports = { rehearseAI }
