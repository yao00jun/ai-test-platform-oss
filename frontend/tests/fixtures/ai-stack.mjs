import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { createWriteStream } from 'node:fs'
import { readFile, readdir, stat, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

// Real isolated backend + MySQL + model-protocol fixture. Never changes the user's model settings.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const runtime = path.join(root, '.runtime')
const connection = JSON.parse(await readFile(path.join(runtime, 'mysql/connection.json'), 'utf8'))
const mysql = path.join(connection.home, 'bin/mysql.exe')
const admin = path.join(runtime, 'mysql/admin.cnf')
if (connection.username !== 'aitest') throw new Error('This fixture requires the project bootstrap MySQL account.')
await new Promise((resolve, reject) => {
  const process = spawn(mysql, [`--defaults-file=${admin}`, '--batch'], { windowsHide: true, stdio: ['pipe', 'ignore', 'pipe'] })
  let message = ''; process.stderr.on('data', data => { message += data.toString() })
  process.once('error', reject)
  process.once('exit', code => code === 0 ? resolve() : reject(new Error(`E2E database setup failed (${code}): ${message}`)))
  process.stdin.end("CREATE DATABASE IF NOT EXISTS ai_test_platform_e2e CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\nGRANT ALL ON ai_test_platform_e2e.* TO 'aitest'@'localhost';\n")
})
const snapshots = await Promise.all((await readdir(runtime)).filter(name => /^backend-classes-[a-f0-9]+$/.test(name)).map(async name => ({ name, time: (await stat(path.join(runtime, name, 'java.args'))).mtimeMs })))
snapshots.sort((a, b) => b.time - a.time)
if (!snapshots[0]) throw new Error('Start the compiled development backend first to create an immutable class snapshot.')
const snapshot = path.join(runtime, snapshots[0].name, 'java.args')
const storage = path.join(runtime, 'e2e-storage')
await mkdir(storage, { recursive: true })
const replies = [], requests = [], children = []
const server = createServer(async (request, response) => {
  try {
    let body = ''; for await (const chunk of request) { body += chunk; if (body.length > 4_000_000) throw new Error('Fixture request too large') }
    if (request.url === '/__fixture/queue' && request.method === 'POST') {
      replies.push(JSON.parse(body)); response.writeHead(200, { 'content-type': 'application/json' }); response.end('{"ok":true}'); return
    }
    if (request.url === '/__fixture/reset' && request.method === 'POST') {
      replies.length = 0; requests.length = 0; response.writeHead(200, { 'content-type': 'application/json' }); response.end('{"ok":true}'); return
    }
    if (request.url === '/__fixture/requests') { response.writeHead(200, { 'content-type': 'application/json' }); response.end(JSON.stringify(requests)); return }
    if (request.url === '/__business/orders' && request.method === 'POST') {
      const input = JSON.parse(body)
      response.writeHead(201, { 'content-type': 'application/json' }); response.end(JSON.stringify({ id: input.qty, state: 'CREATED' })); return
    }
    if (request.url === '/__business/page') {
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }); response.end('<!doctype html><html lang="zh"><title>订单验收</title><body><h1 id="title">订单页面</h1><label>订单备注<input aria-label="订单备注"></label></body></html>'); return
    }
    if (request.url === '/__business/rca-error') {
      response.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' })
      response.end('java.lang.IllegalArgumentException: missing id\n\tat shop.OrderService.refund(OrderService.java:4)\n')
      return
    }
    if (request.url !== '/v1/chat/completions') { response.writeHead(404); response.end(); return }
    const input = JSON.parse(body); requests.push(input)
    const reply = replies.shift()
    if (!reply) { response.writeHead(503); response.end('{"error":{"message":"No queued fixture reply"}}'); return }
    if (reply.delayMs) await new Promise(resolve => setTimeout(resolve, reply.delayMs))
    if (reply.status && reply.status !== 200) { response.writeHead(reply.status, { 'content-type': 'application/json' }); response.end(JSON.stringify(reply.body)); return }
    response.writeHead(200, { 'content-type': 'text/event-stream' })
    if (reply.parentFromTargetCase) {
      const context = JSON.parse(input.messages.findLast(message => message.role === 'user').content)
      const targetIds = Array.isArray(context.targetCases)
        ? context.targetCases.map(target => target?.id).filter(id => typeof id === 'string')
        : typeof context.targetCase?.id === 'string' ? [context.targetCase.id] : []
      if (context.stage !== 'S4' || targetIds.length === 0) throw new Error('Expected actual S4 target case context')
      for (const [index, change] of reply.content.changes.entries()) change.parentId = targetIds[Math.min(index, targetIds.length - 1)]
    }
    const content = typeof reply.content === 'string' ? reply.content : JSON.stringify(reply.content)
    for (let offset = 0; offset < content.length; offset += 50) {
      response.write(`data: ${JSON.stringify({ id: 'e2e', object: 'chat.completion.chunk', created: 1, model: 'e2e-fixture', choices: [{ index: 0, delta: { content: content.slice(offset, offset + 50) } }] })}\n\n`)
    }
    response.write('data: {"id":"e2e","object":"chat.completion.chunk","created":1,"model":"e2e-fixture","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}\n\n')
    if (reply.usage) response.write(`data: ${JSON.stringify({ id: 'e2e', object: 'chat.completion.chunk', created: 1, model: 'e2e-fixture', choices: [], usage: reply.usage })}\n\n`)
    response.end('data: [DONE]\n\n')
  } catch { if (!response.headersSent) response.writeHead(500); response.end() }
})
await new Promise((resolve, reject) => { server.once('error', reject); server.listen(8082, '127.0.0.1', resolve) })
function start(command, args, env, logName) {
  const log = createWriteStream(path.join(runtime, logName), { flags: 'w' })
  const child = spawn(command, args, { cwd: path.join(root, 'frontend'), env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
  child.stdout.pipe(log); child.stderr.pipe(log)
  child.once('error', () => shutdown(1)); child.once('exit', () => { if (!closing) shutdown(1) })
  children.push(child); return child
}
let closing = false
function shutdown(code = 0) {
  if (closing) return
  closing = true
  for (const child of children) child.kill('SIGTERM')
  server.close()
  setTimeout(() => process.exit(code), 100).unref()
}
process.on('SIGINT', () => shutdown()); process.on('SIGTERM', () => shutdown()); process.on('exit', () => { for (const child of children) child.kill() })
const env = { ...process.env, AI_TEST_PORT: '8081', AI_TEST_DB_URL: `jdbc:mysql://127.0.0.1:${connection.port}/ai_test_platform_e2e?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8`, AI_TEST_DB_USER: connection.username, AI_TEST_DB_PASSWORD: connection.password, AI_TEST_STORAGE: storage, AI_TEST_MODEL_BASE_URL: 'http://127.0.0.1:8082/v1', AI_TEST_MODEL_API_KEY: 'local-protocol-fixture', AI_TEST_MODEL_NAME: 'e2e-fixture', AI_TEST_BROWSER_PATH: path.join(root, '.tools/playwright-1.62.0') }
start(path.join(process.env.AI_TEST_JAVA_HOME || 'C:/Program Files/Java/jdk-21.0.11', 'bin/java.exe'), [`@${snapshot}`], env, 'e2e-backend.log')
let ready = false
for (let count = 0; count < 480; count++) {
  try { const response = await fetch('http://127.0.0.1:8081/actuator/health'); if (response.ok) { ready = true; break } } catch { /* Startup is bounded below. */ }
  await new Promise(resolve => setTimeout(resolve, 250))
}
if (!ready) { shutdown(1); throw new Error('E2E backend did not become ready; see .runtime/e2e-backend.log') }
start(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '5174', '--strictPort'], { ...process.env, AI_TEST_API_URL: 'http://127.0.0.1:8081' }, 'e2e-frontend.log')
