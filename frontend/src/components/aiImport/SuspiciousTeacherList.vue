<template>
  <div class="suspicious-teachers" data-testid="teacher-warnings">
    <el-alert
      type="warning"
      show-icon
      :closable="false"
      :title="`疑似教师值 ${warnings.length} 个（可能是课程名或占位词，不影响入库）`"
    />
    <el-table :data="warnings" size="small" border data-testid="teacher-warnings-table">
      <el-table-column prop="value" label="教师值" min-width="140" />
      <el-table-column label="课节" width="92">
        <template #default="{ row }">
          {{ row.lessonCount ? `${row.lessonCount} 节` : '教师名单' }}
        </template>
      </el-table-column>
      <el-table-column label="命中原因" min-width="220">
        <template #default="{ row }">{{ reasonsOf(row) }}</template>
      </el-table-column>
    </el-table>
    <p class="hint">
      可以先照常入库，再
      <el-button link type="primary" size="small" data-testid="teacher-warnings-rename" @click="openTeachers">
        到教师页改名
      </el-button>
      ：课节按教师 id 引用，改名不会断开草稿。
    </p>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import type { TeacherWarning } from './timetablePreview'

defineProps<{ warnings: TeacherWarning[] }>()

const router = useRouter()

function reasonsOf(warning: TeacherWarning): string {
  return (warning.reasons ?? []).map((reason) => reason.message).join('；')
}

function openTeachers() {
  router.push('/admin/teachers')
}
</script>

<style scoped>
.suspicious-teachers {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.hint {
  margin: 0;
  color: #6b7280;
  font-size: 12px;
}
</style>
