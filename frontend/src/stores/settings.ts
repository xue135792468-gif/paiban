import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { http } from '@/api/http'

export type SchoolType = 'junior' | 'senior'

/**
 * UI-only school mode from ADR-0018. Business APIs never read it; trimmed
 * views use it to hide senior-school features.
 */
export const useSettingsStore = defineStore('settings', () => {
  const schoolType = ref<SchoolType>('junior')

  const isJunior = computed(() => schoolType.value === 'junior')

  /** Failures keep the junior default so the simplest UI stays available. */
  async function load() {
    try {
      const { data } = await http.get('/settings/public')
      if (data?.schoolType === 'junior' || data?.schoolType === 'senior') {
        schoolType.value = data.schoolType
      }
    } catch {
      // settings are non-critical for rendering; stay on junior
    }
  }

  return { schoolType, isJunior, load }
})
