import { computed, reactive, ref, type Ref } from 'vue'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import { monthDayCn, weekdayLabel } from './swapTargetRules'
import { buildCoverVisual, buildSwapVisual, type VisualModel } from './changeVisual'
import type {
  PlanDiagnosis,
  PreviewResponse,
  SwapCandidate,
  SwapPreviewResponse,
  WorkbenchActionResult,
  WorkbenchCandidate,
} from './types'

/**
 * 票 02：第③步（核对并提交）的数据编排。
 *
 * 进入步骤 3 的请求（previews / swap-plans + swap-previews）、方案页签切换、
 * 跨科勾选门、提交审批 / 亲自确认、错误与重新生成，全部收在这个组合式函数里；
 * View 只持步骤状态并向 ConfirmStep 传 flow。所有请求序号守卫：旧响应不得覆盖新状态。
 */

export interface ConfirmFlowInput {
  /** 来源课 entryId（null = 未选课，正常到不了步骤 3）。 */
  entryId: Ref<number | null>
  /** 来源课真实日期（ISO）。 */
  lessonDate: Ref<string>
  action: Ref<'cover' | 'swap' | null>
  candidate: Ref<WorkbenchCandidate | null>
  targetDate: Ref<string>
  targetPeriodId: Ref<number | null>
  coverReason: Ref<string>
  swapReason: Ref<string>
}

export interface DiffRow {
  item: string
  before: string
  after: string
}

export interface PersonRow {
  name: string
  text: string
}

export interface ConfirmResult {
  requestId: number
  effective: boolean
}

/** 从「周三第1节」这类节次标签解析节次号。 */
function periodNoOf(label: string | null | undefined): number {
  const match = /第(\d+)节/.exec(label ?? '')
  return match ? Number(match[1]) : 0
}

/** Instant ISO → 本地 HH:mm。 */
function clockTime(iso: string): string {
  const value = new Date(iso)
  if (Number.isNaN(value.getTime())) return ''
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${pad(value.getHours())}:${pad(value.getMinutes())}`
}

/** 按教师分组（保持首次出现顺序），每位受影响老师一句话。 */
function groupByTeacher(
  changes: SwapPreviewResponse['changes'],
): Array<{ name: string; destinations: string[] }> {
  const order: string[] = []
  const grouped = new Map<string, string[]>()
  for (const change of changes) {
    const name = change.teacherName ?? ''
    if (!grouped.has(name)) {
      grouped.set(name, [])
      order.push(name)
    }
    const date = `${monthDayCn(change.toDate)}（${weekdayLabel(change.toDate)}）`
    grouped.get(name)!.push(`${change.courseName ?? ''}改到 ${date}第${periodNoOf(change.toLabel)}节上`)
  }
  return order.map((name) => ({ name, destinations: grouped.get(name)! }))
}

export function useConfirmFlow(input: ConfirmFlowInput) {
  const { entryId, lessonDate, action, candidate, targetDate, targetPeriodId, coverReason, swapReason } =
    input

  const loading = ref(false)
  const busy = ref(false)
  const error = ref('')
  const noSolution = ref(false)
  const diagnoses = ref<PlanDiagnosis[]>([])

  const plans = ref<SwapCandidate[]>([])
  const activePlanIndex = ref(0)
  const coverPreview = ref<PreviewResponse | null>(null)
  const swapPreview = ref<SwapPreviewResponse | null>(null)
  const crossSubjectAck = ref(false)
  const result = ref<ConfirmResult | null>(null)

  /** 恢复基线（票 01 留下的入口）：走同一 previews 端点，替代教师换成基线教师。 */
  const baselineTeacherId = ref<number | null>(null)

  // 请求序号：generate / selectPlan / invalidate 都会推进，旧响应直接丢弃。
  let loadSeq = 0

  const previewLoaded = computed(() => coverPreview.value !== null || swapPreview.value !== null)

  const coverChange = computed(() => coverPreview.value?.changes?.[0] ?? null)

  const deadlineText = computed(() => {
    const iso = coverPreview.value?.deadlineAt ?? swapPreview.value?.deadlineAt ?? ''
    return clockTime(iso)
  })

  const visual = computed<VisualModel | null>(() => {
    if (coverPreview.value) return buildCoverVisual(coverPreview.value)
    if (swapPreview.value) return buildSwapVisual(swapPreview.value)
    return null
  })

  const conclusion = computed(() => {
    if (coverPreview.value && coverChange.value) {
      const preview = coverPreview.value
      const change = coverChange.value
      const date = preview.lessonDate
      return (
        `${monthDayCn(date)}（${weekdayLabel(date)}）第 ${periodNoOf(change.periodLabelBefore)} 节，` +
        `${preview.className ?? ''}${change.courseName ?? ''}改由${preview.substituteTeacherName ?? ''}上，时间、教室不变。`
      )
    }
    if (swapPreview.value && swapPreview.value.changes.length > 0) {
      const preview = swapPreview.value
      const changes = preview.changes
      const source = changes.find((item) => item.fromDate === preview.lessonDate) ?? changes[0]
      const toTarget = changes.find(
        (item) =>
          item.fromDate === preview.targetDate &&
          periodNoOf(item.fromLabel) === (preview.targetPeriodNo ?? 0),
      )
      const target = toTarget ?? changes.find((item) => item.fromDate === preview.targetDate) ?? source
      const targetPeriodNo = preview.targetPeriodNo ?? periodNoOf(target.fromLabel)
      return (
        `${monthDayCn(preview.lessonDate)}（${weekdayLabel(preview.lessonDate)}）第 ${periodNoOf(source.fromLabel)} 节的${source.courseName ?? ''}，` +
        `和 ${monthDayCn(preview.targetDate)}（${weekdayLabel(preview.targetDate)}）第 ${targetPeriodNo} 节的${target.courseName ?? ''}对调时间。`
      )
    }
    return ''
  })

  const scope = computed(() => {
    if (coverPreview.value) {
      const preview = coverPreview.value
      return `影响范围：只有这一节课；${preview.previousTeacherName ?? ''}空出这一节，${preview.substituteTeacherName ?? ''}多一节。`
    }
    if (swapPreview.value) {
      const preview = swapPreview.value
      const crossWeek = (visual.value?.weeks.length ?? 1) > 1 ? '、跨 2 周' : ''
      return (
        `影响范围：${preview.changes.length} 节课对调，` +
        `影响 ${preview.affectedTeacherNames.length} 位老师${crossWeek}。`
      )
    }
    return ''
  })

  const revisionText = computed(() => {
    const preview = coverPreview.value
    if (!preview?.revisionKind || !preview.revisesRequestId) return ''
    return `这是对已生效申请 #${preview.revisesRequestId} 的再次调整`
  })

  /** 跨科门：仅代课且预览判 different subject（科目取步骤 2 候选，缺省写「所派教师」）。 */
  const crossSubject = computed(() => {
    const preview = coverPreview.value
    return !!preview && preview.sameSubject === false
  })

  const crossSubjectText = computed(() => {
    if (!crossSubject.value) return ''
    const subject = candidate.value?.subject
    const who = subject ? `${subject}老师` : '所派教师'
    const course = coverChange.value?.courseName ?? '该课程'
    const teacher = coverPreview.value?.substituteTeacherName ?? ''
    return `${teacher}是${who}，与${course}不同科，属于跨科代课。确认可以接受再提交。`
  })

  const issues = computed(() => coverPreview.value?.issues ?? swapPreview.value?.issues ?? [])

  const diffRows = computed<DiffRow[]>(() => {
    if (coverPreview.value && coverChange.value) {
      const change = coverChange.value
      return [
        {
          item: '上课老师',
          before: change.teacherNameBefore ?? '',
          after: change.teacherNameAfter ?? '',
        },
      ]
    }
    if (swapPreview.value) {
      return swapPreview.value.changes.map((change) => ({
        item: `${change.courseName ?? ''} 上课时间`,
        before: `${monthDayCn(change.fromDate)} ${weekdayLabel(change.fromDate)}第${periodNoOf(change.fromLabel)}节`,
        after: `${monthDayCn(change.toDate)} ${weekdayLabel(change.toDate)}第${periodNoOf(change.toLabel)}节`,
      }))
    }
    return []
  })

  const restText = computed(() =>
    coverPreview.value ? '其余不变：日期、节次、教室、课程。' : '其余不变：教室、课程、任课老师。',
  )

  const people = computed<PersonRow[]>(() => {
    if (coverPreview.value && coverChange.value) {
      const preview = coverPreview.value
      const change = coverChange.value
      const date = preview.lessonDate
      const slot = `${monthDayCn(date)}（${weekdayLabel(date)}）第${periodNoOf(change.periodLabelBefore)}节`
      return [
        {
          name: preview.substituteTeacherName ?? '',
          text: `${slot}，多一节 ${preview.className ?? ''}${change.courseName ?? ''}`,
        },
        { name: preview.previousTeacherName ?? '', text: '同一节空出来了，不用再上' },
        { name: preview.className ?? '', text: `${change.courseName ?? ''}课换老师了` },
      ]
    }
    if (swapPreview.value) {
      const preview = swapPreview.value
      const rows = groupByTeacher(preview.changes).map((group) => ({
        name: group.name,
        text: group.destinations.join('；'),
      }))
      const lessonCount = preview.changes.length === 2 ? '两节课' : `${preview.changes.length}节课`
      rows.push({
        name: preview.className ?? '',
        text: `${lessonCount}的时间对调了，课程和老师不变`,
      })
      return rows
    }
    return []
  })

  const canSubmitApproval = computed(() => coverPreview.value?.canSubmitApproval ?? swapPreview.value?.canSubmitApproval ?? false)
  const canConfirm = computed(() => coverPreview.value?.canConfirm ?? swapPreview.value?.canConfirm ?? false)

  const submitDisabled = computed(
    () => busy.value || (crossSubject.value && !crossSubjectAck.value) || !canSubmitApproval.value,
  )
  const confirmDisabled = computed(
    () => busy.value || (crossSubject.value && !crossSubjectAck.value) || !canConfirm.value,
  )

  const resultTitle = computed(() => (result.value?.effective ? '已生效' : '已提交审批'))
  const resultText = computed(() => {
    const done = result.value
    if (!done) return ''
    return done.effective
      ? `课表已更新，相关老师会收到通知（申请 #${done.requestId}）。`
      : `申请已进入待批队列，审批通过前课表维持原样（申请 #${done.requestId}）。`
  })

  async function loadPlanPreview(planId: number, seq: number): Promise<void> {
    const { data } = await http.post<SwapPreviewResponse>('/substitution-workbench/swap-previews', {
      entryId: entryId.value,
      lessonDate: lessonDate.value,
      planId,
      reason: swapReason.value || undefined,
    })
    if (seq !== loadSeq) return
    swapPreview.value = data
    coverPreview.value = null
  }

  /** 进入步骤 3 / 「重新生成」：按当前动作重跑预览（旧凭证弃用，预览零写入）。 */
  async function generate(): Promise<void> {
    const seq = ++loadSeq
    error.value = ''
    noSolution.value = false
    diagnoses.value = []
    crossSubjectAck.value = false
    result.value = null
    loading.value = true
    try {
      if (action.value === 'cover') {
        const substituteTeacherId = baselineTeacherId.value ?? candidate.value?.teacherId
        if (!entryId.value || !lessonDate.value || !substituteTeacherId) {
          error.value = '请先在上一页选好课和代课老师'
          return
        }
        const { data } = await http.post<PreviewResponse>('/substitution-workbench/previews', {
          entryId: entryId.value,
          lessonDate: lessonDate.value,
          substituteTeacherId,
          reason: coverReason.value || undefined,
        })
        if (seq !== loadSeq) return
        coverPreview.value = data
        swapPreview.value = null
        plans.value = []
      } else {
        if (!entryId.value || !lessonDate.value || !targetDate.value || targetPeriodId.value == null) {
          error.value = '请先在上一页选好课和目标时间'
          return
        }
        const { data } = await http.post('/substitution-workbench/swap-plans', {
          entryId: entryId.value,
          lessonDate: lessonDate.value,
          targetDate: targetDate.value,
          targetPeriodId: targetPeriodId.value,
          reason: swapReason.value || undefined,
        })
        if (seq !== loadSeq) return
        coverPreview.value = null
        swapPreview.value = null
        plans.value = data.plans ?? []
        activePlanIndex.value = 0
        if (plans.value.length === 0) {
          noSolution.value = true
          diagnoses.value = data.diagnoses ?? []
          return
        }
        await loadPlanPreview(plans.value[0]!.planId, seq)
      }
    } catch (e) {
      if (seq === loadSeq) error.value = serverMessage(e, '生成方案失败，请稍后重试')
    } finally {
      if (seq === loadSeq) loading.value = false
    }
  }

  /** 方案页签切换：对另一 planId 重调 swap-previews（旧凭证直接弃用）。 */
  async function selectPlan(index: number): Promise<void> {
    const target = plans.value[index]
    if (!target || index === activePlanIndex.value || loading.value || busy.value) return
    const seq = ++loadSeq
    error.value = ''
    loading.value = true
    try {
      await loadPlanPreview(target.planId, seq)
      if (seq !== loadSeq) return
      activePlanIndex.value = index
      crossSubjectAck.value = false
    } catch (e) {
      if (seq === loadSeq) error.value = serverMessage(e, '切换方案失败，请重新生成')
    } finally {
      if (seq === loadSeq) loading.value = false
    }
  }

  /** 提交审批（代发）或亲自确认生效；失败留在第③步显示错误条。 */
  async function consume(kind: 'submit' | 'confirm'): Promise<void> {
    const preview = coverPreview.value ?? swapPreview.value
    if (!preview || busy.value) return
    busy.value = true
    error.value = ''
    try {
      const suffix = coverPreview.value ? '' : '-swap'
      const path =
        kind === 'submit'
          ? `/substitution-workbench/previews/${preview.voucherId}/submit-approval${suffix}`
          : `/substitution-workbench/previews/${preview.voucherId}/confirm${suffix}`
      const { data } = await http.post<WorkbenchActionResult>(path)
      result.value = { requestId: data.requestId, effective: data.status === 'EFFECTIVE' }
    } catch (e) {
      error.value = serverMessage(e, '操作失败，请稍后重试')
    } finally {
      busy.value = false
    }
  }

  const submitApproval = () => consume('submit')
  const confirmEffective = () => consume('confirm')

  /** 返回上一步 / 取消：凭证弃用，零请求零写入。 */
  function invalidate(): void {
    loadSeq += 1
    coverPreview.value = null
    swapPreview.value = null
    plans.value = []
    activePlanIndex.value = 0
    error.value = ''
    noSolution.value = false
    diagnoses.value = []
    loading.value = false
    busy.value = false
    crossSubjectAck.value = false
    baselineTeacherId.value = null
  }

  /** 「再调整一节课」：清空第③步全部状态。 */
  function reset(): void {
    invalidate()
    result.value = null
  }

  return reactive({
    loading,
    busy,
    error,
    noSolution,
    diagnoses,
    plans,
    activePlanIndex,
    crossSubjectAck,
    previewLoaded,
    deadlineText,
    visual,
    conclusion,
    scope,
    revisionText,
    crossSubject,
    crossSubjectText,
    issues,
    diffRows,
    restText,
    people,
    submitDisabled,
    confirmDisabled,
    result,
    resultTitle,
    resultText,
    generate,
    selectPlan,
    submitApproval,
    confirmEffective,
    invalidate,
    reset,
    startRestoreBaseline(teacherId: number) {
      baselineTeacherId.value = teacherId
    },
    setCrossSubjectAck(value: boolean) {
      crossSubjectAck.value = value
    },
  })
}

export type ConfirmFlow = ReturnType<typeof useConfirmFlow>
