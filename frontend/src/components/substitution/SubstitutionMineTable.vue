<template>
  <el-card shadow="never">
    <template #header><span class="card-title">我的申请</span></template>
    <EmptyStateGuide
      v-if="requests.length === 0"
      title="还没有调代申请"
      description="请假或调课时在上方选择日期与课节提交申请，审批进度会显示在这里。"
      help-slug="substitution"
      help-anchor="apply"
    />
    <el-table v-else :data="requests" stripe>
      <el-table-column label="类型" width="80">
        <template #default="{ row }">
          <el-tag :type="row.type === 'SWAP' ? 'warning' : 'info'" size="small">{{ typeLabel(row.type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="effectiveDate" label="日期" width="120" />
      <el-table-column label="节次" width="180">
        <template #default="{ row }">
          <span v-if="row.type === 'SWAP'">{{ row.periodLabel }} → {{ row.targetPeriodLabel }}</span>
          <span v-else>{{ row.periodLabel }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="className" label="行政班" min-width="110" />
      <el-table-column prop="courseName" label="课程" width="90" />
      <el-table-column v-if="requests.some((item) => item.type === 'COVER')" label="代课教师" width="110">
        <template #default="{ row }">{{ row.substituteTeacherName || '待定' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="150" show-overflow-tooltip />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button v-if="row.type === 'SWAP'" link type="warning" @click="openPlans(row)">方案</el-button>
          <el-button link type="primary" @click="openHistory(row.id)">流转记录</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="historyVisible" title="流转记录" width="560px">
      <el-timeline>
        <el-timeline-item
          v-for="item in history"
          :key="item.id"
          :timestamp="formatTime(item.createdAt)"
          placement="top"
        >
          <div class="history-title">
            {{ item.fromStatus ? statusLabel(item.fromStatus) : '提交' }} → {{ statusLabel(item.toStatus) }}
          </div>
          <div v-if="item.actor" class="history-meta">操作人：{{ item.actor }}</div>
          <div v-if="item.comment" class="history-meta">备注：{{ item.comment }}</div>
        </el-timeline-item>
      </el-timeline>
    </el-dialog>

    <el-dialog v-model="plansVisible" title="调课方案" width="720px">
      <el-empty v-if="plans.length === 0" description="教务还没有生成方案" />
      <div v-for="plan in plans" :key="plan.id" class="plan-summary">
        <div class="plan-head">
          <el-tag size="small" type="warning">{{ plan.rankingPresetLabel }}</el-tag>
          <span>影响 {{ plan.impactDays }} 天 · {{ plan.impactTeachers }} 位教师 · 软分 {{ plan.softPenalty }}</span>
          <el-tag size="small" :type="statusType(plan.status)">{{ statusLabel(plan.status) }}</el-tag>
        </div>
        <div v-for="move in plan.moves" :key="move.entryId" class="plan-move">
          {{ move.teacherName }} 的 {{ move.courseName }}：{{ move.fromPeriodLabel }} → {{ move.toPeriodLabel }}
        </div>
      </div>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { http } from '@/api/http'
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import type { HistoryRow, Plan, RequestRow } from './types'
import { formatTime, statusLabel, statusType, typeLabel } from './types'

defineProps<{ requests: RequestRow[] }>()

const historyVisible = ref(false)
const history = ref<HistoryRow[]>([])
const plansVisible = ref(false)
const plans = ref<Plan[]>([])

async function openHistory(requestId: number) {
  try {
    const { data } = await http.get(`/substitution-requests/${requestId}/history`)
    history.value = data
    historyVisible.value = true
  } catch {
    // not visible to this account
  }
}

async function openPlans(row: RequestRow) {
  try {
    const { data } = await http.get(`/substitution-requests/${row.id}/plans`)
    plans.value = data
    plansVisible.value = true
  } catch {
    plans.value = []
    plansVisible.value = true
  }
}

defineExpose({ openHistory })
</script>

<style scoped>
.card-title {
  font-weight: 600;
}
.history-title {
  font-weight: 600;
  font-size: 14px;
}
.history-meta {
  color: var(--paiban-muted);
  font-size: 12px;
  margin-top: 3px;
}
.plan-summary {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
}
.plan-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.plan-move {
  color: var(--paiban-muted);
  font-size: 13px;
  margin-top: 6px;
}
</style>
