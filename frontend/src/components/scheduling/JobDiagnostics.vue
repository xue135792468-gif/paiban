<template>
  <div class="job-diagnostics" data-testid="job-diagnostics">
    <div class="diagnostics-header">
      <div class="diagnostics-title">排课卡在以下教师，可连带重排相关年级</div>
      <el-button
        type="primary"
        size="small"
        data-testid="rerun-diagnosed-grades"
        @click="emit('rerun')"
      >
        连带重排这些年级
      </el-button>
    </div>

    <div
      v-for="row in diagnostics"
      :key="row.teacherId"
      class="diagnostic-row"
      data-testid="job-diagnostic"
    >
      <div class="diagnostic-head">
        <strong class="teacher-name">{{ row.teacherName || `教师 ${row.teacherId}` }}</strong>
        <span class="dot">·</span>
        <el-tag type="danger" size="small" effect="light">{{ row.hardCount }} 处硬冲突</el-tag>
        <span v-if="row.classNames?.length" class="class-summary">
          （涉及 {{ row.classNames.length }} 个班）
        </span>
        <el-button
          v-if="hasDetails(row)"
          link
          type="primary"
          size="small"
          :data-testid="`toggle-diagnostic-${row.teacherId}`"
          @click="toggle(row.teacherId)"
        >
          {{ isExpanded(row.teacherId) ? '收起明细' : '展开明细' }}
        </el-button>
      </div>

      <div v-if="isExpanded(row.teacherId)" class="diagnostic-details" data-testid="diagnostic-details">
        <div v-if="row.classNames?.length" class="class-names">
          涉及班级：{{ row.classNames.join('、') }}
        </div>
        <ul v-if="row.messages?.length" class="diagnostic-messages">
          <li v-for="(message, idx) in row.messages" :key="`${idx}-${message}`">{{ message }}</li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import type { JobDiagnostic } from './types'

const props = defineProps<{ diagnostics: JobDiagnostic[] }>()
const emit = defineEmits<{ rerun: [] }>()

const expandedTeacherIds = ref<Set<number>>(new Set())

watch(
  () => props.diagnostics,
  () => {
    expandedTeacherIds.value = new Set()
  },
)

function hasDetails(row: JobDiagnostic): boolean {
  return (row.messages?.length ?? 0) > 0 || (row.classNames?.length ?? 0) > 0
}

function isExpanded(teacherId: number): boolean {
  return expandedTeacherIds.value.has(teacherId)
}

function toggle(teacherId: number) {
  const next = new Set(expandedTeacherIds.value)
  if (next.has(teacherId)) {
    next.delete(teacherId)
  } else {
    next.add(teacherId)
  }
  expandedTeacherIds.value = next
}
</script>

<style scoped>
.job-diagnostics {
  border: 1px solid #fecaca;
  background: #fff7f7;
  border-radius: 8px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.diagnostics-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.diagnostics-title {
  font-weight: 600;
  font-size: 14px;
  color: #b91c1c;
}

.diagnostic-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.diagnostic-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.teacher-name {
  font-size: 14px;
}

.dot {
  color: var(--paiban-muted);
}

.class-summary,
.class-names {
  color: var(--paiban-muted);
  font-size: 12px;
}

.diagnostic-details {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-left: 8px;
  border-left: 2px solid #fecaca;
}

.diagnostic-messages {
  margin: 0;
  padding-left: 18px;
  color: #7f1d1d;
  font-size: 12px;
  line-height: 1.5;
}
</style>
