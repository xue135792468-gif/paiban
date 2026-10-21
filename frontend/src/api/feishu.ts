/**
 * Feishu H5 (Lark) environment helpers.
 *
 * Feishu injects `window.tt` into the H5 container, so no external SDK is loaded.
 * The user-agent check covers clients where the JSAPI is injected slightly after
 * the first script runs.
 */
export interface FeishuPublicConfig {
  enabled: boolean
  appId?: string
}

export function isFeishuEnv(): boolean {
  if (typeof window === 'undefined') return false
  return Boolean((window as any).tt) || /Lark|Feishu/i.test(navigator.userAgent)
}

export function requestFeishuAuthCode(appId: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const tt = (window as any).tt
    if (!tt || typeof tt.requestAccess !== 'function') {
      reject(new Error('当前环境不支持飞书免登'))
      return
    }
    const handleSuccess = (result: any) => {
      const code = result?.code ?? result?.authCode
      if (code) {
        resolve(code)
      } else {
        reject(new Error('飞书未返回授权码'))
      }
    }
    try {
      const pending = tt.requestAccess({
        appID: appId,
        scopeList: [],
        success: handleSuccess,
        fail: (error: any) => reject(new Error(error?.errMsg ?? '飞书授权失败')),
      })
      // Newer JSAPI versions also return a promise; resolving twice is harmless.
      if (pending && typeof pending.then === 'function') {
        pending.then(handleSuccess).catch((error: any) => reject(error))
      }
    } catch (error) {
      reject(error)
    }
  })
}
