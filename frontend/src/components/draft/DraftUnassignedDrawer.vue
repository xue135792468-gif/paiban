<template>
  <el-card shadow="never" class="drawer-card">
    <template #header>
      <div class="drawer-header">
        <span>未排课节</span>
        <el-button link @click="emit('close')">收起</el-button>
      </div>
    </template>
    <div v-if="lessons.length === 0" class="drawer-empty">没有未排课的授课任务</div>
    <div
      v-for="lesson in lessons"
      :key="lesson.id"
      class="drawer-lesson"
      :data-lesson-id="lesson.id"
      :style="{ background: courseColor(courses, lesson.courseId) }"
      :draggable="draggable"
      @dragstart="emit('drag-start', lesson)"
      @dragend="emit('drag-end')"
    >
      <div class="lesson-course">{{ lesson.courseName }}</div>
      <div class="lesson-meta">{{ lesson.className }} · {{ lesson.teacherName }}</div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { courseColor } from './colors'
import type { DraftCourse, DraftLesson } from './types'

defineProps<{
  lessons: DraftLesson[]
  courses: DraftCourse[]
  draggable: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'drag-start', lesson: DraftLesson): void
  (e: 'drag-end'): void
}>()
</script>

<style scoped>
.drawer-card {
  width: 240px;
  border: none;
}
.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
.drawer-empty {
  color: var(--paiban-muted);
  font-size: 13px;
  text-align: center;
  padding: 20px 0;
}
.drawer-lesson {
  margin-bottom: 8px;
  border-radius: 8px;
  padding: 6px 8px;
  cursor: grab;
  border: 1px solid rgba(0, 0, 0, 0.06);
  color: #2b3245;
  overflow: hidden;
}
.drawer-lesson .lesson-course {
  font-weight: 600;
  font-size: 13px;
}
.drawer-lesson .lesson-meta {
  font-size: 11px;
  margin-top: 3px;
  color: #4b5563;
}
</style>
