import { addApiUnauthorizedListener, verifyApiKey } from './api/client.js'
import { classifyApiError } from './api/errors.js'
import { setApiKeyStatus, useApiKeyStatus } from './apiKeyStatusStore.js'
import { useSettings } from './settings.js'

const CHECK_INTERVAL_MS = 5 * 60 * 1000

let generation = 0
let checking: Promise<void> | null = null

export const markApiKeyValid = () => setApiKeyStatus('valid')

export const checkApiKeyStatus = async (): Promise<void> => {
  const apiKey = useSettings.getState().apiKey
  if (!apiKey) {
    setApiKeyStatus('missing')
    return
  }
  if (checking) {
    return checking
  }

  const currentGeneration = ++generation
  const previousStatus = useApiKeyStatus.getState().status
  setApiKeyStatus('checking')
  checking = (async () => {
    try {
      await verifyApiKey(apiKey)
      if (generation === currentGeneration && useSettings.getState().apiKey === apiKey) {
        markApiKeyValid()
      }
    } catch (error) {
      if (generation !== currentGeneration || useSettings.getState().apiKey !== apiKey) {
        return
      }
      if (classifyApiError(error) === 'unauthorized') {
        setApiKeyStatus('invalid')
      } else {
        // Network and server failures do not prove that the saved Key was revoked.
        setApiKeyStatus(previousStatus === 'valid' ? 'valid' : 'unknown')
      }
    } finally {
      checking = null
    }
  })()
  return checking
}

const markCurrentKeyInvalid = (rejectedKey?: string) => {
  const currentKey = useSettings.getState().apiKey
  if (currentKey && (!rejectedKey || rejectedKey === currentKey)) {
    generation += 1
    checking = null
    setApiKeyStatus('invalid')
  }
}

export const setupApiKeyStatusMonitor = () => {
  const removeUnauthorizedListener = addApiUnauthorizedListener(markCurrentKeyInvalid)
  const checkWhenVisible = () => {
    if (!document.hidden) {
      void checkApiKeyStatus()
    }
  }
  void checkApiKeyStatus()
  const interval = window.setInterval(checkWhenVisible, CHECK_INTERVAL_MS)
  document.addEventListener('visibilitychange', checkWhenVisible)
  window.addEventListener('focus', checkWhenVisible)
  window.addEventListener('online', checkWhenVisible)

  return () => {
    window.clearInterval(interval)
    document.removeEventListener('visibilitychange', checkWhenVisible)
    window.removeEventListener('focus', checkWhenVisible)
    window.removeEventListener('online', checkWhenVisible)
    removeUnauthorizedListener()
  }
}
