import { ElMessageBox } from 'element-plus'

/**
 * One shared "are you sure?" for destructive actions. Ticket 04 deleted rows go to
 * the 7-day recycle bin, so the call sites pass the sentence that says how to undo.
 */
export async function confirmRemoval(message: string, title: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning' })
    return true
  } catch {
    return false
  }
}
