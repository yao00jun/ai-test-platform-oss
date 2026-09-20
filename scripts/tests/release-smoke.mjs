import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { cp, mkdir, readFile, readdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// Real packaged application, disposable MySQL schemas and a local model protocol
// fixture. No test changes the user's development database or company settings.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const packageArgument = process.argv[2]
if (!packageArgument) throw new Error('Usage: node scripts/tests/release-smoke.mjs <release-directory>')
const id = randomUUID().replaceAll('-', '')
const evidence = path.join(root, '.runtime/release-acceptance', id)
const release = path.join(evidence, '发布 验收 包')
const instances = ['源 实例', '恢复 实例', '失败恢复 实例'].map(name => path.join(evidence, name))
const schemas = ['a', 'b', 'c'].map(suffix => `ai_test_release_${id}_${suffix}`)
const createdSchemas = []
const connection = JSON.parse(await readFile(path.join(root, '.runtime/mysql/connection.json'), 'utf8'))
assert.match(connection.username, /^[A-Za-z0-9_]+$/)
const mysql = path.join(connection.home, 'bin/mysql.exe')
const adminFile = path.join(root, '.runtime/mysql/admin.cnf')
const result = { startedAt: new Date().toISOString(), steps: [], model: 'local HTTP protocol fixture; not company model acceptance' }
const sessions = new Map()
let commandNumber = 0
await mkdir(evidence, { recursive: true })

function invariant(value, message) { if (!value) throw new Error(message) }
function hash(bytes) { return createHash('sha256').update(bytes).digest('hex') }
async function command(executable, args, { input, expected = 0, timeout = 360_000 } = {}) {
  const name = `command-${++commandNumber}.log`
  const environment = { ...process.env }
  delete environment.AI_TEST_MASTER_KEY
  delete environment.MYSQL_PWD
  return await new Promise((resolve, reject) => {
    const child = spawn(executable, args, { cwd: root, env: environment, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
    let output = ''
    const timer = setTimeout(() => { child.kill(); reject(new Error(`Command timed out; inspect ${name}`)) }, timeout)
    child.stdout.on('data', data => { output += data.toString() })
    child.stderr.on('data', data => { output += data.toString() })
    child.once('error', error => { clearTimeout(timer); reject(error) })
    child.once('exit', async code => {
      clearTimeout(timer)
      await writeFile(path.join(evidence, name), output)
      if ((expected === 0 && code !== 0) || (expected !== 0 && code === 0)) reject(new Error(`Unexpected command exit ${code}; inspect ${name}`))
      else resolve(output.trim())
    })
    child.stdin.end(input ?? '')
  })
}
async function sql(schema, query) {
  return command(mysql, [`--defaults-file=${adminFile}`, '--no-login-paths', '--batch', '--skip-column-names', '--raw', ...(schema ? [schema] : [])], { input: query })
}
async function script(name, instance, extra = [], expected = 0) {
  return command('pwsh.exe', ['-NoProfile', '-File', path.join(release, 'scripts/aitest.ps1'), name, '-InstanceDirectory', instance, ...extra], { expected })
}
async function response(base, route, method = 'GET', body) {
  const session = sessions.get(base)
  const headers = new Headers()
  if (session?.cookie) headers.set('Cookie', session.cookie)
  if (session?.csrf && !['GET', 'HEAD', 'OPTIONS'].includes(method)) headers.set('X-CSRF-TOKEN', session.csrf)
  const options = { method, headers, signal: AbortSignal.timeout(180_000) }
  if (body instanceof FormData) options.body = body
  else if (body instanceof URLSearchParams) { headers.set('Content-Type', 'application/x-www-form-urlencoded'); options.body = body.toString() }
  else if (body !== undefined) { headers.set('Content-Type', 'application/json'); options.body = JSON.stringify(body) }
  const reply = await fetch(base + route, options)
  if (session) for (const cookie of reply.headers.getSetCookie()) {
    if (cookie.startsWith('AI_TEST_SESSION=')) session.cookie = cookie.split(';')[0]
  }
  return reply
}
async function login(base, config) {
  assert.equal((await fetch(`${base}/api/projects`)).status, 401)
  const session = { cookie: '', csrf: '' }
  sessions.set(base, session)
  const anonymous = await json(base, '/api/auth/session')
  assert.equal(anonymous.enabled, true); assert.equal(anonymous.authenticated, false)
  session.csrf = anonymous.csrfToken
  const previousCookie = session.cookie
  await json(base, '/api/auth/login', 'POST', new URLSearchParams({ username: config.username, password: config.password }))
  const authenticated = await json(base, '/api/auth/session')
  assert.equal(authenticated.authenticated, true); assert.equal(authenticated.username, config.username)
  assert.notEqual(session.cookie, previousCookie)
  session.csrf = authenticated.csrfToken
}
async function logout(base) {
  const previousCookie = sessions.get(base).cookie
  assert.equal((await response(base, '/api/auth/logout', 'POST')).status, 204)
  sessions.delete(base)
  assert.equal((await fetch(`${base}/api/projects`, { headers: { Cookie: previousCookie } })).status, 401)
}
async function json(base, route, method = 'GET', body, status = 200) {
  const reply = await response(base, route, method, body)
  invariant(reply.status === status, `${method} ${route}: expected ${status}, received ${reply.status}: ${await (reply.status === status ? Promise.resolve('') : reply.text())}`)
  return reply.json()
}
async function bytes(base, route) {
  const reply = await response(base, route)
  invariant(reply.ok, `Download failed: ${route} (${reply.status})`)
  return Buffer.from(await reply.arrayBuffer())
}
async function stopped(base) {
  let reachable = false
  try { await fetch(`${base}/actuator/health`, { signal: AbortSignal.timeout(2000) }); reachable = true } catch { /* A stopped listener rejects connections. */ }
  invariant(!reachable, 'The managed application is still reachable after its stop/backup command')
}
async function finish(base, project, submission) {
  const deadline = Date.now() + 120_000
  while (Date.now() < deadline) {
    const job = await json(base, `/api/jobs/${submission.jobId}?projectId=${project}`)
    if (job.status === 'SUCCEEDED') return job
    invariant(!['FAILED', 'CANCELLED', 'INTERRUPTED', 'CONFLICT'].includes(job.status), `Job ended as ${job.status}: ${job.error ?? ''}`)
    await new Promise(resolve => setTimeout(resolve, 200))
  }
  throw new Error('Release acceptance job exceeded its deadline')
}
async function asset(base, project, type, name, data = {}, parentId) {
  return json(base, `/api/projects/${project}/assets`, 'POST', { type, name, data, ...(parentId ? { parentId } : {}) }, 201)
}
async function unusedPort() {
  const server = createServer()
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const port = server.address().port
  await new Promise(resolve => server.close(resolve))
  return port
}
function completed(name) { result.steps.push({ name, at: new Date().toISOString() }); console.log(name) }

const replies = []
let modelRequests = 0, businessRequests = 0
const modelKey = `release-fixture-${id}`
const fixture = createServer(async (request, reply) => {
  try {
    if (request.url === '/page') {
      reply.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
      reply.end('<!doctype html><html lang="zh"><meta charset="utf-8"><title>发行验收</title><body><h1 id="heading">发行验收页面</h1><label>备注<input aria-label="备注"></label></body></html>')
      return
    }
    if (request.url === '/business') {
      businessRequests++
      reply.writeHead(200, { 'Content-Type': 'application/json' }); reply.end('{"orderId":7,"status":"CREATED"}'); return
    }
    let body = ''; for await (const chunk of request) body += chunk
    if (request.url !== '/v1/chat/completions' || request.headers.authorization !== `Bearer ${modelKey}`) { reply.writeHead(401); reply.end(); return }
    modelRequests++
    const input = JSON.parse(body), content = replies.shift()
    if (content === undefined) { reply.writeHead(503); reply.end('{"error":{"message":"No queued release fixture response"}}'); return }
    if (!input.stream) {
      reply.writeHead(200, { 'Content-Type': 'application/json' })
      reply.end(JSON.stringify({ id: 'release', object: 'chat.completion', created: 1, model: 'release-fixture', choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }] }))
    } else {
      reply.writeHead(200, { 'Content-Type': 'text/event-stream' })
      reply.write(`data: ${JSON.stringify({ id: 'release', object: 'chat.completion.chunk', created: 1, model: 'release-fixture', choices: [{ index: 0, delta: { content } }] })}\n\n`)
      reply.end('data: {"id":"release","object":"chat.completion.chunk","created":1,"model":"release-fixture","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n')
    }
  } catch { if (!reply.headersSent) reply.writeHead(500); reply.end() }
})
await new Promise(resolve => fixture.listen(0, '127.0.0.1', resolve))
const site = `http://127.0.0.1:${fixture.address().port}`

try {
  await cp(path.resolve(packageArgument), release, { recursive: true, errorOnExist: true, force: false })
  const sourceDirectory = path.join(evidence, '业务 源码')
  await mkdir(sourceDirectory)
  await writeFile(path.join(sourceDirectory, 'Known.java'), 'package shop; class Known { int total() { return 7; } }')
  const template = JSON.parse(await readFile(path.join(release, 'config.example.json'), 'utf8'))
  const configs = []
  for (let index = 0; index < instances.length; index++) {
    invariant(/^ai_test_release_[a-f0-9]{32}_[abc]$/.test(schemas[index]), 'Invalid disposable schema identity')
    await sql('', `CREATE DATABASE \`${schemas[index]}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\nGRANT ALL ON \`${schemas[index]}\`.* TO '${connection.username}'@'localhost';\n`)
    createdSchemas.push(schemas[index])
    const config = structuredClone(template)
    config.security = { enabled: true, username: 'release-owner', password: `release-${id}`, sessionMinutes: 30, secureCookie: false }
    config.mysqlHome = connection.home; config.port = await unusedPort()
    config.database = { url: `jdbc:mysql://127.0.0.1:${connection.port}/${schemas[index]}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8`, username: connection.username, password: connection.password }
    config.paths = { storage: 'data', browsers: path.join(root, '.tools/playwright-1.62.0'), localFileRoots: [sourceDirectory] }
    await mkdir(instances[index]); await writeFile(path.join(instances[index], 'config.json'), JSON.stringify(config, null, 2)); configs.push(config)
  }
  await script('install-browsers', instances[0], ['-DryRun'])
  await script('check', instances[0])
  await script('start', instances[0])
  const base = `http://127.0.0.1:${configs[0].port}`
  for (const route of ['/', '/projects', '/cases', '/api-tests', '/scenarios', '/ui-tests', '/plans', '/bugs']) {
    invariant((await bytes(base, route)).toString().includes('id="app"'), `Packaged Vue route failed: ${route}`)
  }
  await login(base, configs[0].security)
  assert.equal((await response(base, '/api/no-such-route')).status, 404)
  assert.equal((await response(base, '/assets/not-present.js')).status, 404)
  completed('Packaged JAR started from a Chinese/space path; eight Vue routes and API/static 404s verified')

  const blocked = await fetch(`${base}/api/projects`, { method: 'POST', headers: {
    'Content-Type': 'application/json', Cookie: sessions.get(base).cookie,
  }, body: JSON.stringify({ name: 'Must not be created without CSRF' }) })
  assert.equal(blocked.status, 403); assert.equal((await blocked.json()).code, 'CSRF_INVALID')
  assert.equal((await json(base, '/api/projects')).length, 0)
  completed('Configured platform login, session rotation and CSRF write protection verified')

  const project = (await json(base, '/api/projects', 'POST', { name: '发行与恢复验收' }, 201)).id
  result.projectId = project
  await json(base, '/api/settings/model', 'PUT', { baseUrl: `${site}/v1`, apiKey: modelKey, modelName: 'release-fixture', temperature: 0.1, timeoutSeconds: 30 })
  const family = (await json(base, '/api/templates')).find(item => item.family === 'FUNCTIONAL')
  const variant = family.variants[0]
  const templateBytes = await bytes(base, `/api/templates/FUNCTIONAL?format=${variant.format}`)
  const form = new FormData(); form.set('type', variant.type); form.set('format', variant.importFormat); form.set('file', new Blob([templateBytes]), variant.filename)
  const preview = await json(base, `/api/projects/${project}/imports/preview`, 'POST', form)
  assert.equal(preview.errors.length, 0)
  const applied = await json(base, `/api/projects/${project}/imports/${preview.id}/apply`, 'POST', {})
  invariant(applied.createdCount > 0, 'Template did not create real assets')
  const untouched = await asset(base, project, 'FUNCTIONAL_CASE', '始终保留的人工兄弟项', { precondition: '人工原文' })
  replies.push('featureCaseStart\n## 发行 AI 用例\n### 前置条件\n初始条件\n### 测试步骤与预期结果\n| 步骤 | 预期 |\n| --- | --- |\n| 输入边界金额 | 拒绝负数 |\n### 备注\nP0\nfeatureCaseEnd')
  await finish(base, project, await json(base, '/api/ai/generate', 'POST', { projectId: project, type: 'FUNCTIONAL_CASE', instruction: '生成发行验收用例', idempotencyKey: 'release-generation' }, 202))
  let target = (await json(base, `/api/projects/${project}/assets?type=FUNCTIONAL_CASE&limit=100`)).items.find(item => item.name === '发行 AI 用例')
  invariant(target, 'Generated case was not persisted')
  let conversationId
  for (let round = 1; round <= 2; round++) {
    replies.push(JSON.stringify({ data: { precondition: `第 ${round} 轮反馈后的条件` } }))
    const submission = await json(base, '/api/ai/refine-item', 'POST', { projectId: project, targetType: 'FUNCTIONAL_CASE', targetId: target.id,
      baseVersion: target.version, targetFields: ['precondition'], feedback: `调整前置条件，第 ${round} 轮`, idempotencyKey: `release-local-${round}`, ...(conversationId ? { conversationId } : {}) }, 202)
    conversationId = submission.conversationId; await finish(base, project, submission)
    const next = await json(base, `/api/projects/${project}/assets/${target.id}`)
    assert.equal(next.id, target.id); assert.equal(next.data.precondition, `第 ${round} 轮反馈后的条件`); target = next
    assert.deepEqual(await json(base, `/api/projects/${project}/assets/${untouched.id}`), untouched)
  }
  const revisionsBefore = await sql(schemas[0], `SELECT COUNT(*) FROM asset_revision WHERE asset_id='${target.id}';`)
  const analysis = await json(base, `/api/projects/${project}/source-analyses`, 'POST', { backendRepoPath: sourceDirectory, idempotencyKey: 'release-source' })
  await finish(base, project, analysis)
  const sourceRoute = `/api/projects/${project}/source-analyses/${analysis.analysisId}/file?kind=BACKEND&path=Known.java&from=1&to=5`
  const sourceExcerpt = await json(base, sourceRoute)
  invariant(sourceExcerpt.content.includes('return 7'), 'Fixed source excerpt is unavailable')
  completed('Template import, actual model protocol generation, two local rounds, unchanged sibling and fixed source verified')

  const uploadBytes = Buffer.from('发行备份附件\n原始字节完整保留\n', 'utf8')
  const upload = new FormData(); upload.set('file', new Blob([uploadBytes], { type: 'text/plain' }), '原始 附件.txt')
  const file = await json(base, `/api/projects/${project}/files`, 'POST', upload)
  const database = await asset(base, project, 'DATABASE_SOURCE', '加密连接恢复验证', { jdbcUrl: configs[0].database.url, username: connection.username, password: connection.password, maxPoolSize: 2 })
  const api = await asset(base, project, 'API_CASE', '真实接口', { path: `${site}/business`, extractors: [{ variable: 'orderId', jsonpath: '$.orderId' }], assertions: [{ type: 'status', expected: 200 }] })
  const sqlAsset = await asset(base, project, 'SQL_VALIDATION', '实际 SQL 参数', { databaseSourceId: database.id, sql: 'SELECT :orderId AS order_id', assertions: [{ field: 'order_id', expected: 7 }] })
  const scenario = await asset(base, project, 'SCENARIO', 'HTTP 到 SQL')
  await asset(base, project, 'SCENARIO_STEP', '请求', { stepType: 'HTTP', targetId: api.id }, scenario.id)
  await asset(base, project, 'SCENARIO_STEP', '数据库验证', { stepType: 'SQL', targetId: sqlAsset.id }, scenario.id)
  const ui = await asset(base, project, 'UI_SCENARIO', '真实 Chromium', { baseUrl: site })
  await asset(base, project, 'UI_STEP', '访问页面', { action: 'navigate', url: '/page' }, ui.id)
  await asset(base, project, 'UI_STEP', '确认文字', { action: 'assertText', selector: '#heading', expected: '发行验收页面' }, ui.id)
  await asset(base, project, 'UI_STEP', '保存截图', { action: 'screenshot' }, ui.id)
  const plan = await asset(base, project, 'TEST_PLAN', '发行混合计划', { diagnoseFailures: false, concurrency: 2 })
  await asset(base, project, 'PLAN_ITEM', '接口 SQL', { targetId: scenario.id }, plan.id)
  await asset(base, project, 'PLAN_ITEM', '浏览器', { targetId: ui.id }, plan.id)
  const runSubmission = await json(base, `/api/projects/${project}/runs`, 'POST', { assetId: plan.id, idempotencyKey: 'release-run' }, 202)
  await finish(base, project, runSubmission)
  const runRoute = `/api/projects/${project}/runs/${runSubmission.runId}`
  const runBefore = await json(base, runRoute); assert.equal(runBefore.status, 'PASSED')
  const screenshotIds = runBefore.items.flatMap(item => item.steps).flatMap(step => step.result.artifactIds ?? [])
  invariant(screenshotIds.length > 0, 'Browser screenshot was not persisted')
  const screenshotBefore = await bytes(base, `/api/projects/${project}/files/${screenshotIds[0]}`)
  await writeFile(path.join(evidence, 'screenshot.png'), screenshotBefore)
  for (const format of ['json', 'html', 'pdf', 'zip']) {
    const report = await bytes(base, `${runRoute}/report?format=${format}`)
    invariant(report.length > 100, `Empty ${format} report`)
    if (format === 'pdf') invariant(report.subarray(0, 5).toString() === '%PDF-', 'PDF output was not a PDF')
    await writeFile(path.join(evidence, `report.${format}`), report)
  }
  completed('Packaged HTTP/SQL/Chromium mixed plan passed; screenshots and JSON/HTML/PDF/ZIP reports downloaded')

  replies.push('OK')
  await script('check', instances[0], ['-TestModel'])
  await logout(base)

  await script('backup', instances[0], ['-DestinationDirectory', path.join(evidence, '一致备份'), '-LeaveStopped'])
  await stopped(base)
  const backups = await readdir(path.join(evidence, '一致备份'))
  invariant(backups.length === 1, 'Expected one complete backup')
  const backup = path.join(evidence, '一致备份', backups[0])
  const manifestPath = path.join(backup, 'manifest.json')
  const originalManifest = await readFile(manifestPath)
  const configBackup = path.join(backup, 'configuration.json'), originalConfig = await readFile(configBackup)
  assert.deepEqual(JSON.parse(originalConfig).security, configs[0].security)
  await writeFile(configBackup, Buffer.concat([originalConfig, Buffer.from('tampered')]))
  await script('restore', instances[1], ['-BackupDirectory', backup], 1)
  assert.equal(await sql(schemas[1], 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();'), '0')
  await writeFile(configBackup, originalConfig)
  const badManifest = JSON.parse(originalManifest); badManifest.files[0].path = '../outside'
  await writeFile(manifestPath, JSON.stringify(badManifest))
  await script('restore', instances[1], ['-BackupDirectory', backup], 1)
  await writeFile(manifestPath, originalManifest)

  const failedBackup = path.join(evidence, '失败 SQL 备份')
  await cp(backup, failedBackup, { recursive: true })
  const failingSql = Buffer.from('CREATE TABLE release_partial(id INT);\nTHIS IS INVALID SQL;\n')
  await writeFile(path.join(failedBackup, 'database.sql'), failingSql)
  const failingManifest = JSON.parse(originalManifest), sqlEntry = failingManifest.files.find(item => item.path === 'database.sql')
  sqlEntry.bytes = failingSql.length; sqlEntry.sha256 = hash(failingSql)
  await writeFile(path.join(failedBackup, 'manifest.json'), JSON.stringify(failingManifest))
  await script('restore', instances[2], ['-BackupDirectory', failedBackup], 1)
  invariant((await readFile(path.join(instances[2], 'run/restore-incomplete.json'), 'utf8')).includes('IMPORT_DATABASE'), 'Failed restore did not preserve its phase')
  assert.equal(await sql(schemas[2], 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();'), '1')
  await script('start', instances[2], [], 1)
  completed('Stopped-instance backup created; checksum/path tampering rejected; partial SQL restore blocks startup')

  await script('restore', instances[1], ['-BackupDirectory', backup])
  assert.deepEqual(await readFile(path.join(instances[0], 'data/.master-key')), await readFile(path.join(instances[1], 'data/.master-key')))
  await writeFile(path.join(sourceDirectory, 'Known.java'), 'class ChangedAfterBackup {}')
  await script('start', instances[1])
  const restored = `http://127.0.0.1:${configs[1].port}`
  await login(restored, configs[1].security)
  assert.deepEqual(await json(restored, `/api/projects/${project}/assets/${target.id}`), target)
  assert.deepEqual(await json(restored, `/api/projects/${project}/assets/${untouched.id}`), untouched)
  assert.deepEqual(await json(restored, runRoute), runBefore)
  assert.deepEqual(await json(restored, sourceRoute), sourceExcerpt)
  assert.equal(await sql(schemas[1], `SELECT COUNT(*) FROM asset_revision WHERE asset_id='${target.id}';`), revisionsBefore)
  assert.deepEqual(await bytes(restored, `/api/projects/${project}/files/${file.id}`), uploadBytes)
  assert.deepEqual(await bytes(restored, `/api/projects/${project}/files/${screenshotIds[0]}`), screenshotBefore)
  replies.push('OK')
  assert.equal((await json(restored, '/api/settings/model/test', 'POST')).ok, true)
  const rerun = await json(restored, `/api/projects/${project}/runs`, 'POST', { assetId: plan.id, idempotencyKey: 'release-restored-run' }, 202)
  await finish(restored, project, rerun)
  assert.equal((await json(restored, `/api/projects/${project}/runs/${rerun.runId}`)).status, 'PASSED')
  assert.deepEqual(await json(restored, runRoute), runBefore)
  await logout(restored)
  await script('stop', instances[1])
  await stopped(restored)
  assert.equal(businessRequests, 2); assert.equal(modelRequests, 5); assert.equal(replies.length, 0)
  result.authentication = { enabled: true, sessionRotation: true, csrf: true, logout: true, authenticatedModelCheck: true, restoreRequiresLogin: true }
  result.originalRunId = runSubmission.runId; result.restoredRunId = rerun.runId
  result.screenshotSha256 = hash(screenshotBefore); result.backup = backup
  completed('Restored IDs, revisions, source, attachment bytes, master-key decryption and a fresh mixed run verified')
  result.status = 'PASSED'
} catch (error) {
  result.status = 'FAILED'; result.error = error.message; process.exitCode = 1
  console.error(error.stack)
} finally {
  const cleanupErrors = []
  for (const instance of instances) {
    try { await script('stop', instance, ['-Force']) } catch (error) { cleanupErrors.push(error.message) }
  }
  fixture.closeAllConnections()
  await new Promise(resolve => fixture.close(resolve))
  for (const schema of createdSchemas) {
    invariant(/^ai_test_release_[a-f0-9]{32}_[abc]$/.test(schema), 'Cleanup target was not created by this test')
    try { await sql('', `REVOKE ALL ON \`${schema}\`.* FROM '${connection.username}'@'localhost';\nDROP DATABASE \`${schema}\`;\n`) }
    catch (error) { cleanupErrors.push(error.message) }
  }
  result.finishedAt = new Date().toISOString(); result.cleanupErrors = cleanupErrors
  if (cleanupErrors.length) { result.status = 'FAILED'; process.exitCode = 1 }
  await writeFile(path.join(evidence, 'release-acceptance.json'), JSON.stringify(result, null, 2))
  console.log(`Release acceptance evidence: ${evidence}`)
}
