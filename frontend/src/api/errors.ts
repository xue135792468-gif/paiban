/**
 * The API returns a human readable `message` for rejected requests; screens fall
 * back to their own wording when the body is missing (network error, proxy page).
 */
export function serverMessage(error: unknown, fallback: string): string {
  const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message ?? fallback
}

/**
 * AI parsing can run for minutes. A timeout or a dropped connection is not a
 * generic failure: tell the user the parse timed out and what to do about it.
 */
export function aiParseErrorMessage(error: unknown): string {
  const response = (error as { response?: { data?: { message?: string } } })?.response
  if (response?.data?.message) {
    return response.data.message
  }
  const code = (error as { code?: string } | null | undefined)?.code
  if (!response || code === 'ECONNABORTED' || code === 'ETIMEDOUT' || code === 'ERR_NETWORK') {
    return 'AI 解析超时，请重试或拆分文件'
  }
  return '操作失败，请稍后重试'
}
