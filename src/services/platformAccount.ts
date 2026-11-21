import { showToast } from '../components/base/toastState.js'
import { supportsWebAccountLogin } from './accountLogin.js'

export type PlatformPage = 'usage' | 'top_up'

const PLATFORM_ORIGIN = 'https://platform.deepseek.com'

export const openPlatformPage = (page: PlatformPage) => {
  const bridge = window.DeepSeekAndroid
  if (typeof bridge?.openPlatformPage === 'function') {
    bridge.openPlatformPage(page)
    return
  }
  if (supportsWebAccountLogin()) {
    void fetch('/__platform/open', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ page }),
    })
      .then(async (response) => {
        if (!response.ok) {
          const body = (await response.json()) as { message?: string }
          throw new Error(body.message || `HTTP ${response.status}`)
        }
      })
      .catch((error: unknown) => {
        showToast({
          key: 'platformOpenFailed',
          type: 'error',
          params: {
            message: error instanceof Error ? error.message : String(error),
          },
        })
      })
    return
  }
  window.open(`${PLATFORM_ORIGIN}/${page}`, '_blank', 'noopener,noreferrer')
}
