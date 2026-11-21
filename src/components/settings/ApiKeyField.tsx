import { useEffect, useState } from 'react'
import { IconCheckOutline16, IconEditOutline16 } from '../base/icons.js'
import { setApiKey, useSettings } from '../../services/settings.js'
import { markApiKeyValid } from '../../services/apiKeyStatus.js'
import { useApiKeyStatus } from '../../services/apiKeyStatusStore.js'
import { useIsMobile } from '../../app/viewport.js'
import { IconButton } from '../base/IconButton.js'
import { useApiKeyForm } from './useApiKeyForm.js'
import { useTranslate } from '../../i18n/index.js'
import { loginWithWebAccount, supportsWebAccountLogin } from '../../services/accountLogin.js'
import { Button } from '../base/Button.js'
import { showToast } from '../base/toastState.js'
import { refreshPlatformBalance } from '../../services/platformBalance.js'
import styles from './Settings.module.css'

/** API Key 的编辑状态：输入框与移动端底部的「确定」按钮共用 */
export const useApiKeyEditor = () => {
  const apiKey = useSettings((state) => state.apiKey)
  const [editing, setEditing] = useState(false)
  const form = useApiKeyForm(() => setEditing(false))
  return { apiKey, editing, setEditing, form, showInput: !apiKey || editing }
}

type ApiKeyEditor = ReturnType<typeof useApiKeyEditor>

/** API Key：保存后只显示掩码，点编辑按钮才能重新输入 */
export const ApiKeyField = ({ editor }: { editor: ApiKeyEditor }) => {
  const t = useTranslate()
  const isMobile = useIsMobile()
  const { apiKey, editing, setEditing, form, showInput } = editor
  const apiKeyStatus = useApiKeyStatus((state) => state.status)
  const nativeAccountLogin =
    typeof window !== 'undefined' && typeof window.DeepSeekAndroid?.loginWithDeepSeek === 'function'
  const webAccountLogin = supportsWebAccountLogin()
  const accountLoginAvailable = nativeAccountLogin || webAccountLogin
  const [accountLoginBusy, setAccountLoginBusy] = useState(false)
  const [accountLoginError, setAccountLoginError] = useState<string | null>(null)

  useEffect(() => {
    const reset = (event: Event) => {
      setAccountLoginBusy(false)
      const detail = (event as CustomEvent<{ error?: string }>).detail
      if (detail?.error) {
        setAccountLoginError(detail.error)
      }
    }
    window.addEventListener('deepseek-account-login-result', reset)
    return () => window.removeEventListener('deepseek-account-login-result', reset)
  }, [])

  const loginWithAccount = async () => {
    setAccountLoginError(null)
    setAccountLoginBusy(true)
    if (nativeAccountLogin) {
      window.DeepSeekAndroid?.loginWithDeepSeek()
      return
    }
    try {
      const locale = document.documentElement.lang
      const key = await loginWithWebAccount(locale)
      setApiKey(key)
      markApiKeyValid()
      void refreshPlatformBalance()
      showToast({ key: 'accountLoginSuccess' })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      setAccountLoginError(message)
      showToast({
        key: 'accountLoginFailed',
        type: 'error',
        params: { message },
      })
    } finally {
      setAccountLoginBusy(false)
    }
  }

  const keyValid = Boolean(apiKey && apiKeyStatus === 'valid')
  const keyInvalid = Boolean(apiKey && apiKeyStatus === 'invalid')
  const keyChecking = Boolean(apiKey) && !accountLoginBusy && apiKeyStatus === 'checking'
  const loginButtonText = accountLoginBusy
    ? t('accountLoginCreatingKey')
    : keyChecking
      ? t('apiKeyVerifying')
      : keyInvalid
        ? t('accountLoginRefresh')
        : t('accountLoginButton')

  return (
    <div className={styles.cell}>
      <div className={styles.apiKey}>
        <div className={styles.label}>
          {accountLoginAvailable ? t('accountLoginStart') : t('apiKeyStart')}
        </div>
        {accountLoginAvailable ? (
          <>
            {accountLoginError ? <div className={styles.error}>{accountLoginError}</div> : null}
            {keyValid ? (
              <div className={styles.accountLoginStatus} role="status">
                <IconCheckOutline16 />
                {t('accountLoginReady')}
              </div>
            ) : (
              <Button
                size="m"
                block
                className={styles.accountLoginButton}
                disabled={accountLoginBusy || keyChecking}
                onClick={() => void loginWithAccount()}
              >
                {loginButtonText}
              </Button>
            )}
            <div className={styles.accountLoginHint}>
              {keyInvalid
                ? t('accountLoginInvalidHint')
                : keyValid
                  ? t('accountLoginReadyHint')
                  : t('accountLoginHint')}
            </div>
            {!apiKey && !editing ? (
              <div className={styles.accountLoginDivider}>{t('accountOrApiKey')}</div>
            ) : null}
          </>
        ) : null}
        {apiKey && !editing ? (
          <div className={styles.apiKeySaved}>
            <span className={styles.apiKeyMask}>{'*'.repeat(apiKey.length)}</span>
            <IconButton
              tone="primary"
              icon={<IconEditOutline16 />}
              tooltip={t('apiKeyEdit')}
              onClick={() => setEditing(true)}
            />
          </div>
        ) : (
          <div className={styles.apiKeyInput}>
            <input
              type="password"
              placeholder={t('apiKeyPlaceholder')}
              autoComplete="off"
              spellCheck={false}
              // 移动端打开设置时不弹键盘，点编辑后才聚焦
              autoFocus={!isMobile || editing}
              value={form.value}
              onChange={(event) => form.setValue(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
                  void form.submit()
                }
              }}
              onBlur={() => {
                // 修改时没输入就离开，回到已保存的状态
                if (apiKey && !form.value.trim()) {
                  setEditing(false)
                }
              }}
            />
            {!isMobile && form.value.trim() ? (
              <>
                <span className={styles.divider} />
                <button
                  type="button"
                  className={styles.confirm}
                  disabled={form.verifying}
                  // 保持输入框焦点
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => void form.submit()}
                >
                  {form.verifying ? t('apiKeyVerifying') : t('confirm')}
                </button>
              </>
            ) : null}
          </div>
        )}
        {showInput && form.error ? <div className={styles.error}>{form.error}</div> : null}
      </div>
    </div>
  )
}

/** 移动端：输入 API Key 时在选项下方显示整行「确定」 */
export const ApiKeyConfirm = ({ editor: { showInput, form } }: { editor: ApiKeyEditor }) => {
  const t = useTranslate()
  return showInput && form.value.trim() ? (
    <button
      type="button"
      className={styles.mobileConfirm}
      disabled={form.verifying}
      onMouseDown={(event) => event.preventDefault()}
      onClick={() => void form.submit()}
    >
      {form.verifying ? t('apiKeyVerifying') : t('confirm')}
    </button>
  ) : null
}
