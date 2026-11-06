import { ElMessageBox } from 'element-plus'

/**
 * 「＋ 新建教师组 / 课程组」: ask for a name.
 * Returns the trimmed name, or null when the user cancelled or typed nothing.
 */
export async function promptGroupName(label: '教师组' | '课程组'): Promise<string | null> {
  try {
    const result = await ElMessageBox.prompt(`给这个${label}起个名字`, `新建${label}`, {
      inputPattern: /^.{1,64}$/,
      inputErrorMessage: '组名需要 1–64 个字',
      confirmButtonText: '创建',
      cancelButtonText: '取消',
    })
    return String(result.value ?? '').trim() || null
  } catch {
    return null
  }
}
