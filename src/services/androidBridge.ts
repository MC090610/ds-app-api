import { showToast } from '../components/base/toastState.js'
import { setApiKey } from './settings.js'
import { markApiKeyValid } from './apiKeyStatus.js'
import { refreshPlatformBalance } from './platformBalance.js'

type AndroidFileBridge = {
  beginFile: (name: string, mime: string, size: number) => boolean
  writeFile: (base64Chunk: string) => boolean
  finishFile: () => void
  abortFile: () => void
  notify: (message: string) => void
  setDarkTheme: (dark: boolean) => void
  loginWithDeepSeek: () => void
  openPlatformPage: (page: 'usage' | 'top_up') => void
  requestPlatformBalance: (requestId: string) => void
}

type AccountLoginResult = {
  ok: boolean
  apiKey?: string
  error?: string
  cancelled?: boolean
}

declare global {
  interface Window {
    DeepSeekAndroid?: AndroidFileBridge
    __deepSeekAccountLoginResult?: (payload: string | AccountLoginResult) => void
    __deepSeekPlatformBalanceResult?: (payload: string) => void
  }
}

const CHUNK_SIZE = 192 * 1024

const readBase64 = (blob: Blob) =>
  new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(reader.error ?? new Error('File read failed'))
    reader.onload = () => {
      const result = typeof reader.result === 'string' ? reader.result : ''
      resolve(result.slice(result.indexOf(',') + 1))
    }
    reader.readAsDataURL(blob)
  })

const safeName = (anchor: HTMLAnchorElement) => {
  if (anchor.download.trim()) {
    return anchor.download.trim()
  }
  try {
    const name = decodeURIComponent(new URL(anchor.href).pathname.split('/').pop() ?? '')
    return name || `deepseek-${Date.now()}.zip`
  } catch {
    return `deepseek-${Date.now()}.zip`
  }
}

const saveBlob = async (bridge: AndroidFileBridge, anchor: HTMLAnchorElement) => {
  const response = await fetch(anchor.href)
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  const blob = await response.blob()
  const name = safeName(anchor)
  const mime = blob.type || 'application/octet-stream'
  if (!bridge.beginFile(name, mime, blob.size)) {
    throw new Error('Unable to create output file')
  }
  try {
    for (let offset = 0; offset < blob.size; offset += CHUNK_SIZE) {
      const chunk = blob.slice(offset, Math.min(offset + CHUNK_SIZE, blob.size))
      if (!bridge.writeFile(await readBase64(chunk))) {
        throw new Error('Unable to write output file')
      }
    }
    bridge.finishFile()
  } catch (error) {
    bridge.abortFile()
    throw error
  }
}

/**
 * Android WebView does not reliably download Blob URLs. Capture local download
 * links and stream their bytes through the native bridge into the Downloads
 * collection. Browser builds are untouched because the bridge is absent.
 */
export const setupAndroidBridge = () => {
  const bridge = window.DeepSeekAndroid
  if (!bridge) {
    return
  }

  window.__deepSeekAccountLoginResult = (payload) => {
    let result: AccountLoginResult
    try {
      result = typeof payload === 'string' ? (JSON.parse(payload) as AccountLoginResult) : payload
    } catch {
      showToast({ key: 'accountLoginFailed', type: 'error', params: { message: '' } })
      return
    }
    if (result.cancelled) {
      window.dispatchEvent(new CustomEvent('deepseek-account-login-result', { detail: result }))
      return
    }
    if (result.ok && typeof result.apiKey === 'string' && result.apiKey.trim()) {
      setApiKey(result.apiKey.trim())
      markApiKeyValid()
      void refreshPlatformBalance()
      showToast({ key: 'accountLoginSuccess' })
      window.dispatchEvent(new CustomEvent('deepseek-account-login-result', { detail: result }))
      return
    }
    window.dispatchEvent(new CustomEvent('deepseek-account-login-result', { detail: result }))
    if (result.error) {
      showToast({
        key: 'accountLoginFailed',
        type: 'error',
        params: { message: result.error },
      })
    }
  }

  document.documentElement.classList.add('deepseek-android')

  // Export actions call click() on a detached anchor, which never bubbles to
  // document. Patch the programmatic path as well as ordinary link clicks.
  const nativeClick = HTMLAnchorElement.prototype.click
  HTMLAnchorElement.prototype.click = function (this: HTMLAnchorElement) {
    if (
      (this.href.startsWith('blob:') || this.href.startsWith('data:')) &&
      this.hasAttribute('download')
    ) {
      void saveBlob(bridge, this).catch((error: unknown) => {
        const message = error instanceof Error ? error.message : String(error)
        bridge.notify(`保存失败：${message}`)
      })
      return
    }
    nativeClick.call(this)
  }

  document.addEventListener(
    'click',
    (event) => {
      const target = event.target
      if (!(target instanceof Element)) {
        return
      }
      const anchor = target.closest('a[href][download]')
      if (
        !(anchor instanceof HTMLAnchorElement) ||
        (!anchor.href.startsWith('blob:') && !anchor.href.startsWith('data:'))
      ) {
        return
      }
      event.preventDefault()
      event.stopPropagation()
      void saveBlob(bridge, anchor).catch((error: unknown) => {
        const message = error instanceof Error ? error.message : String(error)
        bridge.notify(`保存失败：${message}`)
      })
    },
    true
  )
}
