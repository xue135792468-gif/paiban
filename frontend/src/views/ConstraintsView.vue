<template>
  <div class="constraints-page">
    <div class="toolbar">
      <div>
        <h2>约束配置</h2>
        <p class="subtitle">
          {{
            isJunior
              ? '配置学期排课规则，选择「必须」或「尽量」。'
              : '配置学期排课规则与偏好权重，支持保存与复用为模板。'
          }}
        </p>
      </div>
      <div class="actions">
        <el-select
          v-model="semesterId"
          placeholder="请选择学期"
          :disabled="semesters.length === 0"
          style="width: 220px"
          @change="onSemesterChange"
        >
          <el-option
            v-for="s in semesters"
            :key="s.id"
            :label="s.current ? `${s.name} (当前)` : s.name"
            :value="s.id"
          />
        </el-select>
      </div>
    </div>

    <div v-if="semesters.length > 0 && canManage && !isJunior" class="sub-toolbar">
      <ConstraintTemplateBar
        :semester-id="semesterId"
        :classes="classes"
        :base-version="draft.baseVersion.value"
        :reload="loadConstraints"
      />
    </div>

    <ConstraintAiPanel
      v-if="semesterId && canManage"
      ref="aiPanel"
      :semester-id="semesterId"
      :types="draft.rows.value"
      :teachers="teachers"
      :courses="courses"
      :can-read-all="canReadAll"
      :can-manage="canManage"
      @add="onAiAdd"
    />

    <el-alert
      v-if="workspaceStale"
      type="warning"
      :closable="false"
      show-icon
      class="error-bar"
      data-testid="constraint-workspace-stale"
    >
      <template #title>生效配置已被他人更新</template>
      <p class="error-copy">
        恢复的未保存修改仍保留；保存时会要求你核对最新配置后再写入。
      </p>
    </el-alert>

    <el-alert
      v-if="workspaceIssues.length > 0"
      type="warning"
      :closable="false"
      show-icon
      class="error-bar"
      data-testid="constraint-workspace-issues"
    >
      <template #title>恢复的未保存内容需要复核</template>
      <p v-for="(issue, index) in workspaceIssues" :key="index" class="error-copy">{{ issue }}</p>
    </el-alert>

    <el-alert
      v-if="workspaceError"
      type="error"
      :closable="false"
      show-icon
      class="error-bar"
      data-testid="constraint-workspace-error"
    >
      <template #title>暂存失败，跨设备恢复尚未更新</template>
      <p class="error-copy">{{ workspaceError }}</p>
    </el-alert>

    <el-alert
      v-if="workspaceConflict"
      type="warning"
      :closable="false"
      show-icon
      class="error-bar"
      data-testid="constraint-workspace-conflict"
    >
      <template #title>另一台设备更新了未保存工作</template>
      <p class="error-copy">
        本地内容仍保留。请选择继续本地版本，或加载另一台设备的未保存版本。
      </p>
      <div class="conflict-actions">
        <el-button size="small" data-testid="constraint-workspace-keep" @click="keepLocalWorkspace">
          保留我的
        </el-button>
        <el-button
          size="small"
          type="primary"
          data-testid="constraint-workspace-remote"
          @click="loadRemoteWorkspace"
        >
          加载另一台设备
        </el-button>
      </div>
    </el-alert>

    <el-alert
      v-if="aiOutcomes.length > 0"
      :type="aiOutcomeType"
      :closable="true"
      show-icon
      class="error-bar"
      data-testid="constraint-ai-outcomes"
      @close="aiOutcomes = []"
    >
      <template #title>AI 建议加入结果</template>
      <p v-for="(outcome, index) in aiOutcomes" :key="index" class="error-copy">
        {{ outcome.message }}
      </p>
    </el-alert>

    <el-card v-if="semesters.length === 0 && !loading" shadow="never">
      <EmptyStateGuide
        title="还没有学期"
        description="约束配置按学期保存。请先在「学期与作息」中创建学期，再回来配置排课规则。"
        button-text="去创建学期"
        button-to="/admin/semesters"
        help-slug="scheduling"
        help-anchor="constraints"
      />
    </el-card>

    <template v-else>
      <ConstraintSummaryBar
        v-if="semesterId"
        :counts="counts"
        :dirty="dirty || conflict !== null"
        :can-manage="canManage"
        :saving="saving"
        @save="save"
      />

      <el-alert
        v-if="conflicts.length > 0"
        type="warning"
        :closable="false"
        show-icon
        class="error-bar"
        data-testid="constraint-precheck-error"
      >
        <template #title>保存前检查：同一目标存在互斥条件</template>
        <p v-for="(message, index) in conflicts" :key="index" class="error-copy">{{ message }}</p>
      </el-alert>

      <el-alert
        v-if="saveMessage"
        type="error"
        :closable="false"
        show-icon
        class="error-bar"
        data-testid="constraint-save-error"
      >
        <template #title>保存失败，已保留当前输入</template>
        <p class="error-copy">{{ saveMessage }}</p>
      </el-alert>

      <el-alert
        v-if="conflict"
        type="warning"
        :closable="false"
        show-icon
        class="error-bar"
        data-testid="constraint-conflict"
      >
        <template #title>配置已被他人修改（当前版本 {{ conflict.version }}）</template>
        <p class="error-copy">{{ conflict.message }}</p>
        <div class="conflict-actions">
          <el-button size="small" data-testid="constraint-conflict-take-theirs" @click="takeTheirs">
            加载最新配置（放弃我的修改）
          </el-button>
          <el-button
            size="small"
            type="primary"
            data-testid="constraint-conflict-keep-mine"
            @click="keepMine"
          >
            保留我的修改并合并保存
          </el-button>
        </div>
      </el-alert>

      <el-card v-loading="loading" shadow="never" class="content-card">
        <EmptyStateGuide
          v-if="!loading && groups.length === 0"
          title="还没有可配置的约束"
          description="当前学期没有返回任何排课约束。可以查看帮助文档，了解各类约束的作用与配置方式。"
          help-slug="scheduling"
          help-anchor="constraints"
        />

        <div v-else class="split" data-testid="constraint-split">
          <ConstraintTypeNav
            :groups="groups"
            :selected="selectedCode"
            :error-types="errorCodes"
            @select="select"
          />

          <ConstraintTypePanel
            v-if="selected"
            :row="selected"
            :can-manage="canManage"
            :is-junior="isJunior"
            :teachers="teachers"
            :courses="courses"
            :object-options="objectOptions"
            :entry-errors="entryErrors"
            :type-errors="typeErrors"
            @toggle-enabled="onToggleEnabled"
            @set-hard="onSetHard"
            @set-weight="onSetWeight"
            @update-entry="onUpdateEntry"
            @add-entry="onAddEntry"
            @remove-entry="onRemoveEntry"
            @set-all-target="onSetAllTarget"
            @add-slot="onAddSlot"
            @remove-slot="onRemoveSlot"
            @update-slot="onUpdateSlot"
            @set-threshold-global="onSetThresholdGlobal"
            @add-threshold-global="onAddThresholdGlobal"
            @add-threshold-exception="onAddThresholdException"
            @remove-threshold-exception="onRemoveThresholdException"
            @set-threshold-object="onSetThresholdObject"
            @set-threshold-value="onSetThresholdValue"
            @set-entry-enabled="onSetEntryEnabled"
            @set-entry-hard="onSetEntryHard"
            @set-entry-weight="onSetEntryWeight"
            @set-threshold-global-enabled="onSetThresholdGlobalEnabled"
            @set-threshold-global-hard="onSetThresholdGlobalHard"
            @set-threshold-global-weight="onSetThresholdGlobalWeight"
            @set-threshold-exception-enabled="onSetThresholdExceptionEnabled"
            @set-threshold-exception-hard="onSetThresholdExceptionHard"
            @set-threshold-exception-weight="onSetThresholdExceptionWeight"
          />
        </div>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import ConstraintSummaryBar from '@/components/constraints/ConstraintSummaryBar.vue'
import ConstraintTypeNav from '@/components/constraints/ConstraintTypeNav.vue'
import ConstraintTypePanel from '@/components/constraints/ConstraintTypePanel.vue'
import ConstraintTemplateBar from '@/components/constraints/ConstraintTemplateBar.vue'
import ConstraintAiPanel from '@/components/constraints/ConstraintAiPanel.vue'
import { useConstraintDraft } from '@/components/constraints/useConstraintDraft'
import { useConstraintSave } from '@/components/constraints/useConstraintSave'
import {
  useConstraintWorkspace,
  type WorkspaceDto,
  type WorkspacePayload,
} from '@/components/constraints/useConstraintWorkspace'
import type {
  ConstraintConfigDto,
  ConstraintTypeDef,
  ObjectNames,
  ObjectOptions,
  OptionItem,
} from '@/components/constraints/types'
import type { ConstraintProposal, SaveDecision } from '@/components/constraints/useConstraintAi'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import { serverMessage } from '@/api/errors'

interface Semester {
  id: number
  name: string
  current: boolean
}

interface ConstraintState {
  version: number
  configs: ConstraintConfigDto[]
}

const auth = useAuthStore()
const settings = useSettingsStore()
const canManage = computed(() => auth.hasPermission('constraint:manage'))
const canReadAll = computed(() => auth.hasPermission('settings:manage'))
const isJunior = computed(() => settings.isJunior)

const draft = useConstraintDraft()
const { counts, groups, selected, selectedCode, dirty } = draft

interface AiPanelHandle {
  loadAudits: () => void | Promise<void>
  clearSelection: () => void
  workspaceState: () => {
    text: string
    runId: number | null
    selectedProposalIds: string[]
    resolved: Record<string, ConstraintProposal>
  }
  restoreState: (payload: WorkspacePayload) => Promise<void>
}

const aiPanel = ref<AiPanelHandle | null>(null)
const aiRunId = ref<number | null>(null)
const aiDecisions = ref<SaveDecision[]>([])
const aiOutcomes = ref<{ proposalId: string; action: string; message: string }[]>([])
const catalogue = ref<ConstraintTypeDef[]>([])
const workspaceStale = ref(false)
const workspaceIssues = ref<string[]>([])
const restoringWorkspace = ref(false)
const suppressAutosave = ref(false)
const effectivePayloadJson = ref('')
let lastWorkspaceSignature = ''
let autosaveTimer: ReturnType<typeof setTimeout> | null = null

const semesters = ref<Semester[]>([])
const semesterId = ref<number | null>(null)
const teachers = ref<OptionItem[]>([])
const courses = ref<OptionItem[]>([])
const classes = ref<OptionItem[]>([])
const loading = ref(false)

const workspace = useConstraintWorkspace({ semesterId })
const { conflict: workspaceConflict, error: workspaceError } = workspace

function nameMapOf(options: OptionItem[]): Map<number, string> {
  return new Map(options.map((item) => [item.id, item.name]))
}

/**
 * The option lists and their name lookups, one entry per object field. Teachers,
 * administrative classes and courses are three separate id spaces, so nothing is merged:
 * a threshold row is always labelled from the list its own field names, and two objects
 * that happen to share an id can never be mistaken for each other.
 */
const objectOptions = computed<ObjectOptions>(() => ({
  teacherId: teachers.value,
  classId: classes.value,
  courseId: courses.value,
}))
const objectNames = computed<ObjectNames>(() => ({
  teacherId: nameMapOf(teachers.value),
  classId: nameMapOf(classes.value),
  courseId: nameMapOf(courses.value),
}))

// 互斥条件与重复阈值在保存前就暴露出来；输入保持原样，由用户决定改哪条。
const conflictReport = computed(() => draft.conflictsOf(objectNames.value))
const conflicts = computed(() => conflictReport.value.messages)

const { saving, saveMessage, saveReport, conflict, save, takeTheirs, keepMine, clearErrors } =
  useConstraintSave({
  draft,
  semesterId,
  conflicts,
  relaxations: () => draft.relaxations(objectNames.value),
  meta: () => ({ runId: aiRunId.value, decisions: aiDecisions.value }),
  onSaved: () => {
    // The successful save is the new truth; the workspace is deleted server-side.
    suppressAutosave.value = true
    workspace.reset()
    aiDecisions.value = []
    aiRunId.value = null
    aiOutcomes.value = []
    void aiPanel.value?.loadAudits()
  },
  reload: () => loadConstraints(),
})

// 红点覆盖两类问题：服务端拒收的类型，以及页面在保存前就发现的重复/互斥输入。
// 后者与上面的预检条读的是同一份 conflictReport，不再各跑一遍校验。
const errorCodes = computed(() => {
  const codes = new Set(saveReport.value.codes)
  conflictReport.value.codes.forEach((code) => codes.add(code))
  return Array.from(codes)
})
const entryErrors = computed(() => saveReport.value.byEntry[selectedCode.value ?? ''] ?? {})
const typeErrors = computed(() => saveReport.value.byType[selectedCode.value ?? ''] ?? [])

function select(code: string) {
  draft.select(code)
}

/** 所有条目/类型级编辑都作用在左侧当前选中的类型上。 */
function withSelected(action: (code: string) => void) {
  if (selected.value) action(selected.value.code)
}

const onToggleEnabled = (value: boolean) => withSelected((code) => draft.setEnabled(code, value))
const onSetHard = (value: boolean) => withSelected((code) => draft.setHard(code, value))
const onSetWeight = (value: number) => withSelected((code) => draft.setWeight(code, value))
const onUpdateEntry = (key: string, name: string, value: unknown) =>
  withSelected((code) => draft.updateEntryValue(code, key, name, value))
const onAddEntry = () => withSelected((code) => draft.addEntry(code))
const onRemoveEntry = (key: string) => withSelected((code) => draft.removeEntry(code, key))
const onSetAllTarget = (key: string, name: string, all: boolean) =>
  withSelected((code) => draft.setAllTarget(code, key, name, all))
const onAddSlot = (key: string) => withSelected((code) => draft.addSlot(code, key))
const onRemoveSlot = (key: string, slotKey: string) =>
  withSelected((code) => draft.removeSlot(code, key, slotKey))
const onUpdateSlot = (key: string, slotKey: string, field: string, value: unknown) =>
  withSelected((code) => {
    if (field === 'dayOfWeek' || field === 'periodNo') {
      draft.updateSlot(code, key, slotKey, field, value)
    }
  })

const onSetThresholdGlobal = (value: number | null) =>
  withSelected((code) => draft.setThresholdGlobal(code, value))
const onAddThresholdGlobal = () =>
  withSelected((code) => {
    // The catalogue default is the number the type means when someone switches it on.
    const value = selected.value?.params.find(
      (param) => param.name === selected.value?.thresholdValueField,
    )?.defaultValue
    draft.setThresholdGlobal(code, typeof value === 'number' ? value : 1)
  })
const onAddThresholdException = () => withSelected((code) => draft.addThresholdException(code))
const onRemoveThresholdException = (key: string) =>
  withSelected((code) => draft.removeThresholdException(code, key))
const onSetThresholdObject = (key: string, value: unknown) =>
  withSelected((code) => draft.setThresholdExceptionObject(code, key, value))
const aiOutcomeType = computed(() =>
  aiOutcomes.value.some((outcome) => outcome.action === 'CONFLICT'
    || outcome.action === 'SKIPPED' || outcome.action === 'REJECTED')
    ? 'warning'
    : 'success',
)

function objectNameOf(proposal: ConstraintProposal): string {
  const params = proposal.entries[0]?.params ?? {}
  if (params.teacherId !== undefined) {
    return objectNames.value.teacherId.get(Number(params.teacherId)) ?? `教师#${params.teacherId}`
  }
  if (params.courseId !== undefined) {
    return objectNames.value.courseId.get(Number(params.courseId)) ?? `课程#${params.courseId}`
  }
  if (params.classId !== undefined) {
    return objectNames.value.classId.get(Number(params.classId)) ?? `班级#${params.classId}`
  }
  return '该对象'
}

async function onAiAdd(payload: { runId: number; proposals: ConstraintProposal[] }) {
  if (!semesterId.value) {
    return
  }
  const before = draft.snapshotRows()
  const outcomes: { proposalId: string; action: string; message: string }[] = []
  const decisions: SaveDecision[] = []
  for (const proposal of payload.proposals) {
    let outcome = draft.applyProposal(proposal.type, proposal, {})
    if (outcome.action === 'CONFLICT') {
      try {
        await ElMessageBox.confirm(outcome.message, '保留旧值还是替换？', {
          confirmButtonText: '用新建议替换',
          cancelButtonText: '保留旧值',
          type: 'warning',
        })
        outcome = draft.applyProposal(proposal.type, proposal, { replace: true })
      } catch {
        outcome = { action: 'SKIPPED', message: `已保留旧值，未加入「${proposal.typeName}」。` }
      }
    } else if (outcome.action === 'RELAXES') {
      try {
        await ElMessageBox.confirm(outcome.message, '确认放宽限制', {
          confirmButtonText: '确认放宽并加入',
          cancelButtonText: '取消加入',
          type: 'warning',
        })
        outcome = draft.applyProposal(proposal.type, proposal, {
          replace: true,
          confirmRelaxation: true,
          objectName: objectNameOf(proposal),
        })
      } catch {
        outcome = { action: 'SKIPPED', message: '已取消这条放宽要求的加入。' }
      }
    }
    outcomes.push({ proposalId: proposal.proposalId, ...outcome })
    decisions.push({
      proposalId: proposal.proposalId,
      action: outcome.action === 'ADDED' || outcome.action === 'REPLACED'
        ? outcome.action : outcome.action === 'EXISTS' ? 'EXISTS' : 'SKIPPED',
      type: proposal.type,
      reason: outcome.message,
      entries: proposal.entries.map((entry) => entry.params),
    })
  }

  // The server sees the merged configuration before the draft keeps it: invalid
  // references or an illegal merge reject the whole batch and roll back.
  try {
    const { data } = await http.post<{ errors: string[]; invalidReferences: unknown[] }>(
      '/constraints/preview',
      { configs: draft.payload() },
      { params: { semesterId: semesterId.value } },
    )
    const errors = [...(data.errors ?? []),
      ...(data.invalidReferences ?? []).map((issue) => JSON.stringify(issue))]
    if (errors.length > 0) {
      draft.restoreRows(before)
      aiOutcomes.value = [{
        proposalId: '',
        action: 'REJECTED',
        message: `服务端复核未通过，整批未加入：${errors.join('；')}`,
      }]
      ElMessage.error('建议未加入：请先修正冲突')
      return
    }
  } catch (e: any) {
    draft.restoreRows(before)
    aiOutcomes.value = [{
      proposalId: '',
      action: 'REJECTED',
      message: `服务端复核失败，整批未加入：${serverMessage(e, '预览失败')}`,
    }]
    return
  }

  aiRunId.value = payload.runId
  aiDecisions.value = decisions
  aiOutcomes.value = outcomes
  aiPanel.value?.clearSelection()
  ElMessage.success('建议已加入当前表格；仍是未保存修改，请检查后统一保存')
}

const onSetThresholdValue = (key: string, value: unknown) =>
  withSelected((code) => draft.setThresholdExceptionValue(code, key, value))
const onSetEntryEnabled = (key: string, value: boolean) =>
  withSelected((code) => draft.setEntryEnabled(code, key, value))
const onSetEntryHard = (key: string, value: boolean | null) =>
  withSelected((code) => draft.setEntryHard(code, key, value))
const onSetEntryWeight = (key: string, value: number | null) =>
  withSelected((code) => draft.setEntryWeight(code, key, value))
const onSetThresholdGlobalEnabled = (value: boolean) =>
  withSelected((code) => draft.setThresholdGlobalEnabled(code, value))
const onSetThresholdGlobalHard = (value: boolean | null) =>
  withSelected((code) => draft.setThresholdGlobalHard(code, value))
const onSetThresholdGlobalWeight = (value: number | null) =>
  withSelected((code) => draft.setThresholdGlobalWeight(code, value))
const onSetThresholdExceptionEnabled = (key: string, value: boolean) =>
  withSelected((code) => draft.setThresholdExceptionEnabled(code, key, value))
const onSetThresholdExceptionHard = (key: string, value: boolean | null) =>
  withSelected((code) => draft.setThresholdExceptionHard(code, key, value))
const onSetThresholdExceptionWeight = (key: string, value: number | null) =>
  withSelected((code) => draft.setThresholdExceptionWeight(code, key, value))

// ------------------------------------------------------- cross-device workspace (14/11)

function workspacePayload(): WorkspacePayload {
  const ai = aiPanel.value?.workspaceState()
  return {
    text: ai?.text ?? '',
    runId: ai?.runId ?? aiRunId.value,
    selectedProposalIds: ai?.selectedProposalIds ?? [],
    resolved: ai?.resolved ?? {},
    decisions: aiDecisions.value,
    configs: draft.payload(),
    isJunior: isJunior.value,
  }
}

const workspaceSignature = computed(() =>
  JSON.stringify({
    configs: draft.payload(),
    ai: aiPanel.value?.workspaceState() ?? null,
    runId: aiRunId.value,
    decisions: aiDecisions.value,
  }),
)

function scheduleAutosave() {
  if (autosaveTimer) {
    clearTimeout(autosaveTimer)
  }
  autosaveTimer = setTimeout(() => {
    autosaveTimer = null
    void autosaveWorkspace()
  }, 800)
}

async function autosaveWorkspace() {
  if (suppressAutosave.value || restoringWorkspace.value || !semesterId.value) {
    return
  }
  const signature = workspaceSignature.value
  if (signature === lastWorkspaceSignature) {
    return
  }
  const ai = aiPanel.value?.workspaceState()
  const hasWork = draft.dirty.value || (ai?.text ?? '') !== '' || aiDecisions.value.length > 0
  if (!hasWork) {
    return
  }
  const result = await workspace.save(workspacePayload(), draft.baseVersion.value)
  if (result === 'SAVED') {
    lastWorkspaceSignature = signature
  }
}

async function applyWorkspace(
  restored: WorkspaceDto,
  currentVersion: number,
  effectiveSnapshot: string,
) {
  draft.hydrate(
    catalogue.value,
    (restored.payload.configs ?? []) as unknown as ConstraintConfigDto[],
    { isJunior: isJunior.value },
  )
  draft.setBaseVersion(currentVersion)
  draft.setSnapshot(effectiveSnapshot)
  await nextTick()
  await aiPanel.value?.restoreState(restored.payload)
  aiRunId.value = restored.payload.runId ?? null
  aiDecisions.value = restored.payload.decisions ?? []
  workspaceStale.value = restored.baseVersion !== currentVersion
  workspaceIssues.value = [
    ...(restored.errors ?? []),
    ...(restored.invalidReferences ?? []).map((issue) => JSON.stringify(issue)),
  ]
}

async function restoreWorkspace() {
  const currentVersion = draft.baseVersion.value
  const effectiveSnapshot = effectivePayloadJson.value
  restoringWorkspace.value = true
  try {
    const restored = await workspace.load()
    if (!restored) {
      workspaceStale.value = false
      workspaceIssues.value = []
      return
    }
    await applyWorkspace(restored, currentVersion, effectiveSnapshot)
  } finally {
    restoringWorkspace.value = false
    await nextTick()
    lastWorkspaceSignature = workspaceSignature.value
    suppressAutosave.value = false
  }
}

async function keepLocalWorkspace() {
  workspace.adoptServerRevision()
  const result = await workspace.save(workspacePayload(), draft.baseVersion.value)
  if (result === 'SAVED') {
    lastWorkspaceSignature = workspaceSignature.value
  }
}

async function loadRemoteWorkspace() {
  const remote = workspaceConflict.value
  if (!remote) {
    return
  }
  const currentVersion = draft.baseVersion.value
  workspace.adoptServerRevision()
  restoringWorkspace.value = true
  try {
    await applyWorkspace(remote, currentVersion, effectivePayloadJson.value)
  } finally {
    restoringWorkspace.value = false
    await nextTick()
    lastWorkspaceSignature = workspaceSignature.value
  }
}

watch(workspaceSignature, () => {
  if (!suppressAutosave.value && !restoringWorkspace.value) {
    scheduleAutosave()
  }
})

onBeforeUnmount(() => {
  if (autosaveTimer) {
    clearTimeout(autosaveTimer)
  }
})

async function loadSemesters() {
  try {
    const [listRes, currentRes] = await Promise.all([
      http.get<Semester[]>('/semesters'),
      http.get<Semester>('/semesters/current', { validateStatus: (status) => status < 500 }),
    ])
    semesters.value = listRes.data ?? []

    let targetId: number | null = null
    if (currentRes.status === 200 && currentRes.data?.id) {
      targetId = currentRes.data.id
    } else {
      const currentInList = semesters.value.find((s) => s.current)
      if (currentInList) {
        targetId = currentInList.id
      } else if (semesters.value.length > 0) {
        targetId = semesters.value[0].id
      }
    }
    semesterId.value = targetId
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '加载学期失败'))
  }
}

async function loadTeachersAndCourses() {
  try {
    const [teacherRes, courseRes] = await Promise.all([
      http.get<OptionItem[]>('/teachers/options'),
      http.get<OptionItem[]>('/courses'),
    ])
    teachers.value = teacherRes.data ?? []
    courses.value = courseRes.data ?? []
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '加载教师或课程数据失败'))
  }
}

/**
 * The administrative classes of the selected semester: the only objects the class and
 * evening limits may name. A class belongs to one semester, so this reloads with it.
 */
async function loadClasses() {
  if (!semesterId.value) {
    classes.value = []
    return
  }
  try {
    const { data } = await http.get<OptionItem[]>('/classes', {
      params: { semesterId: semesterId.value },
    })
    classes.value = (data ?? []).map((item) => ({ id: item.id, name: item.name }))
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '加载班级数据失败'))
  }
}

async function loadConstraints() {
  if (!semesterId.value) {
    draft.hydrate([], [], { isJunior: isJunior.value })
    return
  }
  loading.value = true
  try {
    const [typeRes, stateRes] = await Promise.all([
      http.get<ConstraintTypeDef[]>('/constraint-types'),
      http.get<ConstraintState>('/constraints/state', {
        params: { semesterId: semesterId.value },
      }),
      loadClasses(),
    ])
    draft.hydrate(typeRes.data ?? [], stateRes.data?.configs ?? [], {
      isJunior: isJunior.value,
    })
    draft.setBaseVersion(stateRes.data?.version ?? 0)
    catalogue.value = typeRes.data ?? []
    effectivePayloadJson.value = JSON.stringify(draft.payload())
    clearErrors()
    await restoreWorkspace()
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '加载约束配置失败'))
  } finally {
    loading.value = false
  }
}

async function onSemesterChange() {
  clearErrors()
  workspace.reset()
  lastWorkspaceSignature = ''
  await loadConstraints()
}

onMounted(async () => {
  loading.value = true
  try {
    await Promise.all([loadSemesters(), loadTeachersAndCourses()])
    if (semesterId.value) {
      await loadConstraints()
    }
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.constraints-page {
  padding-bottom: 24px;
}

.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
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
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.sub-toolbar {
  margin-bottom: 12px;
}

.error-bar {
  margin-bottom: 14px;
}

.conflict-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.error-copy {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-word;
}

.content-card {
  border-radius: var(--paiban-radius, 8px);
}

.split {
  display: grid;
  grid-template-columns: 268px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

@media (max-width: 900px) {
  .split {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
