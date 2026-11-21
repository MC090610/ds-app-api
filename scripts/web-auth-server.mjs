import { randomUUID } from 'node:crypto'
import { createReadStream, existsSync, statSync } from 'node:fs'
import { createServer } from 'node:http'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  launchPlatformBrowserSession,
  openPlatformPageInBrowser,
} from './platform-browser-login.mjs'

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

const clientHeaders = (locale = 'zh_CN') => ({
  'x-client-bundle-id': 'com.deepseek.chat',
  'x-client-platform': 'web',
  'x-client-version': '1.0.1',
  'x-client-locale': locale === 'en-US' || locale === 'en_US' ? 'en_US' : 'zh_CN',
  'x-client-timezone-offset': String(-new Date().getTimezoneOffset() * 60),
})

const readJson = async (request) => {
  const chunks = []
  let size = 0
  for await (const chunk of request) {
    size += chunk.length
    if (size > 64 * 1024) throw new Error('请求体过大')
    chunks.push(chunk)
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')
}

const sendJson = (response, status, body) => {
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
  })
  response.end(JSON.stringify(body))
}

const requestJson = async (url, { method = 'GET', body, token, platform = false, locale } = {}) => {
  const response = await fetch(url, {
    method,
    redirect: 'error',
    signal: AbortSignal.timeout(30_000),
    headers: {
      accept: 'application/json',
      ...(body === undefined ? {} : { 'content-type': 'application/json' }),
      ...(token === undefined ? {} : { authorization: `Bearer ${token}` }),
      ...(platform ? clientHeaders(locale) : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  if (!response.ok) {
    throw new Error(`平台请求失败（HTTP ${response.status}）`)
  }
  try {
    return JSON.parse(text)
  } catch {
    throw new Error('平台返回了无效响应')
  }
}

const createKeyFromPlatformToken = async (platformOrigin, platformToken, locale) => {
  const payload = await requestJson(`${platformOrigin}/api/v0/users/edit_api_keys`, {
    method: 'POST',
    platform: true,
    locale,
    token: platformToken,
    body: {
      action: 'create',
      name: 'DeepSeek App',
      redacted_key: null,
      created_at: null,
      tracking_id: null,
      is_exp: false,
    },
  })
  if (payload?.code !== 0) {
    throw new Error(payload?.msg || '自动创建 API Key 失败')
  }
  const bizCode = payload?.data?.biz_code
  if (bizCode === 8) {
    throw new Error('账号尚未完成实名认证，暂时没有创建 API Key')
  }
  if (bizCode === 1) {
    throw new Error('该账号的 API Key 数量已达上限')
  }
  if (bizCode === 2) {
    throw new Error('API Key 名称过长')
  }
  if (bizCode !== 0) {
    throw new Error(`自动创建 API Key 失败，平台返回码 ${bizCode ?? '?'}`)
  }
  const value = payload?.data?.biz_data?.api_key?.sensitive_id
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error('平台没有返回 API Key')
  }
  return value.trim()
}

const verifyApiKey = async (apiOrigin, apiKey) => {
  const response = await fetch(`${apiOrigin}/models`, {
    redirect: 'error',
    signal: AbortSignal.timeout(30_000),
    headers: { authorization: `Bearer ${apiKey}` },
  })
  await response.body?.cancel()
  if (!response.ok) throw new Error('新建的 API Key 校验失败')
}

const normalizeWallets = (data) => {
  const wallets = new Map()
  const addWallets = (values, field) => {
    for (const item of Array.isArray(values) ? values : []) {
      const currency = String(item?.currency || 'CNY').toUpperCase()
      const wallet = wallets.get(currency) ?? {
        currency,
        recharge: '0.00',
        bonus: '0.00',
      }
      wallet[field] = Number(item?.balance ?? 0).toFixed(2)
      wallets.set(currency, wallet)
    }
  }
  addWallets(data?.normal_wallets, 'recharge')
  addWallets(data?.bonus_wallets, 'bonus')
  return [...wallets.values()].map((wallet) => ({
    ...wallet,
    total: (Number(wallet.recharge) + Number(wallet.bonus)).toFixed(2),
  }))
}

const fetchPlatformBalance = async (platformOrigin, token, locale) => {
  const response = await fetch(`${platformOrigin}/api/v0/users/get_user_summary`, {
    redirect: 'error',
    signal: AbortSignal.timeout(30_000),
    headers: {
      accept: 'application/json',
      authorization: `Bearer ${token}`,
      ...clientHeaders(locale),
    },
  })
  if (response.status === 401 || response.status === 403) {
    await response.body?.cancel()
    return { ok: false, needsLogin: true, error: '开放平台登录已失效，请重新登录' }
  }
  const text = await response.text()
  if (!response.ok) {
    throw new Error(`余额请求失败（HTTP ${response.status}）`)
  }
  const payload = JSON.parse(text)
  if (payload?.code !== 0 || payload?.data?.biz_code !== 0) {
    throw new Error(payload?.data?.biz_msg || payload?.msg || '余额接口返回错误')
  }
  return { ok: true, wallets: normalizeWallets(payload?.data?.biz_data) }
}

const localOrigin = (request) => {
  const host = request.headers.host ?? ''
  const match = /^(localhost|127\.0\.0\.1|\[::1\]):(\d+)$/u.exec(host)
  if (match === null) throw new Error('账号登录只允许使用本机回环地址')
  return `http://${host}`
}

const mime = (filePath) => {
  const extension = path.extname(filePath).toLowerCase()
  return (
    {
      '.css': 'text/css; charset=utf-8',
      '.html': 'text/html; charset=utf-8',
      '.js': 'application/javascript; charset=utf-8',
      '.json': 'application/json; charset=utf-8',
      '.map': 'application/json; charset=utf-8',
      '.svg': 'image/svg+xml',
      '.png': 'image/png',
      '.jpg': 'image/jpeg',
      '.jpeg': 'image/jpeg',
      '.webp': 'image/webp',
      '.woff': 'font/woff',
      '.woff2': 'font/woff2',
      '.ttf': 'font/ttf',
    }[extension] ?? 'application/octet-stream'
  )
}

export const createWebAuthServer = ({
  platformOrigin = 'https://platform.deepseek.com',
  apiOrigin = 'https://api.deepseek.com',
  distDirectory = path.join(root, 'dist'),
  platformSessionFactory = launchPlatformBrowserSession,
  platformPageOpener = openPlatformPageInBrowser,
  pollIntervalMs = 1_000,
  loginTimeoutMs = 10 * 60 * 1000,
  rootDirectory = path.join(root, 'tmp'),
} = {}) => {
  const attempts = new Map()
  let cachedPlatformToken = null
  let balanceTokenRead = null

  const readBalanceToken = async () => {
    if (cachedPlatformToken) return cachedPlatformToken
    if (balanceTokenRead) return balanceTokenRead
    balanceTokenRead = (async () => {
      const session = await platformSessionFactory({
        platformOrigin,
        rootDirectory,
        headless: true,
      })
      try {
        for (let attempt = 0; attempt < 40; attempt += 1) {
          if (session.isClosed()) break
          const token = await session.getPlatformToken()
          if (token) {
            cachedPlatformToken = token
            return token
          }
          await sleep(200)
        }
        return null
      } finally {
        await session.close()
      }
    })().finally(() => {
      balanceTokenRead = null
    })
    return balanceTokenRead
  }

  const closeAttempt = async (attempt) => {
    try {
      await attempt.session?.close()
    } catch {
      // Browser shutdown is best effort.
    }
  }

  const completeAttempt = async (attempt) => {
    const deadline = Date.now() + loginTimeoutMs
    try {
      while (Date.now() < deadline) {
        if (attempt.session.isClosed()) {
          throw new Error('登录窗口已关闭，请重试')
        }
        const platformToken = await attempt.session.getPlatformToken()
        if (platformToken) {
          cachedPlatformToken = platformToken
          const apiKey = await createKeyFromPlatformToken(
            platformOrigin,
            platformToken,
            attempt.locale
          )
          await verifyApiKey(apiOrigin, apiKey)
          Object.assign(attempt, { status: 'ready', apiKey })
          await closeAttempt(attempt)
          return
        }
        await sleep(pollIntervalMs)
      }
      throw new Error('登录超时，请重试')
    } catch (error) {
      Object.assign(attempt, {
        status: 'error',
        error: error instanceof Error ? error.message : String(error),
      })
      await closeAttempt(attempt)
    }
  }

  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url ?? '/', `http://${request.headers.host ?? '127.0.0.1'}`)

      if (request.method === 'POST' && url.pathname === '/__auth/start') {
        const callbackOrigin = localOrigin(request)
        if (request.headers.origin !== callbackOrigin) {
          throw new Error('账号登录请求来源无效')
        }
        const requestBody = await readJson(request)
        const attemptId = randomUUID()
        const session = await platformSessionFactory({
          platformOrigin,
          rootDirectory,
        })
        const attempt = {
          id: attemptId,
          locale: requestBody.locale,
          session,
          status: 'pending',
          expiresAt: Date.now() + loginTimeoutMs,
        }
        attempts.set(attemptId, attempt)
        void completeAttempt(attempt)
        sendJson(response, 200, { attemptId })
        return
      }

      if (request.method === 'GET' && url.pathname === '/__auth/status') {
        const attempt = attempts.get(url.searchParams.get('id') ?? '')
        if (attempt === undefined || attempt.expiresAt < Date.now()) {
          sendJson(response, 404, { status: 'missing' })
          return
        }
        sendJson(
          response,
          200,
          attempt.status === 'ready'
            ? { status: 'ready', apiKey: attempt.apiKey }
            : attempt.status === 'error'
              ? { status: 'error', message: attempt.error }
              : { status: 'pending' }
        )
        return
      }

      if (request.method === 'POST' && url.pathname === '/__platform/open') {
        const callbackOrigin = localOrigin(request)
        if (request.headers.origin !== callbackOrigin) {
          throw new Error('开放平台页面请求来源无效')
        }
        const requestBody = await readJson(request)
        const page = requestBody.page === 'usage' ? 'usage' : 'top_up'
        await platformPageOpener({
          page,
          platformOrigin,
          rootDirectory,
        })
        sendJson(response, 200, { ok: true })
        return
      }

      if (request.method === 'GET' && url.pathname === '/__platform/balance') {
        localOrigin(request)
        const locale = request.headers['accept-language'] ?? 'zh-CN'
        await readBalanceToken()
        if (!cachedPlatformToken) {
          sendJson(response, 200, {
            ok: false,
            needsLogin: true,
            error: '请先登录 DeepSeek 开放平台',
          })
          return
        }
        const result = await fetchPlatformBalance(platformOrigin, cachedPlatformToken, locale)
        if (result.needsLogin) cachedPlatformToken = null
        sendJson(response, 200, result)
        return
      }

      if (request.method !== 'GET' && request.method !== 'HEAD') {
        sendJson(response, 405, { message: 'Method not allowed' })
        return
      }

      let pathname = decodeURIComponent(url.pathname)
      if (pathname === '/') pathname = '/index.html'
      const filePath = path.resolve(distDirectory, `.${pathname}`)
      if (
        !filePath.startsWith(`${path.resolve(distDirectory)}${path.sep}`) ||
        !existsSync(filePath)
      ) {
        sendJson(response, 404, { message: 'Not found' })
        return
      }
      const info = statSync(filePath)
      if (!info.isFile()) {
        sendJson(response, 404, { message: 'Not found' })
        return
      }
      response.writeHead(200, {
        'content-type': mime(filePath),
        'content-length': info.size,
        'cache-control': pathname.includes('/static/')
          ? 'public, max-age=31536000, immutable'
          : 'no-cache',
      })
      if (request.method === 'HEAD') {
        response.end()
      } else {
        createReadStream(filePath).pipe(response)
      }
    } catch (error) {
      sendJson(response, 500, {
        message: error instanceof Error ? error.message : String(error),
      })
    }
  })

  server.on('close', () => {
    for (const attempt of attempts.values()) {
      void closeAttempt(attempt)
    }
    attempts.clear()
  })

  return server
}

export const startWebAuthServer = async ({
  port = Number(process.env.PORT ?? 8090),
  host = '127.0.0.1',
  ...options
} = {}) => {
  const server = createWebAuthServer(options)
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, host, resolve)
  })
  return server
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const server = await startWebAuthServer()
  const address = server.address()
  console.log(`DeepSeek web app: http://127.0.0.1:${address.port}`)
}
