<template>
  <div class="workbench">
    <div class="toolbar">
      <div>
        <h2>调代工作台</h2>
        <p class="subtitle">
          搜索教师 → 看实际日期课表 → 指定代课或同班调课 → 对照确认。调课需指定明确的目标日期与课节；预览不产生申请或生效，提交审批进待批队列，亲自确认按普通批准路径生效。
        </p>
      </div>
    </div>

    <el-alert
      v-if="!isManager"
      type="warning"
      :closable="false"
      show-icon
      title="管理调代工作台需要「全校课表查看 + 提交调代 + 审批调代」权限；教师请使用「调代课」页面的自助入口。"
      data-testid="workbench-forbidden"
    />

    <template v-else>
      <el-card shadow="never" class="picker-card">
        <div class="filters">
          <span class="label">教师</span>
          <el-select
            v-model="teacherId"
            filterable
            placeholder="搜索教师姓名"
            style="width: 220px"
            data-testid="teacher-select"
            @change="loadTimetable"
          >
            <el-option v-for="option in teachers" :key="option.id" :label="option.name" :value="option.id" />
          </el-select>
          <span class="label">日期</span>
          <span data-testid="lesson-date">
            <el-date-picker
              v-model="date"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择授课日期"
              style="width: 160px"
              @change="loadTimetable"
            />
          </span>
        </div>
      </el-card>

      <WorkbenchTimetable
        v-if="teacherId"
        :entries="entries"
        :week-start="weekStart"
        :selected-date="date"
        :teacher-name="teacherName"
        :loading="timetableLoading"
        @pick="onPick"
      />

      <el-empty
        v-if="teacherId && !timetableLoading && entries.length === 0"
        description="所选教师当天没有可调整的课"
      />

      <template v-if="sourceEntry && !action && !preview">
        <el-card shadow="never" class="picker-card">
          <div class="reason-row">
            <span>
              已选：{{ sourceEntry.courseName }}（{{ date }} 第 {{ sourceEntry.periodNo }} 节）
            </span>
            <el-button type="primary" plain data-testid="choose-cover" @click="action = 'cover'">
              指定代课
            </el-button>
            <el-button type="primary" plain data-testid="choose-swap" @click="action = 'swap'">
              调课（同班置换）
            </el-button>
          </div>
        </el-card>
      </template>

      <template v-if="sourceEntry && candidateList && action === 'cover' && !preview">
        <SubstitutePicker
          :info="candidateList"
          :selected="candidate"
          @choose="onChoose"
        />
        <el-card v-if="candidate || restorable" shadow="never" class="reason-card">
          <div class="reason-row">
            <span v-if="candidate">已选择代课人：{{ candidate.name }}</span>
            <el-input
              v-model="reason"
              placeholder="代课原因（如：甲老师开会）"
              style="width: 260px"
              data-testid="preview-reason"
            />
            <el-button
              v-if="candidate"
              type="primary"
              data-testid="generate-preview"
              :loading="previewLoading"
              @click="generatePreview()"
            >
              生成预览对照
            </el-button>
            <el-button
              v-if="restorable"
              data-testid="restore-baseline"
              :loading="previewLoading"
              @click="generatePreview(candidateList.baselineTeacherId!)"
            >
              恢复基线教师{{ candidateList.baselineTeacherName ? '（' + candidateList.baselineTeacherName + '）' : '' }}
            </el-button>
            <el-button text @click="resetFlow">重新选择</el-button>
          </div>
        </el-card>
      </template>

      <SwapPlannerPanel
        v-if="sourceEntry && action === 'swap' && !preview"
        :entry="sourceEntry"
        :lesson-date="date"
        @preview="onSwapPreview"
        @cancel="resetFlow"
      />

      <PreviewCompare
        v-if="preview && action === 'cover'"
        :preview="preview"
        :can-confirm="canConfirm"
        :busy="actionBusy"
        @submit-approval="onSubmitApproval"
        @confirm="onConfirm"
        @cancel="resetFlow"
      />

      <SwapCompare
        v-if="swapPreview"
        :preview="swapPreview"
        :can-confirm="canConfirm"
        :busy="actionBusy"
        @submit-approval="onSubmitSwapApproval"
        @confirm="onConfirmSwap"
        @cancel="resetFlow"
      />

      <el-alert
        v-if="result"
        :type="result.status === 'EFFECTIVE' ? 'success' : 'info'"
        :closable="false"
        show-icon
        class="result"
        :data-testid="`workbench-result`"
        :title="resultMessage"
      />
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'
import WorkbenchTimetable from '@/components/substitution/workbench/WorkbenchTimetable.vue'
import SubstitutePicker from '@/components/substitution/workbench/SubstitutePicker.vue'
import PreviewCompare from '@/components/substitution/workbench/PreviewCompare.vue'
import SwapPlannerPanel from '@/components/substitution/workbench/SwapPlannerPanel.vue'
import SwapCompare from '@/components/substitution/workbench/SwapCompare.vue'
import type { Entry } from '@/components/substitution/types'
import type {
  PreviewResponse,
  SwapPreviewResponse,
  WorkbenchActionResult,
  WorkbenchCandidate,
  WorkbenchCandidateList,
} from '@/components/substitution/workbench/types'

/** 与后端 AdminSubstitutionService.MANAGER_PERMISSIONS 保持同一组合。 */
const MANAGER_PERMISSIONS = ['timetable:view', 'substitution:submit', 'substitution:approve']

const auth = useAuthStore()
const isManager = computed(() => auth.hasAllPermissions(MANAGER_PERMISSIONS))
const canConfirm = computed(() => auth.hasPermission('substitution:approve'))

const teachers = ref<Array<{ id: number; name: string }>>([])
const teacherId = ref<number | null>(null)
const date = ref(today())
const entries = ref<Entry[]>([])
const timetableLoading = ref(false)

const sourceEntry = ref<Entry | null>(null)
/** 本次调整的动作：指定代课（票 03）或同班调课（票 05）。 */
const action = ref<'cover' | 'swap' | null>(null)
const candidateList = ref<WorkbenchCandidateList | null>(null)
const candidate = ref<WorkbenchCandidate | null>(null)
const reason = ref('')
const previewLoading = ref(false)
const preview = ref<PreviewResponse | null>(null)
const swapPreview = ref<SwapPreviewResponse | null>(null)
const actionBusy = ref(false)
const result = ref<WorkbenchActionResult | null>(null)

const teacherName = computed(
  () => teachers.value.find((item) => item.id === teacherId.value)?.name ?? '',
)
/**
 * 恢复基线入口（票 04）：当前实际教师与基线原教师不同、且实例未被旧格式锁定、
 * 没有待处理单时，可以直接对基线原教师发起一次经预览、校验的再次调整。
 */
const restorable = computed(() => {
  const info = candidateList.value
  return (
    !!info &&
    !info.legacyLocked &&
    info.activeRequestId == null &&
    info.baselineTeacherId != null &&
    info.baselineTeacherId !== info.currentTeacherId
  )
})
/** 所选日期所在周的周一，作为周课表列头。 */
const weekStart = computed(() => {
  const value = new Date(`${date.value || today()}T00:00:00`)
  const offset = (value.getDay() + 6) % 7
  value.setDate(value.getDate() - offset)
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const dayNo = String(value.getDate()).padStart(2, '0')
  return `${value.getFullYear()}-${month}-${dayNo}`
})

const resultMessage = computed(() => {
  if (!result.value) return ''
  return result.value.status === 'EFFECTIVE'
    ? `亲自确认已生效（申请 #${result.value.requestId}），课表已更新。`
    : `已提交审批（申请 #${result.value.requestId}），待批期间旧安排继续生效。`
})

async function loadTeachers() {
  const { data } = await http.get('/teachers/options')
  teachers.value = data
}

async function loadTimetable() {
  if (!teacherId.value || !date.value) return
  timetableLoading.value = true
  try {
    const { data } = await http.get(`/timetable/teacher/${teacherId.value}`, {
      params: { view: 'latest', date: date.value },
    })
    entries.value = data
  } catch {
    entries.value = []
  } finally {
    timetableLoading.value = false
    resetFlow()
  }
}

async function onPick(entry: Entry) {
  sourceEntry.value = entry
  action.value = null
  candidate.value = null
  preview.value = null
  swapPreview.value = null
  result.value = null
  try {
    const { data } = await http.get('/substitution-workbench/candidates', {
      params: { entryId: entry.id, lessonDate: date.value },
    })
    candidateList.value = data
  } catch (error) {
    ElMessage.error(serverMessage(error, '操作失败'))
  }
}

/** 调课面板选定候选方案并创建 SWAP 预览凭证后进入整环对照。 */
function onSwapPreview(response: SwapPreviewResponse) {
  swapPreview.value = response
}

function onChoose(chosen: WorkbenchCandidate) {
  candidate.value = chosen
}

async function generatePreview(substituteTeacherId?: number) {
  const entry = sourceEntry.value
  const chosen = substituteTeacherId ?? candidate.value?.teacherId
  if (!entry || !chosen || !date.value) return
  previewLoading.value = true
  try {
    const { data } = await http.post('/substitution-workbench/previews', {
      entryId: entry.id,
      lessonDate: date.value,
      substituteTeacherId: chosen,
      reason: reason.value || undefined,
    })
    preview.value = data
  } catch (error) {
    ElMessage.error(serverMessage(error, '操作失败'))
  } finally {
    previewLoading.value = false
  }
}

async function onSubmitApproval(comment: string) {
  await consume('submit-approval', comment)
}

async function onConfirm(comment: string) {
  await consume('confirm', comment)
}

async function onSubmitSwapApproval(comment: string) {
  await consume('submit-approval-swap', comment)
}

async function onConfirmSwap(comment: string) {
  await consume('confirm-swap', comment)
}

async function consume(action: string, comment: string) {
  const voucherId = preview.value?.voucherId ?? swapPreview.value?.voucherId
  if (!voucherId) return
  actionBusy.value = true
  try {
    const { data } = await http.post(`/substitution-workbench/previews/${voucherId}/${action}`, {
      comment: comment || undefined,
    })
    result.value = data
    preview.value = null
    swapPreview.value = null
    resetFlow()
    await loadTimetable()
  } catch (error) {
    ElMessage.error(serverMessage(error, '操作失败'))
  } finally {
    actionBusy.value = false
  }
}

/** 取消预览或完成操作后清空流程状态；预览本身零申请、零生效、零通知。 */
function resetFlow() {
  sourceEntry.value = null
  action.value = null
  candidateList.value = null
  candidate.value = null
  preview.value = null
  swapPreview.value = null
  reason.value = ''
}

function today() {
  return new Date().toISOString().slice(0, 10)
}

onMounted(loadTeachers)
</script>

<style scoped>
.toolbar {
  margin-bottom: 8px;
}
h2 {
  margin: 0 0 4px;
  font-size: 20px;
}
.subtitle {
  color: var(--paiban-muted);
  font-size: 13px;
  margin: 0;
}
.picker-card {
  margin-bottom: 12px;
}
.filters {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.label {
  color: var(--paiban-muted);
  font-size: 13px;
}
.reason-card {
  margin-top: 12px;
}
.reason-row {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.result {
  margin-top: 12px;
}
</style>
