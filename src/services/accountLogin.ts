const wait = (milliseconds: number) =>
  new Promise<void>((resolve) => window.setTimeout(resolve, milliseconds))

const LOCAL_AUTH_ORIGIN = 'http://127.0.0.1:8090'

export const WEB_AUTH_HELPER_UNAVAILABLE = 'WEB_AUTH_HELPER_UNAVAILABLE'

export const supportsWebAccountLogin = () =>
  location.protocol === 'http:' || location.protocol === 'https:'

export const usesRemoteWebAuthHelper = () =>
  supportsWebAccountLogin() &&
  location.hostname !== 'localhost' &&
  location.hostname !== '127.0.0.1'

export const webAuthEndpoint = (path: string) =>
  usesRemoteWebAuthHelper() ? `${LOCAL_AUTH_ORIGIN}${path}` : path

const readJson = async <T>(response: Response): Promise<T> => {
  const body = (await response.json()) as T & { message?: string }
  if (!response.ok) {
    throw new Error(body.message || `HTTP ${response.status}`)
  }
  return body
}

type StartResponse = {
  attemptId: string
}

type StatusResponse =
  | { status: 'pending' }
  | { status: 'ready'; apiKey: string }
  | { status: 'error'; message: string }
  | { status: 'missing' }

export const loginWithWebAccount = async (locale: string): Promise<string> => {
  let response: Response
  try {
    response = await fetch(webAuthEndpoint('/__auth/start'), {
      method: 'POST',
      mode: 'cors',
      credentials: 'omit',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ locale }),
    })
  } catch {
    throw new Error(WEB_AUTH_HELPER_UNAVAILABLE)
  }
  const start = await readJson<StartResponse>(response)
  const deadline = Date.now() + 10 * 60 * 1000
  while (Date.now() < deadline) {
    await wait(1200)
    const result = await readJson<StatusResponse>(
      await fetch(webAuthEndpoint(`/__auth/status?id=${encodeURIComponent(start.attemptId)}`), {
        cache: 'no-store',
        mode: 'cors',
        credentials: 'omit',
      })
    )
    if (result.status === 'ready') {
      return result.apiKey
    }
    if (result.status === 'error') {
      throw new Error(result.message)
    }
    if (result.status === 'missing') {
      throw new Error('登录申请已失效')
    }
  }
  throw new Error('登录超时')
}
