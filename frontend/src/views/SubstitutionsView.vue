<template>
  <div>
    <div class="toolbar">
      <div>
        <h2>调代课</h2>
        <p class="subtitle">提交调代申请、审批，并追溯本学期的调代记录。</p>
      </div>
      <el-button @click="reloadAll">刷新</el-button>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="我的调代" name="mine">
        <el-card shadow="never" class="submit-card">
          <template #header><span class="card-title">提交调代申请</span></template>
          <el-alert
            v-if="!myTeacherId"
            title="当前账号未关联教师，无法提交调代申请。请联系教务管理员在用户管理中关联教师。"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-form v-else label-width="100px" class="form">
            <el-form-item label="请假日期">
              <el-date-picker v-model="form.date" type="date" value-format="YYYY-MM-DD" @change="loadEntries" />
              <span class="hint">只会列出当天有课的课节</span>
            </el-form-item>
            <el-form-item label="课节">
              <el-select v-model="form.entryId" placeholder="选择要调代的课节" style="width: 320px" :loading="loadingEntries">
                <el-option
                  v-for="entry in entries"
                  :key="entry.id"
                  :label="`第${entry.periodNo}节 · ${entry.courseName} · ${entry.className}`"
                  :value="entry.id"
                />
              </el-select>
              <span v-if="entries.length === 0" class="hint">当天没有可调代的课</span>
            </el-form-item>
            <el-form-item label="期望代课">
              <el-select v-model="form.expectedSubstituteTeacherId" filterable clearable placeholder="可不指定" style="width: 260px">
                <el-option
                  v-for="teacher in otherTeachers"
                  :key="teacher.id"
                  :label="teacher.name"
                  :value="teacher.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="请假原因">
              <el-input v-model="form.reason" type="textarea" :rows="3" placeholder="如：家中有事，需要请假半天" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="submitting" @click="submit">提交申请</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card shadow="never">
          <template #header><span class="card-title">我的申请</span></template>
          <EmptyStateGuide
            v-if="!loadingMine && myRequests.length === 0"
            title="还没有调代申请"
            description="请假时在上方选择日期与课节提交申请，审批进度会显示在这里。"
            help-slug="substitution"
            help-anchor="apply"
          />
          <el-table v-else v-loading="loadingMine" :data="myRequests" stripe>
            <el-table-column prop="effectiveDate" label="日期" width="120" />
            <el-table-column prop="periodLabel" label="节次" width="110" />
            <el-table-column prop="className" label="行政班" min-width="120" />
            <el-table-column prop="courseName" label="课程" width="100" />
            <el-table-column prop="substituteTeacherName" label="代课教师" width="110">
              <template #default="{ row }">{{ row.substituteTeacherName || '待定' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="原因" min-width="160" show-overflow-tooltip />
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button link type="primary" @click="openHistory(row)">流转记录</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane v-if="canApprove" label="待审批" name="pending">
        <el-card shadow="never">
          <el-table
            v-if="loadingPending || pendingRequests.length > 0"
            v-loading="loadingPending"
            :data="pendingRequests"
            stripe
          >
            <el-table-column prop="originalTeacherName" label="申请人" width="110" />
            <el-table-column prop="effectiveDate" label="日期" width="120" />
            <el-table-column prop="periodLabel" label="节次" width="110" />
            <el-table-column prop="className" label="行政班" min-width="120" />
            <el-table-column prop="courseName" label="课程" width="100" />
            <el-table-column prop="substituteTeacherName" label="期望代课" width="110">
              <template #default="{ row }">{{ row.substituteTeacherName || '未指定' }}</template>
            </el-table-column>
            <el-table-column prop="reason" label="原因" min-width="150" show-overflow-tooltip />
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <el-button link type="primary" @click="openApprove(row)">批准</el-button>
                <el-button link type="danger" @click="reject(row)">驳回</el-button>
              </template>
            </el-table-column>
          </el-table>
          <EmptyStateGuide
            v-else
            title="没有待审批的申请"
            description="新的调代申请会出现在这里。审批通过后，课表会自动标注「代」。"
            help-slug="substitution"
            help-anchor="approve"
          />
        </el-card>
      </el-tab-pane>

      <el-tab-pane v-if="canViewRecords" label="调代记录" name="records">
        <el-card shadow="never" class="filter-card">
          <div class="filters">
            <el-select v-model="filter.classId" filterable clearable placeholder="行政班" style="width: 180px">
              <el-option v-for="item in classOptions" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
            <el-select v-model="filter.teacherId" filterable clearable placeholder="教师" style="width: 180px">
              <el-option v-for="teacher in teachers" :key="teacher.id" :label="teacher.name" :value="teacher.id" />
            </el-select>
            <el-date-picker
              v-model="filter.range"
              type="daterange"
              value-format="YYYY-MM-DD"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
            />
            <el-button type="primary" @click="loadRecords">查询</el-button>
          </div>
          <div v-if="stats" class="stats">
            <span>教师：{{ stats.teacherName }}</span>
            <span>被代课 <b>{{ stats.substitutedOut }}</b> 次</span>
            <span>代课 <b>{{ stats.substitutedIn }}</b> 次</span>
          </div>
        </el-card>
        <el-card shadow="never">
          <el-table
            v-if="loadingRecords || records.length > 0"
            v-loading="loadingRecords"
            :data="records"
            stripe
          >
            <el-table-column prop="effectiveDate" width="120">
              <template #header>
                生效日期
                <InfoTooltip field="substitution.effectiveDate" />
              </template>
            </el-table-column>
            <el-table-column prop="className" label="行政班" min-width="120" />
            <el-table-column prop="courseName" label="课程" width="100" />
            <el-table-column prop="originalTeacherName" label="原教师" width="110" />
            <el-table-column prop="substituteTeacherName" label="代课教师" width="110" />
            <el-table-column prop="roomName" label="教室" width="110" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag type="success" size="small">{{ statusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <EmptyStateGuide
            v-else
            title="还没有调代记录"
            description="调代申请审批通过并生效后，会在这里留下记录，方便期末统计与追溯。"
            help-slug="substitution"
            help-anchor="apply"
          />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="historyVisible" title="流转记录" width="560px">
      <el-timeline>
        <el-timeline-item
          v-for="item in history"
          :key="item.id"
          :timestamp="formatTime(item.createdAt)"
          placement="top"
        >
          <div class="history-title">
            {{ item.fromStatus ? statusLabel(item.fromStatus) : '提交' }} →
            {{ statusLabel(item.toStatus) }}
          </div>
          <div v-if="item.actor" class="history-meta">操作人：{{ item.actor }}</div>
          <div v-if="item.comment" class="history-meta">备注：{{ item.comment }}</div>
        </el-timeline-item>
      </el-timeline>
    </el-dialog>

    <el-dialog v-model="approveVisible" title="批准调代申请" width="600px" @closed="resetRecommendations">
      <div class="ai-recommend-bar">
        <el-tooltip content="请先在设置中配置 AI 供应商" :disabled="aiRecommendConfigured" placement="top">
          <span>
            <el-button
              type="primary"
              plain
              :disabled="!aiRecommendConfigured"
              :loading="recommending"
              @click="requestRecommendations"
            >
              AI 推荐代课人
            </el-button>
          </span>
        </el-tooltip>
        <span class="hint">AI 只提供建议，最终由你确认</span>
      </div>
      <div v-if="recommendations.length" class="recommend-list">
        <div
          v-for="item in recommendations"
          :key="item.teacherId"
          class="recommend-card"
          :class="{ selected: approveForm.substituteTeacherId === item.teacherId }"
        >
          <div class="recommend-head">
            <span class="recommend-name">{{ item.name }}</span>
            <el-tag v-if="item.score !== null && item.score !== undefined" type="success" size="small">
              评分 {{ item.score }}
            </el-tag>
          </div>
          <p class="recommend-reason">{{ item.reason }}</p>
          <el-button size="small" type="primary" plain @click="chooseRecommendation(item)">选择</el-button>
        </div>
      </div>
      <el-form label-width="90px">
        <el-form-item label="代课教师" required>
          <el-select v-model="approveForm.substituteTeacherId" filterable style="width: 100%">
            <el-option v-for="teacher in otherTeachersFor(approveForm.originalTeacherId)" :key="teacher.id" :label="teacher.name" :value="teacher.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="审批意见">
          <el-input v-model="approveForm.comment" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="approveVisible = false">取消</el-button>
        <el-button type="primary" :loading="approving" @click="approve">确认批准</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import { useAuthStore } from '@/stores/auth'
import InfoTooltip from '@/components/InfoTooltip.vue'
import { serverMessage } from '@/api/errors'

interface Teacher {
  id: number
  name: string
}

interface Entry {
  id: number
  courseName: string
  className: string
  periodNo: number
  dayOfWeek: number
  substituted: boolean
}

interface RequestRow {
  id: number
  entryId: number
  className: string
  courseName: string
  originalTeacherName: string
  substituteTeacherName?: string
  substituteTeacherId?: number
  effectiveDate: string
  periodLabel: string
  reason?: string
  status: string
  approvalMode: string
  approvalComment?: string
  createdAt: string
}

interface RecordRow {
  id: number
  effectiveDate: string
  className: string
  courseName: string
  originalTeacherName: string
  substituteTeacherName: string
  roomName?: string
  status: string
}

interface HistoryRow {
  id: number
  fromStatus?: string
  toStatus: string
  actor?: string
  comment?: string
  createdAt: string
}

interface Stats {
  teacherName: string
  substitutedOut: number
  substitutedIn: number
}

interface AiStatus {
  configured: boolean
  tasks?: Record<string, { configured: boolean } | undefined>
}

interface Recommendation {
  teacherId: number
  name: string
  reason?: string
  score?: number
}

const auth = useAuthStore()
const route = useRoute()
const canApprove = computed(() => auth.hasPermission('substitution:approve'))
const canViewRecords = computed(() =>
  auth.hasAnyPermission(['substitution:view', 'substitution:view:class']),
)
const canViewAllRecords = computed(() => auth.hasPermission('substitution:view'))
const myTeacherId = computed(() => auth.user?.teacherId ?? null)

const activeTab = ref('mine')
const teachers = ref<Teacher[]>([])
const classes = ref<Array<{ id: number; name: string; headTeacherId?: number | null }>>([])
const classOptions = computed(() =>
  canViewAllRecords.value
    ? classes.value
    : classes.value.filter((item) => item.headTeacherId === auth.user?.teacherId),
)
const entries = ref<Entry[]>([])
const loadingEntries = ref(false)
const myRequests = ref<RequestRow[]>([])
const pendingRequests = ref<RequestRow[]>([])
const records = ref<RecordRow[]>([])
const stats = ref<Stats | null>(null)
const loadingMine = ref(false)
const loadingPending = ref(false)
const loadingRecords = ref(false)
const submitting = ref(false)

const form = reactive({
  date: new Date().toISOString().slice(0, 10),
  entryId: null as number | null,
  expectedSubstituteTeacherId: null as number | null,
  reason: '',
})

const filter = reactive({
  classId: null as number | null,
  teacherId: null as number | null,
  range: [] as string[],
})

const historyVisible = ref(false)
const history = ref<HistoryRow[]>([])

const approveVisible = ref(false)
const approving = ref(false)
const recommendations = ref<Recommendation[]>([])
const recommending = ref(false)
const aiRecommendConfigured = ref(false)
const approveForm = reactive({
  requestId: 0,
  originalTeacherId: 0,
  substituteTeacherId: null as number | null,
  comment: '',
})

const otherTeachers = computed(() => teachers.value.filter((teacher) => teacher.id !== myTeacherId.value))

function otherTeachersFor(originalTeacherId: number) {
  return teachers.value.filter((teacher) => teacher.id !== originalTeacherId)
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    SUBMITTED: '已提交',
    PENDING_APPROVAL: '待审批',
    APPROVED: '已批准',
    REJECTED: '已驳回',
    EFFECTIVE: '已生效',
    CANCELLED: '已取消',
  }
  return labels[status] ?? status
}

function statusType(status: string) {
  if (status === 'EFFECTIVE' || status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  if (status === 'PENDING_APPROVAL') return 'warning'
  return 'info'
}

function formatTime(value: string) {
  return value ? value.replace('T', ' ').slice(0, 16) : ''
}

function weekdayOf(date: string) {
  const day = new Date(`${date}T00:00:00`).getDay()
  return day === 0 ? 7 : day
}

async function loadEntries() {
  if (!myTeacherId.value || !form.date) return
  loadingEntries.value = true
  try {
    const { data } = await http.get(`/timetable/teacher/${myTeacherId.value}`, {
      params: { view: 'baseline', date: form.date },
    })
    const weekday = weekdayOf(form.date)
    entries.value = (data as Entry[]).filter((entry) => entry.dayOfWeek === weekday)
  } catch {
    entries.value = []
  } finally {
    loadingEntries.value = false
  }
}

async function submit() {
  if (!form.entryId) {
    ElMessage.warning('请选择要调代的课节')
    return
  }
  submitting.value = true
  try {
    const { data } = await http.post('/substitution-requests', {
      entryId: form.entryId,
      effectiveDate: form.date,
      reason: form.reason,
      expectedSubstituteTeacherId: form.expectedSubstituteTeacherId,
    })
    ElMessage.success(
      data.status === 'EFFECTIVE' ? '调代已直接生效' : '申请已提交，等待教务审批',
    )
    form.entryId = null
    form.reason = ''
    form.expectedSubstituteTeacherId = null
    await Promise.all([loadMine(), loadEntries()])
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '提交失败'))
  } finally {
    submitting.value = false
  }
}

async function loadMine() {
  loadingMine.value = true
  try {
    const { data } = await http.get('/substitution-requests', { params: { mine: true } })
    myRequests.value = data
  } finally {
    loadingMine.value = false
  }
}

async function loadPending() {
  if (!canApprove.value) return
  loadingPending.value = true
  try {
    const { data } = await http.get('/substitution-requests/pending')
    pendingRequests.value = data
  } finally {
    loadingPending.value = false
  }
}

async function loadRecords() {
  if (!canViewRecords.value) return
  if (!canViewAllRecords.value && !filter.classId) {
    filter.classId = classOptions.value.length > 0 ? classOptions.value[0].id : null
    if (!filter.classId) return
  }
  loadingRecords.value = true
  try {
    const params: Record<string, unknown> = {}
    if (filter.classId) params.classId = filter.classId
    if (filter.teacherId) params.teacherId = filter.teacherId
    if (filter.range && filter.range.length === 2) {
      params.dateFrom = filter.range[0]
      params.dateTo = filter.range[1]
    }
    const { data } = await http.get('/substitutions', { params })
    records.value = data
    if (filter.teacherId) {
      const statsRes = await http.get('/substitutions/stats', { params: { teacherId: filter.teacherId } })
      stats.value = statsRes.data
    } else {
      stats.value = null
    }
  } finally {
    loadingRecords.value = false
  }
}

async function openHistory(row: { id: number }) {
  try {
    const { data } = await http.get(`/substitution-requests/${row.id}/history`)
    history.value = data
    historyVisible.value = true
  } catch {
    // not visible to this account
  }
}

function openApprove(row: RequestRow) {
  approveForm.requestId = row.id
  approveForm.originalTeacherId = 0
  approveForm.substituteTeacherId = row.substituteTeacherId ?? null
  approveForm.comment = ''
  recommendations.value = []
  const original = teachers.value.find((teacher) => teacher.name === row.originalTeacherName)
  approveForm.originalTeacherId = original ? original.id : 0
  approveVisible.value = true
}

async function loadAiStatus() {
  try {
    const { data } = await http.get<AiStatus>('/ai/status')
    aiRecommendConfigured.value = data.tasks?.substitution_recommend?.configured === true
  } catch {
    aiRecommendConfigured.value = false
  }
}

function resetRecommendations() {
  recommendations.value = []
}

async function requestRecommendations() {
  if (!approveForm.requestId) return
  recommending.value = true
  try {
    const { data } = await http.post<{ recommendations?: Recommendation[] }>(
      `/substitution-requests/${approveForm.requestId}/recommend`,
    )
    recommendations.value = data.recommendations ?? []
    if (recommendations.value.length === 0) {
      ElMessage.warning('AI 没有返回可用的代课教师，请手动选择')
    }
  } catch (e: any) {
    ElMessage.error(serverMessage(e, 'AI 推荐失败，请稍后重试或手动选择代课教师'))
  } finally {
    recommending.value = false
  }
}

function chooseRecommendation(item: Recommendation) {
  approveForm.substituteTeacherId = item.teacherId
}

async function approve() {
  if (!approveForm.substituteTeacherId) {
    ElMessage.warning('请选择代课教师')
    return
  }
  approving.value = true
  try {
    await http.post(`/substitution-requests/${approveForm.requestId}/approve`, {
      substituteTeacherId: approveForm.substituteTeacherId,
      comment: approveForm.comment,
    })
    ElMessage.success('已批准，调代已生效')
    approveVisible.value = false
    await Promise.all([loadPending(), loadRecords()])
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '审批失败'))
  } finally {
    approving.value = false
  }
}

async function reject(row: RequestRow) {
  try {
    const { value } = await ElMessageBox.prompt('请填写驳回原因', '驳回申请', {
      inputType: 'textarea',
      inputValidator: (input) => Boolean(input && input.trim()),
    })
    await http.post(`/substitution-requests/${row.id}/reject`, { comment: value })
    ElMessage.success('已驳回')
    await loadPending()
  } catch {
    // cancelled
  }
}

async function reloadAll() {
  await Promise.all([loadMine(), loadPending(), loadRecords()])
}

onMounted(async () => {
  const [teacherRes, classRes] = await Promise.all([
    http.get('/teachers/options'),
    http.get('/classes'),
    loadAiStatus(),
  ])
  teachers.value = teacherRes.data
  classes.value = classRes.data
  await Promise.all([loadMine(), loadPending(), loadRecords()])
  if (myTeacherId.value) {
    await loadEntries()
  }
  const requestId = Number(route.query.requestId)
  if (requestId) {
    await openHistory({ id: requestId })
  }
})
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 8px;
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
.card-title {
  font-weight: 600;
}
.submit-card {
  margin-bottom: 16px;
}
.form {
  max-width: 680px;
}
.hint {
  color: var(--paiban-muted);
  font-size: 12px;
  margin-left: 10px;
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
.filter-card {
  margin-bottom: 16px;
}
.history-title {
  font-weight: 600;
  font-size: 14px;
}
.ai-recommend-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.recommend-list {
  display: grid;
  gap: 10px;
  margin-bottom: 16px;
}
.recommend-card {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 10px 12px;
  transition:
    border-color 0.2s,
    background-color 0.2s;
}
.recommend-card.selected {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.recommend-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.recommend-name {
  font-weight: 600;
  font-size: 14px;
}
.recommend-reason {
  margin: 6px 0 8px;
  color: var(--paiban-muted);
  font-size: 13px;
  line-height: 1.5;
}
.history-meta {
  color: var(--paiban-muted);
  font-size: 12px;
  margin-top: 3px;
}
</style>
