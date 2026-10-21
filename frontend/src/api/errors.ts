/**
 * The API returns a human readable `message` for rejected requests; screens fall
 * back to their own wording when the body is missing (network error, proxy page).
 */
export function serverMessage(error: unknown, fallback: string): string {
  const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message ?? fallback
}
