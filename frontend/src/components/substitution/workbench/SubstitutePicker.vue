<template>
  <el-card shadow="never" data-testid="substitute-picker">
    <template #header>
      <div class="card-head">
        <span class="card-title">为 {{ sourceLabel }} 指定代课人</span>
        <el-tag v-if="info?.legacyLocked" type="danger" data-testid="legacy-locked">旧格式只读</el-tag>
      </div>
    </template>

    <el-alert
      v-if="info?.legacyLocked"
      title="该课节当前生效的是旧格式调代记录（历史数据），需先完成历史转换，管理端暂不能修改。"
      type="error"
      :closable="false"
      show-icon
      class="notice"
    />
    <el-alert
      v-else-if="info?.activeRequestId"
      :title="`该课节已有待处理的申请（#${info.activeRequestId}），请先在待审批中处理该单。`"
      type="warning"
      :closable="false"
      show-icon
      class="notice"
      data-testid="active-request-alert"
    />

    <template v-else>
      <p class="hint">同科教师优先排列；跨科可选但会提示；忙的教师会给出冲突原因，最终以服务端校验为准。</p>
      <el-table :data="candidates" size="small" data-testid="candidate-table">
        <el-table-column label="教师" min-width="140">
          <template #default="{ row }">
            {{ row.name }}
            <el-tag v-if="row.sameSubject" size="small" type="success">同科</el-tag>
            <el-tag v-else size="small" type="warning">跨科</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="subject" label="学科" min-width="90" />
        <el-table-column label="状态" min-width="220">
          <template #default="{ row }">
            <span v-if="row.busyAtPeriod" class="busy" :data-testid="`busy-${row.teacherId}`">
              {{ row.busyReason }}
            </span>
            <span v-else class="free">该时段空闲</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button
              size="small"
              type="primary"
              plain
              :data-testid="`choose-${row.teacherId}`"
              @click="emit('choose', row)"
            >
              选择
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { WorkbenchCandidate, WorkbenchCandidateList } from './types'

const props = defineProps<{ info: WorkbenchCandidateList | null; selected: WorkbenchCandidate | null }>()

const emit = defineEmits<{ choose: [candidate: WorkbenchCandidate] }>()

const sourceLabel = computed(() =>
  props.info
    ? `${props.info.className ?? ''} ${props.info.courseName ?? ''} ${props.info.periodLabel}（${props.info.lessonDate}）`
    : '',
)
const candidates = computed(() => props.info?.candidates ?? [])
</script>

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.card-title {
  font-weight: 600;
}
.hint {
  color: var(--paiban-muted);
  font-size: 12px;
  margin: 0 0 8px;
}
.notice {
  margin-bottom: 8px;
}
.busy {
  color: var(--el-color-danger);
  font-size: 12px;
}
.free {
  color: var(--el-color-success);
  font-size: 12px;
}
</style>
