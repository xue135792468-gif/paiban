<template>
  <el-card shadow="never" class="filter-card">
    <div class="filters">
      <el-select v-model="filter.classId" filterable clearable placeholder="行政班" style="width: 170px">
        <el-option v-for="item in classOptions" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-select v-model="filter.teacherId" filterable clearable placeholder="教师" style="width: 170px">
        <el-option v-for="teacher in teachers" :key="teacher.id" :label="teacher.name" :value="teacher.id" />
      </el-select>
      <el-select v-model="filter.type" clearable placeholder="类型" style="width: 120px">
        <el-option label="调课" value="SWAP" />
        <el-option label="代课" value="COVER" />
      </el-select>
      <el-date-picker
        v-model="filter.range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
      />
      <el-button type="primary" @click="loadRecords">查询</el-button>
      <el-button @click="exportRecords">导出</el-button>
    </div>
    <div v-if="stats" class="stats">
      <span>教师：{{ stats.teacherName }}</span>
      <span>被代课 <b>{{ stats.substitutedOut }}</b> 次</span>
      <span>代课 <b>{{ stats.substitutedIn }}</b> 次</span>
      <span>参与调课 <b>{{ stats.swapParticipation }}</b> 次</span>
      <span>周课时 <b>{{ stats.weeklyLessons }}</b></span>
      <span>净课时（含代课） <b>{{ stats.netLessons }}</b></span>
    </div>
  </el-card>

  <el-card shadow="never">
    <el-table v-if="loading || records.length > 0" v-loading="loading" :data="records" stripe @expand-change="onExpand">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div v-if="row.type === 'SWAP'" class="expand-body">
            <div v-if="!planMoves[row.id]" class="hint">加载方案…</div>
            <div v-for="move in planMoves[row.id] || []" :key="move.entryId" class="plan-move">
              <el-tag size="small" type="info">{{ move.teacherName }}</el-tag>
              {{ move.courseName }} {{ move.fromPeriodLabel }} → {{ move.toPeriodLabel }}
              <span class="hint">{{ move.toDate }}</span>
            </div>
          </div>
          <div v-else class="expand-body hint">代课为单向补位，不涉及其他课节移动。</div>
        </template>
      </el-table-column>
      <el-table-column prop="effectiveDate" width="115">
        <template #header>
          生效日期
          <InfoTooltip field="substitution.effectiveDate" />
        </template>
      </el-table-column>
      <el-table-column label="类型" width="80">
        <template #default="{ row }">
          <el-tag :type="row.type === 'SWAP' ? 'warning' : 'info'" size="small">{{ typeLabel(row.type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="className" label="行政班" min-width="110" />
      <el-table-column prop="courseName" label="课程" width="90" />
      <el-table-column label="节次" width="180">
        <template #default="{ row }">
          <span v-if="row.type === 'SWAP'">{{ row.fromPeriodLabel }} → {{ row.toPeriodLabel }}</span>
          <span v-else>{{ row.toPeriodLabel }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="originalTeacherName" label="原教师" width="100" />
      <el-table-column label="代课教师" width="100">
        <template #default="{ row }">{{ row.type === 'COVER' ? row.substituteTeacherName : '—' }}</template>
      </el-table-column>
      <el-table-column prop="roomName" label="教室" width="100" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag type="success" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
    </el-table>
    <EmptyStateGuide
      v-else
      title="还没有调代记录"
      description="调课方案或代课审批通过并生效后，会在这里留下记录，方便期末统计与追溯。"
      help-slug="substitution"
      help-anchor="records"
    />
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { http } from '@/api/http'
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import InfoTooltip from '@/components/InfoTooltip.vue'
import type { Plan, PlanMove, RecordRow, Stats, Teacher } from './types'
import { statusLabel, typeLabel } from './types'

const props = defineProps<{
  classOptions: Array<{ id: number; name: string }>
  teachers: Teacher[]
  canViewAll: boolean
}>()

const records = ref<RecordRow[]>([])
const stats = ref<Stats | null>(null)
const loading = ref(false)
const planMoves = ref<Record<number, PlanMove[]>>({})
const filter = reactive({
  classId: null as number | null,
  teacherId: null as number | null,
  type: '' as '' | 'SWAP' | 'COVER',
  range: [] as string[],
})

async function loadRecords() {
  if (!props.canViewAll && !filter.classId) {
    filter.classId = props.classOptions.length > 0 ? props.classOptions[0].id : null
    if (!filter.classId) return
  }
  loading.value = true
  try {
    const { data } = await http.get('/substitutions', { params: buildParams() })
    records.value = data
    planMoves.value = {}
    if (filter.teacherId) {
      const statsRes = await http.get('/substitutions/stats', { params: { teacherId: filter.teacherId } })
      stats.value = statsRes.data
    } else {
      stats.value = null
    }
  } finally {
    loading.value = false
  }
}

function buildParams() {
  const params: Record<string, unknown> = {}
  if (filter.classId) params.classId = filter.classId
  if (filter.teacherId) params.teacherId = filter.teacherId
  if (filter.type) params.type = filter.type
  if (filter.range && filter.range.length === 2) {
    params.dateFrom = filter.range[0]
    params.dateTo = filter.range[1]
  }
  return params
}

async function exportRecords() {
  const response = await http.get('/substitutions/export', {
    params: buildParams(),
    responseType: 'blob',
  })
  const url = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = url
  link.download = 'substitutions.csv'
  link.click()
  URL.revokeObjectURL(url)
}

async function onExpand(row: RecordRow, expandedRows: RecordRow[]) {
  const expanded = Array.isArray(expandedRows)
    ? expandedRows.some((item) => item.id === row.id)
    : Boolean(expandedRows)
  if (!expanded || row.type !== 'SWAP' || planMoves.value[row.id]) return
  try {
    const { data } = await http.get<Plan[]>(`/substitution-requests/${row.requestId}/plans`)
    const plan = data.find((item) => item.id === row.planId) ?? data[0]
    planMoves.value = { ...planMoves.value, [row.id]: plan?.moves ?? [] }
  } catch {
    planMoves.value = { ...planMoves.value, [row.id]: [] }
  }
}

defineExpose({ reload: loadRecords })

onMounted(loadRecords)
</script>

<style scoped>
.filter-card {
  margin-bottom: 16px;
}
.filters {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.stats {
  display: flex;
  gap: 24px;
  margin-top: 12px;
  color: #4b5563;
  font-size: 13px;
}
.expand-body {
  padding: 4px 24px 8px;
}
.plan-move {
  font-size: 13px;
  margin-top: 6px;
}
.hint {
  color: var(--paiban-muted);
  font-size: 12px;
  margin-left: 8px;
}
</style>
