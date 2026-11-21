import assert from 'node:assert/strict'
import { createServer } from 'node:http'

import { createWebAuthServer } from './web-auth-server.mjs'

const readBody = async (request) => {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')
}

const reply = (response, payload, status = 200) => {
  response.writeHead(status, { 'content-type': 'application/json' })
  response.end(JSON.stringify(payload))
}

const envelope = (data) => ({
  code: 0,
  msg: '',
  data: { biz_code: 0, biz_msg: '', biz_data: data },
})

let keyRequest
let modelAuthorization
let balanceAuthorization

const platform = createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', 'http://127.0.0.1')
  if (url.pathname === '/api/v0/users/edit_api_keys') {
    keyRequest = {
      authorization: request.headers.authorization,
      body: await readBody(request),
    }
    reply(
      response,
      envelope({
        api_key: {
          sensitive_id: 'sk-test-generated',
          tracking_id: 'test-tracking',
        },
      })
    )
    return
  }
  if (url.pathname === '/models') {
    modelAuthorization = request.headers.authorization
    reply(response, { data: [{ id: 'deepseek-flash' }] })
    return
  }
  if (url.pathname === '/api/v0/users/get_user_summary') {
    balanceAuthorization = request.headers.authorization
    reply(response, {
      code: 0,
      data: {
        biz_code: 0,
        biz_msg: '',
        biz_data: {
          normal_wallets: [{ currency: 'CNY', balance: '128.50' }],
          bonus_wallets: [{ currency: 'CNY', balance: '12' }],
        },
      },
    })
    return
  }
  reply(response, { message: 'not found' }, 404)
})

await new Promise((resolve, reject) => {
  platform.once('error', reject)
  platform.listen(0, '127.0.0.1', resolve)
})
const platformOrigin = `http://127.0.0.1:${platform.address().port}`

let tokenPolls = 0
let browserClosed = false
let openedPlatformPage
const platformSessionFactory = async () => ({
  async getPlatformToken() {
    tokenPolls += 1
    return tokenPolls >= 2 ? 'test-platform-token' : ''
  },
  isClosed() {
    return browserClosed
  },
  async close() {
    browserClosed = true
  },
})

const web = createWebAuthServer({
  platformOrigin,
  apiOrigin: platformOrigin,
  platformSessionFactory,
  platformPageOpener: async ({ page }) => {
    openedPlatformPage = page
  },
  pollIntervalMs: 10,
})
await new Promise((resolve, reject) => {
  web.once('error', reject)
  web.listen(0, '127.0.0.1', resolve)
})
const webOrigin = `http://127.0.0.1:${web.address().port}`

try {
  const started = await fetch(`${webOrigin}/__auth/start`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      origin: webOrigin,
    },
    body: JSON.stringify({ locale: 'zh-CN' }),
  }).then((response) => response.json())

  let result
  for (let attempt = 0; attempt < 30; attempt += 1) {
    result = await fetch(`${webOrigin}/__auth/status?id=${started.attemptId}`).then((response) =>
      response.json()
    )
    if (result.status !== 'pending') break
    await new Promise((resolve) => setTimeout(resolve, 10))
  }

  assert.deepEqual(result, { status: 'ready', apiKey: 'sk-test-generated' })
  assert.equal(keyRequest.authorization, 'Bearer test-platform-token')
  assert.equal(keyRequest.body.action, 'create')
  assert.equal(keyRequest.body.name, 'DeepSeek App')
  assert.equal(modelAuthorization, 'Bearer sk-test-generated')
  assert.equal(browserClosed, true)

  const opened = await fetch(`${webOrigin}/__platform/open`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      origin: webOrigin,
    },
    body: JSON.stringify({ page: 'top_up' }),
  })
  assert.equal(opened.status, 200)
  assert.equal(openedPlatformPage, 'top_up')

  const balance = await fetch(`${webOrigin}/__platform/balance`).then((response) => response.json())
  assert.deepEqual(balance.wallets, [
    {
      currency: 'CNY',
      recharge: '128.50',
      bonus: '12.00',
      total: '140.50',
    },
  ])
  assert.equal(balanceAuthorization, 'Bearer test-platform-token')

  console.log('Platform sign-in -> API key: PASS')
} finally {
  await new Promise((resolve) => web.close(resolve))
  await new Promise((resolve) => platform.close(resolve))
}
