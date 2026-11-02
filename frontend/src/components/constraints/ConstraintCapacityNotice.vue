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
import { ElAlert } from 'element-plus'
import { useConstraintCapacity } from './useConstraintCapacity'
import type { ConstraintConfigRequest } from './types'
const props = defineProps<{ semesterId: number | null; configs: ConstraintConfigRequest[]; incomplete: boolean }>()
const { warnings, error } = useConstraintCapacity(props)
</script>
