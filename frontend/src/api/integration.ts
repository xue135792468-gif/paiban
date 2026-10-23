/** Result of an organisation sync (`/integrations/{platform}/sync`). */
export interface SyncResult {
  success: boolean
  created: number
  updated: number
  deactivated: number
  departmentsScanned: number
  usersScanned: number
  elapsedMs: number
  message: string
}
