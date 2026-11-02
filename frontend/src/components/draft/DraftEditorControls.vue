<template>
  <div class="controls">
    <el-radio-group
      :model-value="viewMode"
      @update:model-value="onViewChange"
    >
      <el-radio-button value="class">按行政班</el-radio-button>
      <el-radio-button value="teacher">按教师</el-radio-button>
      <el-radio-button value="room">按教室</el-radio-button>
    </el-radio-group>
    <el-select
      :model-value="targetId"
      filterable
      style="width: 220px"
      placeholder="选择对象"
      @update:model-value="emit('update:targetId', $event)"
    >
      <el-option
        v-for="option in targetOptions"
        :key="option.id"
        :label="option.name"
        :value="option.id"
      />
    </el-select>
    <div class="spacer" />
    <template v-if="selectedLesson">
      <el-tag type="primary" effect="plain" closable @close="emit('clear-selection')">
        已选：{{ selectedLesson.courseName }} · {{ selectedLesson.teacherName }} ·
        {{ selectedLesson.periodLabel }}
      </el-tag>
      <el-button
        type="primary"
        :disabled="!draftEditable || selectedLesson.periodId == null"
        @click="emit('swap')"
      >
        换课
      </el-button>
      <el-button
        :disabled="!draftEditable || selectedLesson.periodId == null"
        @click="emit('adjust-position')"
      >
        调整位置
      </el-button>
    </template>
    <el-button @click="emit('toggle-drawer')">
      <el-icon><Tickets /></el-icon>
      <span>未排课节（{{ unassignedCount }}）</span>
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { Tickets } from '@element-plus/icons-vue'
import type { DraftLesson } from './types'

defineProps<{
  viewMode: 'class' | 'teacher' | 'room'
  targetId: number | null
  targetOptions: Array<{ id: number; name: string }>
  selectedLesson: DraftLesson | null
  draftEditable: boolean
  unassignedCount: number
}>()

const emit = defineEmits<{
  (e: 'update:viewMode', value: 'class' | 'teacher' | 'room'): void
  (e: 'update:targetId', value: number | null): void
  (e: 'view-change'): void
  (e: 'swap'): void
  (e: 'adjust-position'): void
  (e: 'toggle-drawer'): void
  (e: 'clear-selection'): void
}>()

function onViewChange(value: string | number | boolean | undefined) {
  const next = value as 'class' | 'teacher' | 'room'
  emit('update:viewMode', next)
  emit('view-change')
}
</script>

<style scoped>
.controls {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.spacer {
  flex: 1;
}
</style>
