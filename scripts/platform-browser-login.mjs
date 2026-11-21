import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdir, readFile, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

const browserCandidates = () => {
  const values = [
    process.env.CHROME_PATH,
    process.env.BROWSER_PATH,
    process.platform === 'win32'
      ? path.join(process.env.ProgramFiles ?? '', 'Google', 'Chrome', 'Application', 'chrome.exe')
      : null,
    process.platform === 'win32'
      ? path.join(
          process.env['ProgramFiles(x86)'] ?? '',
          'Microsoft',
          'Edge',
          'Application',
          'msedge.exe'
        )
      : null,
    process.platform === 'darwin'
      ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
      : null,
    process.platform === 'darwin'
      ? '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'
      : null,
    process.platform === 'linux' ? '/usr/bin/google-chrome' : null,
    process.platform === 'linux' ? '/usr/bin/chromium' : null,
    process.platform === 'linux' ? '/usr/bin/chromium-browser' : null,
  ]
  return values.filter((value) => value && existsSync(value))
}

const findBrowser = () => {
  const candidate = browserCandidates()[0]
  if (!candidate) {
    throw new Error('未找到 Chrome 或 Edge。请设置 CHROME_PATH 指向 Chromium 浏览器。')
  }
  return candidate
}

class CdpConnection {
  #socket
  #nextId = 1
  #pending = new Map()
  #open = false

  constructor(url) {
    this.#socket = new WebSocket(url)
    this.#socket.addEventListener('close', () => {
      this.#open = false
      for (const pending of this.#pending.values()) {
        pending.reject(new Error('登录浏览器连接已关闭'))
      }
      this.#pending.clear()
    })
    this.#socket.addEventListener('message', (event) => {
      const message = JSON.parse(String(event.data))
      if (!message.id || !this.#pending.has(message.id)) {
        return
      }
      const pending = this.#pending.get(message.id)
      this.#pending.delete(message.id)
      if (message.error) {
        pending.reject(new Error(message.error.message || '浏览器调试请求失败'))
      } else {
        pending.resolve(message.result)
      }
    })
  }

  async open() {
    await new Promise((resolve, reject) => {
      this.#socket.addEventListener('open', resolve, { once: true })
      this.#socket.addEventListener('error', () => reject(new Error('无法连接登录浏览器')), {
        once: true,
      })
    })
    this.#open = true
  }

  send(method, params = {}) {
    const id = this.#nextId++
    return new Promise((resolve, reject) => {
      this.#pending.set(id, { resolve, reject })
      this.#socket.send(JSON.stringify({ id, method, params }))
    })
  }

  close() {
    this.#socket.close()
  }

  isOpen() {
    return this.#open
  }
}

const waitForDebuggingPort = async (profileDirectory, timeoutMs = 15_000) => {
  const portFile = path.join(profileDirectory, 'DevToolsActivePort')
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const [port] = (await readFile(portFile, 'utf8')).trim().split(/\r?\n/u)
      if (/^\d+$/u.test(port ?? '')) {
        return Number(port)
      }
    } catch {
      // Chrome writes the file after its remote-debugging server starts.
    }
    await sleep(100)
  }
  throw new Error('登录浏览器启动超时')
}

const pageTargets = async (port) => {
  const response = await fetch(`http://127.0.0.1:${port}/json/list`, {
    signal: AbortSignal.timeout(2_000),
  })
  if (!response.ok) {
    throw new Error('无法读取登录浏览器页面')
  }
  return response.json()
}

const findPlatformTarget = async (port) => {
  let lastError = null
  for (let attempt = 0; attempt < 40; attempt += 1) {
    try {
      const targets = await pageTargets(port)
      const target = targets.find(
        (item) =>
          item.type === 'page' &&
          typeof item.url === 'string' &&
          new URL(item.url).hostname.endsWith('deepseek.com')
      )
      if (target) {
        return target
      }
    } catch (error) {
      lastError = error
    }
    await sleep(150)
  }
  throw lastError ?? new Error('未找到 DeepSeek 登录页面')
}

const readPlatformToken = async (connection) => {
  const result = await connection.send('Runtime.evaluate', {
    expression: `(() => {
      try {
        const raw = localStorage.getItem('userToken')
        if (!raw) return { token: '' }
        const parsed = JSON.parse(raw)
        const token =
          parsed && typeof parsed === 'object' && 'value' in parsed ? parsed.value : parsed
        return { token: typeof token === 'string' ? token.trim() : '' }
      } catch {
        return { token: '' }
      }
    })()`,
    returnByValue: true,
    awaitPromise: true,
  })
  const value = result?.result?.value
  return typeof value?.token === 'string' ? value.token : ''
}

/**
 * Launches a dedicated Chromium profile and exposes the official Platform
 * page. The login token stays in that browser's localStorage; only a polling
 * read is returned to the local helper process.
 */
export const launchPlatformBrowserSession = async ({
  platformOrigin = 'https://platform.deepseek.com',
  rootDirectory = path.resolve(os.tmpdir(), 'deepseek-chat-demo'),
  headless = false,
} = {}) => {
  const profileDirectory = path.join(rootDirectory, 'platform-browser-profile')
  await mkdir(profileDirectory, { recursive: true })
  await rm(path.join(profileDirectory, 'DevToolsActivePort'), { force: true })

  const browser = findBrowser()
  const process = spawn(
    browser,
    [
      ...(headless ? ['--headless=new'] : []),
      '--remote-debugging-address=127.0.0.1',
      '--remote-debugging-port=0',
      `--user-data-dir=${profileDirectory}`,
      '--no-first-run',
      '--no-default-browser-check',
      '--new-window',
      `${platformOrigin}/sign_in`,
    ],
    {
      stdio: 'ignore',
      windowsHide: headless,
    }
  )
  process.on('error', () => {
    // getPlatformToken reports startup failures with a user-facing message.
  })
  process.unref()

  const port = await waitForDebuggingPort(profileDirectory)
  const target = await findPlatformTarget(port)
  const connection = new CdpConnection(target.webSocketDebuggerUrl)
  await connection.open()

  let closed = false
  return {
    async getPlatformToken() {
      if (closed || !connection.isOpen()) {
        return null
      }
      try {
        return await readPlatformToken(connection)
      } catch {
        return null
      }
    },
    isClosed() {
      return closed || !connection.isOpen()
    },
    async close() {
      if (closed) {
        return
      }
      closed = true
      try {
        await connection.send('Browser.close')
      } catch {
        // The browser may already be closed by the user.
      }
      connection.close()
    },
  }
}

export const openPlatformPageInBrowser = async ({
  page = 'top_up',
  platformOrigin = 'https://platform.deepseek.com',
  rootDirectory = path.resolve(os.tmpdir(), 'deepseek-chat-demo'),
} = {}) => {
  const destination = page === 'usage' ? 'usage' : 'top_up'
  const profileDirectory = path.join(rootDirectory, 'platform-browser-profile')
  await mkdir(profileDirectory, { recursive: true })
  const browser = findBrowser()
  const child = spawn(
    browser,
    [
      `--user-data-dir=${profileDirectory}`,
      '--no-first-run',
      '--no-default-browser-check',
      '--new-window',
      `${platformOrigin}/${destination}`,
    ],
    {
      stdio: 'ignore',
      windowsHide: false,
    }
  )
  child.on('error', () => {
    // The request still completes; the user can retry from the web app.
  })
  child.unref()
}
