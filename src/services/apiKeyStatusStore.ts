import { create } from 'zustand'

export type ApiKeyStatus = 'missing' | 'unknown' | 'checking' | 'valid' | 'invalid'

type ApiKeyStatusStore = {
  status: ApiKeyStatus
  checkedAt: number | null
}

export const useApiKeyStatus = create<ApiKeyStatusStore>(() => ({
  status: 'unknown',
  checkedAt: null,
}))

export const setApiKeyStatus = (status: ApiKeyStatus) => {
  useApiKeyStatus.setState({
    status,
    checkedAt: status === 'valid' || status === 'invalid' ? Date.now() : null,
  })
}
