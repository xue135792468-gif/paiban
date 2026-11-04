<template>
  <div class="job-diagnostics" data-testid="job-diagnostics">
    <div class="diagnostics-title">排课卡在以下教师，可连带重排相关年级</div>
    <div
      v-for="row in diagnostics"
      :key="row.teacherId"
      class="diagnostic-row"
      data-testid="job-diagnostic"
    >
      <div class="diagnostic-head">
        <strong class="teacher-name">{{ row.teacherName || `教师 ${row.teacherId}` }}</strong>
        <el-tag type="danger" size="small" effect="light">{{ row.hardCount }} 处硬冲突</el-tag>
        <span v-if="row.classNames?.length" class="class-names">
          {{ row.classNames.join('、') }}
        </span>
      </div>
      <ul class="diagnostic-messages">
        <li v-for="message in row.messages" :key="message">{{ message }}</li>
      </ul>
    </div>
    <el-button
      type="primary"
      plain
      data-testid="rerun-diagnosed-grades"
      @click="emit('rerun')"
    >
      连带重排这些年级
    </el-button>
  </div>
</template>

<script setup lang="ts">
import type { JobDiagnostic } from './types'

defineProps<{ diagnostics: JobDiagnostic[] }>()
const emit = defineEmits<{ rerun: [] }>()
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

.diagnostics-title {
  font-weight: 600;
  font-size: 14px;
  color: #b91c1c;
}

.diagnostic-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
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

.class-names {
  color: var(--paiban-muted);
  font-size: 12px;
}

.diagnostic-messages {
  margin: 0;
  padding-left: 18px;
  color: #7f1d1d;
  font-size: 12px;
  line-height: 1.5;
}
</style>
