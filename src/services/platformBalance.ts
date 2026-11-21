import { create } from 'zustand'
import { supportsWebAccountLogin } from './accountLogin.js'

export type WalletBalance = {
  currency: string
  recharge: string
  bonus: string
  total: string
}

type BalanceStatus = 'idle' | 'loading' | 'ready' | 'unauthenticated' | 'error'

type PlatformBalanceStore = {
  status: BalanceStatus
  wallets: WalletBalance[]
  error: string | null
  updatedAt: number | null
}

type BalanceResult = {
  requestId?: string | number
  ok: boolean
  wallets?: WalletBalance[]
  error?: string
  needsLogin?: boolean
}

export const usePlatformBalance = create<PlatformBalanceStore>(() => ({
  status: 'idle',
  wallets: [],
  error: null,
  updatedAt: null,
}))

let requestSequence = 0
let latestRequest = 0

const applyResult = (requestId: number, result: BalanceResult) => {
  if (requestId !== latestRequest) {
    return
  }
  if (result.ok && Array.isArray(result.wallets)) {
    usePlatformBalance.setState({
      status: 'ready',
      wallets: result.wallets,
      error: null,
      updatedAt: Date.now(),
    })
    return
  }
  usePlatformBalance.setState({
    status: result.needsLogin ? 'unauthenticated' : 'error',
    error: result.error ?? null,
  })
}

const parseNativeResult = (payload: string): BalanceResult | null => {
  try {
    return JSON.parse(payload) as BalanceResult
  } catch {
    return null
  }
}

export const refreshPlatformBalance = async (): Promise<void> => {
  const requestId = ++requestSequence
  latestRequest = requestId
  usePlatformBalance.setState({ status: 'loading', error: null })

  const bridge = window.DeepSeekAndroid
  if (typeof bridge?.requestPlatformBalance === 'function') {
    window.__deepSeekPlatformBalanceResult = (payload) => {
      const result = parseNativeResult(payload)
      if (result) {
        applyResult(Number(result.requestId) || requestId, result)
      }
    }
    bridge.requestPlatformBalance(String(requestId))
    return
  }

  if (!supportsWebAccountLogin()) {
    usePlatformBalance.setState({
      status: 'unauthenticated',
      error: null,
    })
    return
  }

  try {
    const response = await fetch('/__platform/balance', {
      cache: 'no-store',
      signal: AbortSignal.timeout(30_000),
    })
    const result = (await response.json()) as BalanceResult
    applyResult(requestId, result)
  } catch (error) {
    applyResult(requestId, {
      ok: false,
      error: error instanceof Error ? error.message : String(error),
    })
  }
}
