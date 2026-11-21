const wait = (milliseconds: number) =>
  new Promise<void>((resolve) => window.setTimeout(resolve, milliseconds))

export const supportsWebAccountLogin = () =>
  (location.hostname === 'localhost' || location.hostname === '127.0.0.1') &&
  location.protocol === 'http:'

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
  const start = await readJson<StartResponse>(
    await fetch('/__auth/start', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ locale }),
    })
  )
  const deadline = Date.now() + 10 * 60 * 1000
  while (Date.now() < deadline) {
    await wait(1200)
    const result = await readJson<StatusResponse>(
      await fetch(`/__auth/status?id=${encodeURIComponent(start.attemptId)}`, {
        cache: 'no-store',
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
