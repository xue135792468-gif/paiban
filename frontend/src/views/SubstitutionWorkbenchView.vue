<template>
  <div class="workbench">
    <el-alert
      v-if="!isManager"
      type="warning"
      :closable="false"
      show-icon
      title="管理调代工作台需要「全校课表查看 + 提交调代 + 审批调代」权限；教师请使用「调代课」页面的自助入口。"
      data-testid="workbench-forbidden"
    />

    <template v-else>
      <div ref="scrollRef" class="wb-scroll">
        <div class="wb-canvas">
          <WizardSteps :step="step" />

          <LessonPicker
            v-if="step === 1"
            :teachers="teachers"
            :teacher-id="teacherId"
            :date="date"
            :entries="entries"
            :loading="timetableLoading"
            :timetable-error="timetableError"
            :week-start="weekStart"
            :selected-entry-id="sourceEntry?.id ?? null"
            :drag-invalid-reason="dragInvalidReason"
            @update:teacher-id="onTeacherChange"
            @update:date="onDateChange"
            @pick="onPick"
            @drop="onLessonDrop"
            @retry="loadTimetable"
            @clear-drag-invalid="dragInvalidReason = ''"
          />

          <ActionChooser
            v-else-if="step === 2"
            :action="action"
            :hint="sourceLabel"
            :lesson-date="sourceLessonDate"
            :candidates-loading="candidatesLoading"
            :candidates-error="candidatesError"
            :candidate-list="candidateList"
            :candidate="candidate"
            :cover-reason="coverReason"
            :swap-reason="swapReason"
            :chips="targetChips"
            :target-date="targetDate"
            :target-slots="targetSlots"
            :target-period-id="targetPeriodId"
            :targets-loading="targetsLoading"
            :targets-error="targetsError"
            :restorable="restorable"
            @choose-action="onChooseAction"
            @choose-candidate="onChooseCandidate"
            @retry-candidates="loadCandidates"
            @update:cover-reason="coverReason = $event"
            @update:swap-reason="swapReason = $event"
            @select-target-date="onSelectTargetDate"
            @select-target-slot="targetPeriodId = $event"
            @restore-baseline="onRestoreBaseline"
          />

          <section v-else class="panel cur" data-testid="wizard-panel-3">
            <div class="phead">
              <span class="idx">3</span>
              <h2>核对并提交</h2>
              <span class="hint">只列这次会变化的内容</span>
            </div>
            <div class="pbody">
              <p class="placeholder">第 3 步（结论、课表变化对照与提交）由票 02 实现。</p>
            </div>
          </section>
        </div>
      </div>

      <div class="dock" data-testid="wizard-dock">
        <div class="dock-in">
          <div v-if="step === 1" class="foot">
            <span v-if="!sourceEntry" class="need">还差：点一节课开始</span>
            <PickedSummary v-else :label="sourceLabel" :editable="false" />
            <button
              type="button"
              class="bt primary next"
              data-testid="wizard-next-1"
              :disabled="!sourceEntry"
              @click="step = 2"
            >
              下一步：怎么调
            </button>
          </div>

          <div v-else-if="step === 2" class="foot">
            <PickedSummary :label="sourceLabel" @edit="step = 1" />
            <button type="button" class="bt" data-testid="wizard-back-2" @click="step = 1">上一步</button>
            <div class="fr">
              <span class="need" data-testid="wizard-need-2">{{ need2 }}</span>
              <button
                type="button"
                class="bt primary"
                data-testid="wizard-next-2"
                :disabled="!ready2"
                @click="step = 3"
              >
                下一步：核对并提交
              </button>
            </div>
          </div>

          <div v-else class="foot">
            <button type="button" class="bt" data-testid="wizard-back-3" @click="step = 2">上一步</button>
            <div class="fr"><span class="need">第 3 步由票 02 实现</span></div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'
import WizardSteps from '@/components/substitution/workbench/WizardSteps.vue'
import LessonPicker from '@/components/substitution/workbench/LessonPicker.vue'
import ActionChooser from '@/components/substitution/workbench/ActionChooser.vue'
import PickedSummary from '@/components/substitution/workbench/PickedSummary.vue'
import {
  dateOfDay,
  mondayOf,
  monthDayCn,
  today,
  weekdayLabel,
} from '@/components/substitution/workbench/swapTargetRules'
import { buildSwapWindow } from '@/components/substitution/workbench/windowDates'
import { evaluateLessonDrop } from '@/components/substitution/workbench/dragTargets'
import { WORKBENCH_MANAGER_PERMISSIONS } from '@/constants/permissions'
import type { Entry } from '@/components/substitution/types'
import type {
  SwapTargetSlot,
  SwapTargetsResponse,
  WorkbenchCandidate,
  WorkbenchCandidateList,
} from '@/components/substitution/workbench/types'

const auth = useAuthStore()
const isManager = computed(() => auth.hasAllPermissions(WORKBENCH_MANAGER_PERMISSIONS))

const step = ref<1 | 2 | 3>(1)
const scrollRef = ref<HTMLElement | null>(null)

const teachers = ref<Array<{ id: number; name: string }>>([])
const teacherId = ref<number | null>(null)
const date = ref(today())
const entries = ref<Entry[]>([])
const timetableLoading = ref(false)
const timetableError = ref('')

/** 点选的课卡；lessonDate 一律取该卡所在格的真实日期，而不是日期选择器的值。 */
const sourceEntry = ref<Entry | null>(null)
const action = ref<'cover' | 'swap' | null>(null)

const candidateList = ref<WorkbenchCandidateList | null>(null)
const candidate = ref<WorkbenchCandidate | null>(null)
const candidatesLoading = ref(false)
const candidatesError = ref('')
const coverReason = ref('')

const targetDate = ref('')
const targetSlots = ref<SwapTargetSlot[]>([])
const targetPeriodId = ref<number | null>(null)
const targetsLoading = ref(false)
const targetsError = ref('')
const swapReason = ref('')

const dragInvalidReason = ref('')

const weekStart = computed(() => mondayOf(date.value || today()))
const sourceLessonDate = computed(() =>
  sourceEntry.value ? dateOfDay(weekStart.value, sourceEntry.value.dayOfWeek) : '',
)
const sourceLabel = computed(() => {
  const entry = sourceEntry.value
  const lessonDate = sourceLessonDate.value
  if (!entry || !lessonDate) return ''
  return `${monthDayCn(lessonDate)} ${weekdayLabel(lessonDate)}第${entry.periodNo}节 · ${entry.className} · ${entry.courseName} · ${entry.teacherName}`
})

const targetChips = computed(() =>
  sourceLessonDate.value ? buildSwapWindow(sourceLessonDate.value, today()) : [],
)

/** 「恢复基线」入口沿用旧视图同款判定（票 04）；本票只展示按钮与 emit。 */
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

const need2 = computed(() => {
  if (!action.value) return '还差：选择一种调整方式'
  if (action.value === 'cover') return candidate.value ? '可以点下一步了' : '还差：选择代课老师'
  if (!targetDate.value) return '还差：选择目标日期'
  if (!targetPeriodId.value) return '还差：选择目标节次'
  return '可以点下一步了'
})

const ready2 = computed(() => {
  if (!action.value) return false
  if (action.value === 'cover') return !!candidate.value
  return !!targetDate.value && targetPeriodId.value != null
})

// 请求序号守卫：响应与 finally 都校验 mine === seq，旧请求不得覆盖新状态。
let teachersSeq = 0
let timetableSeq = 0

async function loadTeachers() {
  const mine = ++teachersSeq
  try {
    const { data } = await http.get('/teachers/options')
    if (mine === teachersSeq) teachers.value = data
  } catch (error) {
    if (mine === teachersSeq) ElMessage.error(serverMessage(error, '加载教师列表失败'))
  }
}

async function loadTimetable() {
  const mine = ++timetableSeq
  entries.value = []
  timetableError.value = ''
  if (!teacherId.value || !date.value) return
  timetableLoading.value = true
  try {
    const { data } = await http.get(`/timetable/teacher/${teacherId.value}`, {
      params: { view: 'latest', date: date.value },
    })
    if (mine !== timetableSeq) return
    entries.value = data
  } catch (error) {
    if (mine !== timetableSeq) return
    entries.value = []
    timetableError.value = serverMessage(error, '课表加载失败')
  } finally {
    if (mine === timetableSeq) timetableLoading.value = false
  }
}

function onTeacherChange(value: number | null) {
  teacherId.value = value
  step.value = 1
  resetSelection()
  void loadTimetable()
}

function onDateChange(value: string) {
  if (!value) return
  date.value = value
  step.value = 1
  resetSelection()
  void loadTimetable()
}

/** 换教师/换日期/重新点选都会清掉已选课及之后的全部状态。 */
function resetSelection() {
  sourceEntry.value = null
  action.value = null
  candidateList.value = null
  candidate.value = null
  candidatesLoading.value = false
  coverReason.value = ''
  swapReason.value = ''
  targetDate.value = ''
  targetSlots.value = []
  targetPeriodId.value = null
  targetsLoading.value = false
  targetsError.value = ''
  dragInvalidReason.value = ''
}

function onPick(entry: Entry) {
  resetSelection()
  sourceEntry.value = entry
}

async function onChooseAction(kind: 'cover' | 'swap') {
  if (!sourceEntry.value || action.value === kind) return
  action.value = kind
  if (kind === 'cover') {
    // cover ↔ swap 互斥：切到代课时清掉调课的未定状态。
    targetDate.value = ''
    targetSlots.value = []
    targetPeriodId.value = null
    targetsError.value = ''
    swapReason.value = ''
    await loadCandidates()
  } else {
    // 切到调课时清掉代课候选与原因。
    candidateList.value = null
    candidate.value = null
    candidatesError.value = ''
    coverReason.value = ''
  }
}

async function loadCandidates() {
  const entry = sourceEntry.value
  const lessonDate = sourceLessonDate.value
  if (!entry || !lessonDate) return
  candidatesLoading.value = true
  candidateList.value = null
  candidate.value = null
  candidatesError.value = ''
  try {
    const { data } = await http.get<WorkbenchCandidateList>('/substitution-workbench/candidates', {
      params: { entryId: entry.id, lessonDate },
    })
    if (sourceEntry.value?.id === entry.id) candidateList.value = data
  } catch (error) {
    if (sourceEntry.value?.id !== entry.id) return
    candidatesError.value = serverMessage(error, '加载代课候选失败')
  } finally {
    candidatesLoading.value = false
  }
}

function onChooseCandidate(chosen: WorkbenchCandidate) {
  candidate.value = chosen
}

async function onSelectTargetDate(value: string) {
  if (value === targetDate.value) return
  targetDate.value = value
  targetPeriodId.value = null
  targetSlots.value = []
  targetsError.value = ''
  await loadTargets()
}

async function loadTargets() {
  const entry = sourceEntry.value
  const lessonDate = sourceLessonDate.value
  const requestedDate = targetDate.value
  if (!entry || !lessonDate || !requestedDate) return
  targetsLoading.value = true
  targetsError.value = ''
  try {
    const { data } = await http.get<SwapTargetsResponse>('/substitution-workbench/swap-targets', {
      params: { entryId: entry.id, lessonDate, targetDate: requestedDate },
    })
    if (targetDate.value !== requestedDate) return
    targetSlots.value = data.slots
  } catch (error) {
    // 窗口天数、学期范围由后端裁决：这里原样显示后端原因。
    if (targetDate.value !== requestedDate) return
    targetSlots.value = []
    targetsError.value = serverMessage(error, '加载目标节次失败')
  } finally {
    if (targetDate.value === requestedDate) targetsLoading.value = false
  }
}

/**
 * 拖拽预选：有效目标 = 选中来源卡 + 调课动作 + 目标日期/节次，落在步骤 2；
 * 绝不自动进步骤 3、不自动生成方案。无效目标只显示原因，零请求零写入。
 */
async function onLessonDrop(payload: { source: Entry; target: Entry | null; targetDate: string }) {
  const lessonDate = dateOfDay(weekStart.value, payload.source.dayOfWeek)
  const verdict = evaluateLessonDrop(payload.source, payload.target, payload.targetDate, lessonDate)
  if (!verdict.ok) {
    dragInvalidReason.value = verdict.reason ?? '该目标格不能作为调课目标'
    return
  }

  dragInvalidReason.value = ''
  resetSelection()
  sourceEntry.value = payload.source
  action.value = 'swap'
  targetDate.value = payload.targetDate
  step.value = 2
  await loadTargets()
  // 等待期间用户可能已换目标日期或重新点选：状态漂移就放弃预选，不写 targetPeriodId。
  if (targetDate.value !== payload.targetDate || sourceEntry.value?.id !== payload.source.id) return
  const periodNo = payload.target?.periodNo
  const slot = targetSlots.value.find((item) => item.periodNo === periodNo)
  if (slot) targetPeriodId.value = slot.periodId
}

/** 本票只提供按钮与 emit；票 02 接「以基线教师创建修订预览」。 */
function onRestoreBaseline() {}

watch(step, async () => { await nextTick(); if (scrollRef.value) scrollRef.value.scrollTop = 0 })

onMounted(loadTeachers)
</script>

<style scoped>
.workbench {
  --bg: #f5f7fa;
  --surface: #ffffff;
  --inset: #f9fafb;
  --line: #e6e8ef;
  --line-2: #d0d5dd;
  --text: #101828;
  --muted: #667085;
  --faint: #98a2b3;
  --brand: #2563eb;
  --brand-soft: #eff6ff;
  --ok: #15803d;
  --ok-soft: #f0fdf4;
  --warn: #b45309;
  --warn-soft: #fffbeb;
  --on-brand: #ffffff;
  --shadow: 0 1px 2px rgba(16, 24, 40, 0.05);
  --radius: 14px;
  --radius-sm: 10px;
  --btn-h: 38px;
  background: var(--bg);
  border-radius: 14px;
  color: var(--text);
  display: flex;
  flex-direction: column;
  font-family: system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  font-size: 14px;
  height: 100%;
  line-height: 1.7;
  min-height: 0;
  overflow: hidden;
}
.wb-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px 24px 4px;
}
.wb-canvas {
  display: flex;
  flex-direction: column;
  gap: 24px;
  margin: 0 auto;
  max-width: 1080px;
  min-height: 100%;
}
.panel {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  min-width: 0;
  overflow: hidden;
}
.panel.cur {
  border-left: 3px solid var(--brand);
}
.phead {
  align-items: center;
  border-bottom: 1px solid var(--line);
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 16px 24px;
}
.idx {
  align-items: center;
  background: var(--brand-soft);
  border-radius: 50%;
  color: var(--brand);
  display: grid;
  flex: none;
  font-size: 12px;
  height: 22px;
  place-items: center;
  width: 22px;
}
.phead h2 {
  font-size: 15px;
  font-weight: 500;
  margin: 0;
}
.hint {
  color: var(--muted);
  font-size: 12px;
}
.pbody {
  padding: 24px;
}
.placeholder {
  color: var(--muted);
  margin: 0;
}
.dock {
  background: var(--surface);
  border-top: 1px solid var(--line);
  flex: none;
  padding: 12px 24px;
}
.dock-in {
  margin: 0 auto;
  max-width: 1080px;
}
.foot {
  align-items: center;
  display: flex;
  gap: 16px;
  min-width: 0;
  width: 100%;
}
.need {
  color: var(--muted);
  font-size: 12px;
}
.fr {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  justify-content: flex-end;
  margin-left: auto;
  min-width: 0;
}
.next {
  margin-left: auto;
}
.bt {
  align-items: center;
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: 8px;
  color: var(--text);
  cursor: pointer;
  display: inline-flex;
  font: inherit;
  font-size: 13.5px;
  gap: 6px;
  justify-content: center;
  min-height: var(--btn-h);
  padding: 0 16px;
  white-space: nowrap;
}
.bt:hover:not(:disabled) {
  border-color: var(--brand);
  color: var(--brand);
}
.bt.primary {
  background: var(--brand);
  border-color: var(--brand);
  color: var(--on-brand);
}
.bt.primary:hover:not(:disabled) {
  color: var(--on-brand);
  filter: brightness(1.07);
}
.bt:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
@media (max-width: 1180px) {
  .dock-in {
    max-width: 100%;
  }
}
@media (max-width: 900px) {
  .wb-scroll {
    padding: 14px 14px 4px;
  }
  .dock {
    padding: 10px 14px;
  }
  .phead {
    padding: 12px 16px;
  }
  .pbody {
    padding: 16px;
  }
}
@media (max-width: 520px) {
  .foot {
    flex-wrap: wrap;
    gap: 10px;
  }
  .next {
    margin-left: 0;
    width: 100%;
  }
  .fr {
    justify-content: space-between;
    margin-left: 0;
    width: 100%;
  }
}
</style>
