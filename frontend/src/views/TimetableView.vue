<template>
  <div class="timetable-page">
    <div class="toolbar">
      <div>
        <h2>课表</h2>
        <p class="subtitle">
          {{
            isJunior
              ? '按行政班、教师或教室查看课表。调代生效的课节会标注「代」。'
              : '按行政班、教师、教室或全校查看课表。调代生效的课节会标注「代」。'
          }}
        </p>
      </div>
      <div class="actions">
        <el-radio-group v-if="canViewAll || canViewClassScope" v-model="mode" @change="onModeChange">
          <el-radio-button v-if="canViewAll || headClasses.length > 0" value="class">行政班</el-radio-button>
          <el-radio-button value="teacher">教师</el-radio-button>
          <el-radio-button v-if="canViewAll" value="room">教室</el-radio-button>
          <el-radio-button v-if="canViewAll && !isJunior" value="school">全校</el-radio-button>
        </el-radio-group>
        <el-tag v-else effect="plain">我的课表</el-tag>
        <el-select
          v-if="(canViewAll || canViewClassScope) && mode !== 'school'"
          v-model="targetId"
          filterable
          style="width: 200px"
          @change="load"
        >
          <el-option v-for="option in targets" :key="option.id" :label="option.name" :value="option.id" />
        </el-select>
        <el-date-picker
          v-model="date"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
          style="width: 160px"
          @change="load"
        />
        <el-radio-group v-model="view" @change="load">
          <el-radio-button value="latest">最新</el-radio-button>
          <el-radio-button value="baseline">原始</el-radio-button>
        </el-radio-group>
        <el-dropdown v-if="canExport" @command="exportFile">
          <el-button>
            <el-icon><Download /></el-icon>
            <span>导出</span>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="excel">导出 Excel</el-dropdown-item>
              <el-dropdown-item command="pdf">导出 PDF</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>

    <el-alert
      v-if="published === null"
      title="当前学期还没有发布课表，以下内容可能为空。请先在「自动排课」中发布。"
      type="warning"
      :closable="false"
      show-icon
      class="notice"
    />

    <el-card v-loading="loading" shadow="never">
      <el-empty v-if="!hasData" description="暂无课表数据" />

      <template v-else-if="mode === 'school'">
        <el-table-v2
          :columns="schoolColumns"
          :data="schoolRows"
          :width="1180"
          :height="620"
          :row-height="46"
          fixed
        />
      </template>

      <template v-else>
        <div class="grid" :style="{ gridTemplateColumns: `96px repeat(${days.length}, minmax(150px, 1fr))` }">
          <div class="corner">节次</div>
          <div v-for="day in days" :key="`h-${day}`" class="day-head">{{ dayName(day) }}</div>
          <template v-for="row in periodRows" :key="`r-${row.periodNo}`">
            <div class="row-head">
              <div class="row-title">第{{ row.periodNo }}节</div>
              <div class="row-time">{{ row.time }}</div>
            </div>
            <div v-for="day in days" :key="`c-${day}-${row.periodNo}`" class="cell">
              <el-tooltip
                v-if="cellAt(day, row.periodNo)"
                :content="tooltip(cellAt(day, row.periodNo)!)"
                placement="top"
              >
                <div class="lesson" :style="{ background: courseColor(cellAt(day, row.periodNo)!.courseId) }">
                  <div class="lesson-course">
                    {{ cellAt(day, row.periodNo)!.courseName }}
                    <el-tag v-if="cellAt(day, row.periodNo)!.substituted" size="small" type="warning">代</el-tag>
                  </div>
                  <div class="lesson-meta">
                    {{ cellAt(day, row.periodNo)!.teacherName }}
                    <span v-if="cellAt(day, row.periodNo)!.roomName">
                      · {{ cellAt(day, row.periodNo)!.roomName }}
                    </span>
                  </div>
                </div>
              </el-tooltip>
              <div v-else class="empty-cell">—</div>
            </div>
          </template>
        </div>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import { http } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'

interface Entry {
  id: number
  classId: number
  className?: string
  courseId: number
  courseName?: string
  teacherId: number
  teacherName?: string
  roomId?: number
  roomName?: string
  periodId: number
  dayOfWeek: number
  periodNo: number
  substituted: boolean
  substituteTeacherName?: string
  originalTeacherName?: string
  substitutionReason?: string
  note?: string
}

interface Period {
  id: number
  dayOfWeek: number
  periodNo: number
  label: string
  startTime: string
  endTime: string
}

interface Option {
  id: number
  name: string
  headTeacherId?: number | null
}

interface Course {
  id: number
  color?: string
}

interface PublishInfo {
  draftId: number
  draftName: string
  publishedAt: string
}

const auth = useAuthStore()
const settings = useSettingsStore()
const isJunior = computed(() => settings.schoolType === 'junior')
const canExport = computed(() => auth.hasPermission('export:timetable'))
const canViewAll = computed(() => auth.hasPermission('timetable:view'))
const canViewClassScope = computed(() => auth.hasPermission('timetable:view:class'))
const headClasses = computed(() =>
  classes.value.filter((item) => item.headTeacherId != null && item.headTeacherId === auth.user?.teacherId),
)

const mode = ref<'class' | 'teacher' | 'room' | 'school'>('class')
const view = ref<'latest' | 'baseline'>('latest')
const date = ref<string>(new Date().toISOString().slice(0, 10))
const targetId = ref<number | null>(null)
const entries = ref<Entry[]>([])
const periods = ref<Period[]>([])
const classes = ref<Option[]>([])
const teachers = ref<Option[]>([])
const rooms = ref<Option[]>([])
const courses = ref<Course[]>([])
const published = ref<PublishInfo | null | undefined>(undefined)
const loading = ref(false)

const palette = ['#AEC6CF', '#FFD1DC', '#B5EAD7', '#FFDAC1', '#C7CEEA', '#E2F0CB', '#F1C0E8', '#FDFD96']

const days = computed(() => {
  const set = new Set<number>()
  periods.value.forEach((period) => set.add(period.dayOfWeek))
  return [...set].sort((a, b) => a - b)
})

const periodRows = computed(() => {
  const map = new Map<number, { periodNo: number; time: string }>()
  periods.value.forEach((period) => {
    if (!map.has(period.periodNo)) {
      map.set(period.periodNo, {
        periodNo: period.periodNo,
        time: `${period.startTime.slice(0, 5)}-${period.endTime.slice(0, 5)}`,
      })
    }
  })
  return [...map.values()].sort((a, b) => a.periodNo - b.periodNo)
})

const targets = computed(() => {
  if (mode.value === 'class') return canViewAll.value ? classes.value : headClasses.value
  if (mode.value === 'teacher') return teachers.value
  return rooms.value
})

const hasData = computed(() => (mode.value === 'school' ? schoolGroups.value.length > 0 : entries.value.length > 0))

const schoolGroups = computed(() => {
  const map = new Map<number, { classId: number; className: string; cells: Record<string, Entry> }>()
  classes.value.forEach((item) => map.set(item.id, { classId: item.id, className: item.name, cells: {} }))
  entries.value.forEach((entry) => {
    const group = map.get(entry.classId)
    if (group) {
      group.cells[`${entry.dayOfWeek}-${entry.periodNo}`] = entry
    }
  })
  return [...map.values()].filter((group) => Object.keys(group.cells).length > 0)
})

function dayName(day: number) {
  return ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'][day] ?? `周${day}`
}

function schoolCell(group: { cells: Record<string, Entry> }, day: number, periodNo: number) {
  const entry = group.cells[`${day}-${periodNo}`]
  if (!entry) return ''
  return `${entry.courseName} · ${entry.teacherName}${entry.substituted ? '（代）' : ''}`
}

// ADR-0017: the school-wide table is virtualised instead of rendering every class at once.
const schoolRows = computed(() => {
  const rows: Array<Record<string, string | number>> = []
  let id = 0
  schoolGroups.value.forEach((group) => {
    periodRows.value.forEach((row) => {
      const record: Record<string, string | number> = {
        id: id++,
        className: group.className,
        period: `第${row.periodNo}节 ${row.time}`,
      }
      days.value.forEach((day) => {
        record[`d${day}`] = schoolCell(group, day, row.periodNo)
      })
      rows.push(record)
    })
  })
  return rows
})

const schoolColumns = computed(() => [
  { key: 'className', dataKey: 'className', title: '行政班', width: 140, fixed: true },
  { key: 'period', dataKey: 'period', title: '节次', width: 150, fixed: true },
  ...days.value.map((day) => ({
    key: `d${day}`,
    dataKey: `d${day}`,
    title: dayName(day),
    width: 160,
  })),
])

function courseColor(courseId: number) {
  const course = courses.value.find((item) => item.id === courseId)
  return course?.color || palette[courseId % palette.length]
}

function cellAt(day: number, periodNo: number) {
  return entries.value.find((entry) => entry.dayOfWeek === day && entry.periodNo === periodNo)
}

function tooltip(entry: Entry) {
  const base = `${entry.courseName} · ${entry.teacherName}`
  if (!entry.substituted) return base
  const reason = entry.substitutionReason ? `；原因：${entry.substitutionReason}` : ''
  return `${base}（代课）；原教师：${entry.originalTeacherName ?? '—'}${reason}；${entry.note ?? ''}`
}

function onModeChange() {
  if (mode.value === 'school') {
    targetId.value = null
  } else {
    targetId.value = targets.value.length > 0 ? targets.value[0].id : null
  }
  load()
}

// ADR-0018: junior mode has no school-wide view. The settings store resolves
// asynchronously, so fall back whenever junior mode becomes active.
watch(isJunior, (junior) => {
  if (junior && mode.value === 'school') {
    mode.value = 'class'
    onModeChange()
  }
})

async function load() {
  loading.value = true
  try {
    if (mode.value === 'school') {
      const { data } = await http.get('/timetable/school', { params: { view: view.value, date: date.value } })
      entries.value = data.entries
    } else if (targetId.value != null) {
      const { data } = await http.get(`/timetable/${mode.value}/${targetId.value}`, {
        params: { view: view.value, date: date.value },
      })
      entries.value = data
    } else {
      entries.value = []
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '加载课表失败')
    entries.value = []
  } finally {
    loading.value = false
  }
}

async function exportFile(format: string) {
  if (mode.value !== 'school' && targetId.value == null) {
    ElMessage.warning('请先选择要导出的对象')
    return
  }
  const path =
    mode.value === 'school'
      ? `/timetable/school/export/${format}`
      : `/timetable/${mode.value}/${targetId.value}/export/${format}`
  try {
    const { data } = await http.get(path, {
      params: { view: view.value, date: date.value },
      responseType: 'blob',
    })
    const url = URL.createObjectURL(data)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `课表.${format === 'pdf' ? 'pdf' : 'xlsx'}`
    anchor.click()
    URL.revokeObjectURL(url)
  } catch {
    ElMessage.error('导出失败')
  }
}

onMounted(async () => {
  const [classRes, teacherRes, roomRes, courseRes, currentRes] = await Promise.all([
    http.get('/classes'),
    http.get('/teachers/options'),
    http.get('/rooms'),
    http.get('/courses'),
    http.get('/semesters/current'),
  ])
  classes.value = classRes.data
  teachers.value = teacherRes.data
  rooms.value = roomRes.data
  courses.value = courseRes.data
  if (currentRes.status !== 204 && currentRes.data) {
    const semesterId = currentRes.data.id
    const [periodRes, publishRes] = await Promise.all([
      http.get(`/semesters/${semesterId}/periods`),
      http.get('/timetable/publish-info'),
    ])
    periods.value = periodRes.data
    published.value = publishRes.data ?? null
  }
  targetId.value = targets.value.length > 0 ? targets.value[0].id : null
  if (!canViewAll.value) {
    if (canViewClassScope.value && headClasses.value.length > 0) {
      mode.value = 'class'
      targetId.value = headClasses.value[0].id
    } else {
      mode.value = 'teacher'
      targetId.value = auth.user?.teacherId ?? null
    }
  }
  await load()
})
</script>

<style scoped>
.timetable-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
h2 {
  margin: 0 0 4px;
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
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: center;
}
.notice {
  margin-bottom: 0;
}
.grid {
  display: grid;
  border-top: 1px solid #eef0f5;
  border-left: 1px solid #eef0f5;
  min-width: 860px;
}
.corner,
.day-head,
.row-head,
.cell {
  border-right: 1px solid #eef0f5;
  border-bottom: 1px solid #eef0f5;
}
.corner,
.day-head {
  background: #f8fafc;
  padding: 10px 8px;
  font-weight: 600;
  text-align: center;
  font-size: 13px;
}
.row-head {
  padding: 8px;
  background: #fbfcfe;
  display: flex;
  flex-direction: column;
  gap: 2px;
  justify-content: center;
}
.row-title {
  font-weight: 600;
  font-size: 13px;
}
.row-time {
  font-size: 11px;
  color: var(--paiban-muted);
}
.cell {
  min-height: 68px;
  padding: 4px;
  display: flex;
  align-items: stretch;
}
.empty-cell {
  color: #cbd2e0;
  margin: auto;
  font-size: 12px;
}
.lesson {
  flex: 1;
  border-radius: 8px;
  padding: 6px 8px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  color: #2b3245;
  overflow: hidden;
}
.lesson-course {
  font-weight: 600;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 4px;
}
.lesson-meta {
  font-size: 11px;
  margin-top: 3px;
  color: #4b5563;
}
.school-block {
  margin-bottom: 28px;
}
.school-block h3 {
  margin: 0 0 10px;
  font-size: 15px;
}
</style>
