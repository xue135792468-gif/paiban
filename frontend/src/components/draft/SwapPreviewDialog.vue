<template>
  <el-dialog
    :model-value="visible"
    title="换课预览"
    width="92%"
    top="4vh"
    @update:model-value="(value: boolean) => emit('update:visible', value)"
  >
    <DualTimetablePreview
      v-if="source && candidate"
      v-model:swap-rooms="roomsModel"
      :periods="periods"
      :lessons="lessons"
      :courses="courses"
      :source="source"
      :candidate="candidate"
    />
    <template #footer>
      <el-button @click="emit('cancel')">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="emit('confirm')">
        确认换课
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import DualTimetablePreview from './DualTimetablePreview.vue'
import type { DraftCourse, DraftLesson, DraftPeriod, SwapCandidate } from './types'

const props = withDefaults(
  defineProps<{
    visible: boolean
    periods: DraftPeriod[]
    lessons: DraftLesson[]
    courses: DraftCourse[]
    source: DraftLesson | null
    candidate: SwapCandidate | null
    swapRooms: boolean
    submitting?: boolean
  }>(),
  { submitting: false },
)

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'update:swapRooms', value: boolean): void
  (e: 'cancel'): void
  (e: 'confirm'): void
}>()

const roomsModel = computed({
  get: () => props.swapRooms,
  set: (value: boolean) => emit('update:swapRooms', value),
})
</script>
