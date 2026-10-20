<template>
  <div>
    <div class="toolbar">
      <div>
        <el-button link @click="router.back()">
          <el-icon><ArrowLeft /></el-icon>
          <span>返回学期列表</span>
        </el-button>
        <h2>{{ semester?.name ?? '作息表' }}</h2>
        <p class="subtitle">勾选每节课在星期几开设。行 = 节次，列 = 星期。</p>
      </div>
      <div class="actions">
        <el-button :loading="generating" @click="generateDefault">生成默认作息</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存作息表</el-button>
      </div>
    </div>

    <el-card v-loading="loading" shadow="never">
      <el-table :data="rows" border>
        <el-table-column label="节次" width="110">
          <template #default="{ row }">
            <el-input-number v-model="row.periodNo" :min="1" :max="20" size="small" controls-position="right" />
          </template>
        </el-table-column>
        <el-table-column label="名称" width="150">
          <template #default="{ row }">
            <el-input v-model="row.label" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="时段" width="130">
          <template #default="{ row }">
            <el-select v-model="row.section" size="small">
              <el-option label="上午" value="MORNING" />
              <el-option label="下午" value="AFTERNOON" />
              <el-option label="晚自习" value="EVENING" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="开始" width="120">
          <template #default="{ row }">
            <el-time-picker
              v-model="row.startTime"
              format="HH:mm"
              value-format="HH:mm"
              size="small"
              style="width: 100%"
            />
          </template>
        </el-table-column>
        <el-table-column label="结束" width="120">
          <template #default="{ row }">
            <el-time-picker
              v-model="row.endTime"
              format="HH:mm"
              value-format="HH:mm"
              size="small"
              style="width: 100%"
            />
          </template>
        </el-table-column>
        <el-table-column v-for="day in days" :key="day.value" :label="day.label" width="70" align="center">
          <template #default="{ row }">
            <el-checkbox
              :model-value="row.days.includes(day.value)"
              @change="(checked: boolean) => toggleDay(row, day.value, checked)"
            />
          </template>
        </el-table-column>
        <el-table-column width="70" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" @click="rows.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button class="add-row" @click="addRow">
        <el-icon><Plus /></el-icon>
        <span>添加节次</span>
      </el-button>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Plus } from '@element-plus/icons-vue'
import { http } from '@/api/http'

interface Semester {
  id: number
  name: string
}

interface Period {
  id: number
  dayOfWeek: number
  periodNo: number
  label: string
  section: string
  startTime: string
  endTime: string
}

interface SlotRow {
  periodNo: number
  label: string
  section: string
  startTime: string
  endTime: string
  days: number[]
}

const route = useRoute()
const router = useRouter()
const semesterId = Number(route.params.id)

const semester = ref<Semester | null>(null)
const rows = ref<SlotRow[]>([])
const loading = ref(false)
const saving = ref(false)
const generating = ref(false)

const days = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 7, label: '周日' },
]

function normalizeTime(value: string) {
  return value ? value.slice(0, 5) : '08:00'
}

function toRows(periods: Period[]): SlotRow[] {
  const map = new Map<number, SlotRow>()
  for (const period of periods) {
    let row = map.get(period.periodNo)
    if (!row) {
      row = {
        periodNo: period.periodNo,
        label: period.label,
        section: period.section,
        startTime: normalizeTime(period.startTime),
        endTime: normalizeTime(period.endTime),
        days: [],
      }
      map.set(period.periodNo, row)
    }
    if (!row.days.includes(period.dayOfWeek)) row.days.push(period.dayOfWeek)
  }
  return [...map.values()].sort((a, b) => a.periodNo - b.periodNo).map((r) => ({ ...r, days: r.days.sort() }))
}

async function load() {
  loading.value = true
  try {
    const [semesterRes, periodRes] = await Promise.all([
      http.get(`/semesters/${semesterId}`),
      http.get(`/semesters/${semesterId}/periods`),
    ])
    semester.value = semesterRes.data
    rows.value = toRows(periodRes.data)
  } finally {
    loading.value = false
  }
}

function toggleDay(row: SlotRow, day: number, checked: boolean) {
  if (checked && !row.days.includes(day)) row.days.push(day)
  if (!checked) row.days = row.days.filter((d) => d !== day)
}

function addRow() {
  const last = rows.value[rows.value.length - 1]
  rows.value.push({
    periodNo: (last?.periodNo ?? 0) + 1,
    label: `第${(last?.periodNo ?? 0) + 1}节`,
    section: 'MORNING',
    startTime: last?.endTime ?? '08:00',
    endTime: '08:45',
    days: [1, 2, 3, 4, 5],
  })
}

async function generateDefault() {
  try {
    await ElMessageBox.confirm('将用默认作息（上午 4 节、下午 4 节、晚自习 2 节，周一至周五）覆盖当前配置，继续？', '生成默认作息', {
      type: 'warning',
    })
  } catch {
    return
  }
  generating.value = true
  try {
    await http.post(`/semesters/${semesterId}/periods/default`, {
      morningCount: 4,
      afternoonCount: 4,
      eveningCount: 2,
      days: [1, 2, 3, 4, 5],
    })
    ElMessage.success('已生成默认作息')
    await load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '生成失败')
  } finally {
    generating.value = false
  }
}

async function save() {
  for (const row of rows.value) {
    if (row.days.length === 0) {
      ElMessage.warning(`第 ${row.periodNo} 节没有选择任何星期`)
      return
    }
  }
  saving.value = true
  try {
    await http.put(`/semesters/${semesterId}/periods`, {
      slots: rows.value.map((row) => ({
        periodNo: row.periodNo,
        label: row.label,
        section: row.section,
        startTime: row.startTime,
        endTime: row.endTime,
        days: [...row.days].sort(),
      })),
    })
    ElMessage.success('作息表已保存')
    await load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
}
h2 {
  margin: 4px 0;
  font-size: 20px;
}
.subtitle {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 13px;
}
.actions {
  display: flex;
  gap: 8px;
}
.add-row {
  margin-top: 12px;
}
</style>
