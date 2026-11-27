import { ref, type ModelRef } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'

/** Each card owns its keys; a card save never overwrites unrelated settings. */
export function useSettingsSection(form: ModelRef<Record<string, string>>, keys: string[]) {
  const saving = ref(false)

  async function save(successMessage: string) {
    saving.value = true
    try {
      const payload = Object.fromEntries(keys.map((key) => [key, form.value[key] ?? '']))
      const { data } = await http.put<Record<string, string>>('/settings', payload)
      Object.assign(form.value, data)
      ElMessage.success(successMessage)
      return true
    } catch (error) {
      ElMessage.error(serverMessage(error, '保存失败'))
      return false
    } finally {
      saving.value = false
    }
  }

  return { saving, save }
}
