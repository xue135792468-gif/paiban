<template>
  <el-alert
    v-if="warnings.length || error" type="warning" :closable="false" show-icon
    data-testid="constraint-capacity-warning"
  >
    <template #title>{{ error || '按当前配置，有些班级的课时放不下' }}</template>
    <p v-for="message in warnings" :key="message">{{ message }}</p>
    <p v-if="warnings.length">配置可以保存；请增加可用位置、减少留空时段或核对授课任务后再排课。容量足够也仍需排课检查其他冲突。</p>
  </el-alert>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert } from 'element-plus'
import { http } from '@/api/http'
import type { ConstraintConfigRequest } from './types'
const props = defineProps<{ semesterId: number | null; configs: ConstraintConfigRequest[]; incomplete: boolean }>()
const warnings = ref<string[]>([])
const error = ref('')
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0
watch(() => [props.semesterId, JSON.stringify(props.configs), props.incomplete] as const, () => {
  const current = ++generation
  clearTimeout(timer)
  warnings.value = []
  error.value = ''
  if (!props.semesterId || props.incomplete) return
  timer = setTimeout(async () => {
    try {
      const { data } = await http.post<{ warnings?: string[] }>('/constraints/preview',
        { configs: props.configs }, { params: { semesterId: props.semesterId } })
      if (current === generation) warnings.value = data.warnings ?? []
    } catch {
      if (current === generation) error.value = '暂时无法检查课时容量，请在开始排课时查看系统检查结果。'
    }
  }, 350)
}, { immediate: true })
onBeforeUnmount(() => { ++generation; clearTimeout(timer) })
</script>
