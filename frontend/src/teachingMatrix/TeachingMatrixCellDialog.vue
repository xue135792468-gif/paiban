<template>
  <el-dialog
    :model-value="true" :title="`${selection.adminClass.name} · ${selection.course.name}`"
    width="min(440px, 94vw)" :show-close="!saving" :close-on-click-modal="false"
    :close-on-press-escape="!saving" @close="$emit('close')"
  >
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="任课教师">
        <el-select v-model="teacherId" aria-label="任课教师" filterable>
          <el-option v-for="teacher in teachers" :key="teacher.id" :label="teacher.name" :value="teacher.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="每周节数">
        <el-input v-model="hours" aria-label="每周节数" inputmode="numeric" placeholder="1–20" />
      </el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" :closable="false" type="error" show-icon />
    <template #footer>
      <el-button v-if="selection.assignment" type="danger" plain :disabled="saving" @click="$emit('delete')">删除</el-button>
      <el-button :disabled="saving" @click="$emit('close')">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import type { MatrixCell, MatrixClass } from './matrix'

const props = defineProps<{
  selection: MatrixCell & { semesterId: number; adminClass: MatrixClass }
  teachers: { id: number; name: string }[]
}>()
const emit = defineEmits<{ close: []; saved: []; delete: [] }>()
const assignment = props.selection.assignment
const teacherId = ref(assignment?.teacherId)
const hours = ref(String(assignment?.weeklyLessons ?? props.selection.course.weeklyHours ?? ''))
const saving = ref(false)
const error = ref('')
const teachers = computed(() => {
  if (!assignment || props.teachers.some((item) => item.id === assignment.teacherId)) return props.teachers
  return [...props.teachers, { id: assignment.teacherId, name: assignment.teacherName ?? '原任课教师' }]
})

async function save() {
  if (saving.value) return
  if (teacherId.value == null || !/^(?:[1-9]|1[0-9]|20)$/.test(hours.value)) {
    error.value = '请选择任课教师，并填写 1–20 的整数节数'
    return
  }
  const { semesterId, adminClass, course } = props.selection
  const payload = { classId: adminClass.id, courseId: course.id, teacherId: teacherId.value, weeklyLessons: Number(hours.value) }
  saving.value = true
  error.value = ''
  try {
    if (assignment) await http.put(`/assignments/${assignment.id}`, payload)
    else await http.post('/assignments', payload, { params: { semesterId } })
    emit('saved')
  } catch (cause) {
    error.value = serverMessage(cause, '保存授课任务失败')
  } finally {
    saving.value = false
  }
}
</script>
