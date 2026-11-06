<template>
  <div class="rule-builder" data-testid="rule-builder">
    <RulePageHeader />

    <EmptyStateGuide
      v-if="!loading && semesters.length === 0"
      title="还没有学期"
      description="排课规则按学期保存。请先在「学期与作息」中创建学期，再回来配置规则。"
      button-text="去创建学期"
      button-to="/data/semesters"
      help-slug="scheduling"
      help-anchor="constraints"
    />

    <template v-else>
      <RuleToolbar
        v-model:semester-id="semesterId"
        v-model:query="query"
        :semesters="semesters"
        :groups-visible="showGroups"
        @semester-change="onSemesterChange"
        @new="openNew"
        @toggle-groups="showGroups = !showGroups"
        @toggle-ai="toggleAi"
      >
        <template #actions-extra>
          <RuleCopyButton :semesters="semesters" :target-semester-id="semesterId" :base-version="baseVersion" :copy="copyFromSemester" @done="onCopied" />
        </template>
      </RuleToolbar>

      <ConstraintAiPanel v-if="showAi && semesterId !== null" :semester-id="semesterId" :types="aiTypes" :teachers="teachers" :courses="courses" :can-read-all="canReadAll" :can-manage="canManage" @add="onAiAdd" />
      <el-alert v-if="aiNotice" type="info" :closable="false" show-icon class="alert" data-testid="rule-ai-notice" :title="aiNotice" />

      <el-alert
        v-if="conflict"
        type="warning"
        :closable="false"
        show-icon
        class="alert"
        data-testid="rule-conflict"
      >
        <template #title>规则已被他人修改（当前版本 {{ conflict.version }}）</template>
        <p class="alert-copy">{{ conflict.message }}</p>
        <el-button size="small" type="primary" data-testid="rule-conflict-reload" @click="reloadAfterConflict">
          加载最新配置（放弃我的修改）
        </el-button>
      </el-alert>

      <el-alert
        v-if="errorMessage"
        type="error"
        :closable="false"
        show-icon
        class="alert"
        data-testid="rule-save-error"
      >
        <template #title>保存失败，已保留当前输入</template>
        <p class="alert-copy">{{ errorMessage }}</p>
      </el-alert>

      <RuleGroupPanels
        v-if="showGroups"
        :teachers="teachers"
        :courses="courses"
        :teacher-groups="teacherGroups"
        :course-groups="courseGroups"
        :time-groups="timeGroups"
        :semester-id="semesterId"
        :periods="periods"
        @changed="onGroupsChanged"
      />

      <RuleForm
        v-if="formOpen"
        :key="formKey"
        :initial="editingCard"
        :teachers="teachers"
        :classes="classes"
        :courses="courses"
        :days="availableDays"
        :periods="periods"
        :teacher-groups="teacherGroupOptions"
        :create-teacher-group="teacherGroups.create"
        :course-groups="courseGroupOptions"
        :create-course-group="courseGroups.create"
        :time-groups="timeGroupOptions"
        :create-time-group="createTimeGroup"
        @submit="onFormSubmit"
        @cancel="closeForm"
      />

      <RuleImpactSummary
        v-if="impactVisible"
        :title="impactTitle"
        :impact="impact"
        :loading="impactLoading"
        :error="impactError"
        @close="impactVisible = false"
      />

      <el-card v-loading="loading" shadow="never" class="content-card">
        <EmptyStateGuide
          v-if="!loading && cards.length === 0"
          title="还没有规则"
          description="点「新建规则」，选好对谁、什么时候，就能拼出第一条规则。"
          help-slug="scheduling"
          help-anchor="constraints"
        />
        <template v-else>
          <RuleUnusablePanel v-if="ruleBuckets.unusable.length > 0" :cards="ruleBuckets.unusable" :names="ruleNames" :periods="periods" @edit="openEdit" @remove="onRemove" />
          <RuleGroupedList
            :cards="ruleBuckets.usable"
            :query="query"
            :names="ruleNames"
            :periods="periods"
            @edit="openEdit"
            @toggle="onToggle"
            @duplicate="onDuplicate"
            @remove="onRemove"
            @impact="onImpact"
          />
        </template>
      </el-card>

      <footer class="footer">
        <el-button
          type="primary"
          :loading="saving"
          :disabled="!dirty || saving || semesterId === null"
          data-testid="rule-save"
          @click="save"
        >
          保存规则
        </el-button>
      </footer>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import { activeOnly } from '@/utils/activeOptions'
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import ConstraintAiPanel from '@/components/constraints/ConstraintAiPanel.vue'
import RuleForm from '@/components/rules/RuleForm.vue'
import RuleCopyButton from '@/components/rules/RuleCopyButton.vue'
import RuleGroupedList from '@/components/rules/RuleGroupedList.vue'
import RuleGroupPanels from '@/components/rules/RuleGroupPanels.vue'
import RuleImpactSummary from '@/components/rules/RuleImpactSummary.vue'
import RulePageHeader from '@/components/rules/RulePageHeader.vue'
import RuleToolbar from '@/components/rules/RuleToolbar.vue'
import RuleUnusablePanel from '@/components/rules/RuleUnusablePanel.vue'
import type {
  ConstraintConfigDto,
  ConstraintTypeDef,
  OptionItem,
} from '@/components/constraints/types'
import {
  applyCardInputs,
  cardConfigs,
  cardsFromState,
  configsFromCards,
  duplicateCard,
  entryBackedTypeCodes,
  isSwitchRule,
  toggleCard,
  type CardSentenceInput,
  type PeriodOption,
  type RuleCard,
  type RuleNames,
} from '@/rules/compiler'
import { cardsSignature } from '@/rules/signature'
import { cardChangedSinceLoad, cardLabelOf } from '@/rules/cardChange'
import { buildConflictContext, findRuleConflicts, formatConflictMessage } from '@/rules/conflicts'
import { deleteRuleCardWithConfirmation } from '@/rules/deleteRuleCard'
import { useRuleImpact } from '@/rules/useRuleImpact'
import { useRuleAi } from '@/rules/useRuleAi'
import { useRuleGroups } from '@/rules/useRuleGroups'
import { useRuleCopy } from '@/rules/useRuleCopy'
import { useTimeGroups } from '@/rules/useTimeGroups'
import { splitUnusableRules } from '@/rules/unusableRules'
import { useAuthStore } from '@/stores/auth'

interface Semester {
  id: number
  name: string
  current: boolean
  active?: boolean
}

interface ConstraintState {
  version: number
  configs: ConstraintConfigDto[]
}

interface PeriodDto {
  dayOfWeek: number
  periodNo: number
  startTime?: string | null
}

const semesters = ref<Semester[]>([])
const semesterId = ref<number | null>(null)
const teachers = ref<OptionItem[]>([])
const classes = ref<OptionItem[]>([])
const courses = ref<OptionItem[]>([])
const periods = ref<PeriodOption[]>([])
const configs = ref<ConstraintConfigDto[]>([])
const catalogue = ref<ConstraintTypeDef[]>([])
const baseVersion = ref(0)
const cards = ref<RuleCard[]>([])
const entryBackedTypes = ref<Set<string>>(new Set())
const loadedSignature = ref('')
/** Cards as they were saved, so a delete only refuses this card's own edits. */
const loadedCards = ref<RuleCard[]>([])
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref<string | null>(null)
const conflict = ref<{ message: string; version: number } | null>(null)
const formOpen = ref(false)
const formKey = ref(0)
const editingKey = ref<string | null>(null)
const query = ref('')
const showGroups = ref(false)
const teacherGroups = useRuleGroups('TEACHER')
const courseGroups = useRuleGroups('COURSE')
const timeGroups = useTimeGroups(semesterId)
const teacherGroupOptions = teacherGroups.options
const courseGroupOptions = courseGroups.options
const timeGroupOptions = timeGroups.options
const {
  visible: impactVisible,
  loading: impactLoading,
  error: impactError,
  impact,
  title: impactTitle,
  preview: previewImpact,
  hide: hideImpact,
} = useRuleImpact(semesterId)
const { copyFromSemester, onCopied } = useRuleCopy({
  semesterId, baseVersion, setConflict: (value) => { conflict.value = value }, reload: loadState,
})

const availableDays = computed(() =>
  Array.from(new Set(periods.value.map((period) => period.dayOfWeek))).sort(
    (left, right) => left - right,
  ),
)
const classIds = computed(() => classes.value.map((item) => item.id))
const auth = useAuthStore()
const canManage = computed(() => auth.hasPermission('constraint:manage'))
const canReadAll = computed(() => auth.hasPermission('settings:manage'))
const {
  showAi, aiTypes, aiNotice, toggle: toggleAi, onAiAdd, aiFields, resetAi,
} = useRuleAi({ catalogue, classIds, cards })
const ruleNames = computed<RuleNames>(() => {
  const teacherNames = new Map(teachers.value.map((teacher) => [teacher.id, teacher.name]))
  const classNames = new Map(classes.value.map((item) => [item.id, item.name]))
  const courseNames = new Map(courses.value.map((item) => [item.id, item.name]))
  return {
    teacher: (id) => teacherNames.get(id) ?? `教师${id}`,
    course: (id) => courseNames.get(id) ?? `课程${id}`,
    klass: (id) => classNames.get(id) ?? `班级${id}`,
    teacherGroup: (id) => teacherGroups.groupNames.value.get(id) ?? '教师组',
    courseGroup: (id) => courseGroups.groupNames.value.get(id) ?? '课程组',
    timeGroup: (id) => timeGroups.groupNames.value.get(id) ?? '时间组',
  }
})
const dirty = computed(() => cardsSignature(cards.value) !== loadedSignature.value)
/** Ticket 08: copied cards whose references are gone, kept out of the normal list. */
const ruleBuckets = computed(() => splitUnusableRules(cards.value))
/** Ticket 07: the groups expanded to concrete members/slots, read by the save check. */
const conflictContext = computed(() => buildConflictContext({
  periods: periods.value,
  names: ruleNames.value,
  allClassIds: classIds.value,
  teacherGroups: teacherGroups.groups.value,
  courseGroups: courseGroups.groups.value,
  timeGroups: timeGroups.groups.value,
}))
const editingCard = computed(
  () => cards.value.find((card) => card.key === editingKey.value) ?? null,
)

function configsOf(card: RuleCard) {
  return cardConfigs(card, {
    entryBackedTypes: entryBackedTypes.value,
    classIds: classIds.value,
  })
}

async function loadSemesters() {
  const [listRes, currentRes] = await Promise.all([
    http.get<Semester[]>('/semesters'),
    http.get<Semester>('/semesters/current', { validateStatus: (status) => status < 500 }),
  ])
  semesters.value = activeOnly(listRes.data ?? [])
  if (currentRes.status === 200 && currentRes.data?.id) {
    semesterId.value = currentRes.data.id
    return
  }
  const currentInList = semesters.value.find((semester) => semester.current)
  semesterId.value = currentInList?.id ?? semesters.value[0]?.id ?? null
}

async function loadTeachers() {
  const { data } = await http.get<Array<OptionItem & { active?: boolean }>>('/teachers/options')
  teachers.value = activeOnly(data ?? []).map((teacher) => ({ id: teacher.id, name: teacher.name }))
}

async function loadCourses() {
  const { data } = await http.get<Array<{ id: number; name: string; active?: boolean }>>('/courses')
  courses.value = activeOnly(data ?? []).map((course) => ({ id: course.id, name: course.name }))
}

async function loadClasses() {
  if (semesterId.value === null) {
    classes.value = []
    return
  }
  const { data } = await http.get<Array<{ id: number; name: string; active?: boolean }>>(
    '/classes',
    { params: { semesterId: semesterId.value } },
  )
  classes.value = activeOnly(data ?? []).map((item) => ({ id: item.id, name: item.name }))
}

async function loadCatalogue() {
  const { data } = await http.get<ConstraintTypeDef[]>('/constraint-types')
  const types = data ?? []
  catalogue.value = types
  entryBackedTypes.value = entryBackedTypeCodes(types)
}

async function loadState() {
  // A reload starts from the stored configuration: an earlier run's decisions must
  // not travel with the next save.
  resetAi()
  if (semesterId.value === null) {
    configs.value = []
    periods.value = []
    cards.value = []
    loadedCards.value = []
    loadedSignature.value = ''
    return
  }
  loading.value = true
  try {
    const [stateRes, periodRes] = await Promise.all([
      http.get<ConstraintState>('/constraints/state', {
        params: { semesterId: semesterId.value },
      }),
      http.get<PeriodDto[]>(`/semesters/${semesterId.value}/periods`),
    ])
    configs.value = stateRes.data?.configs ?? []
    baseVersion.value = stateRes.data?.version ?? 0
    periods.value = (periodRes.data ?? []).map((period) => ({
      dayOfWeek: period.dayOfWeek,
      periodNo: period.periodNo,
      startTime: period.startTime ?? null,
    }))
    cards.value = cardsFromState(configs.value, { allClassIds: classIds.value })
    // A snapshot copy: the page replaces single entries of `cards.value` in place, so
    // keeping the same array reference would make every edit look "already saved".
    loadedCards.value = [...cards.value]
    loadedSignature.value = cardsSignature(cards.value)
    errorMessage.value = null
    conflict.value = null
  } catch (e: any) {
    errorMessage.value = serverMessage(e, '加载排课规则失败')
    ElMessage.error(errorMessage.value)
  } finally {
    loading.value = false
  }
}

async function onSemesterChange() {
  closeForm()
  hideImpact()
  await Promise.all([loadClasses(), timeGroups.load()])
  await loadState()
}

function openNew() {
  editingKey.value = null
  formKey.value += 1
  formOpen.value = true
}

function openEdit(card: RuleCard) {
  editingKey.value = card.key
  formKey.value += 1
  formOpen.value = true
}

function closeForm() {
  formOpen.value = false
  editingKey.value = null
}

/** A rename/member edit only refreshes the group list, so unsaved rule edits survive.
 * Deleting rules together with the group is the one case that must reload the state.
 */
async function onGroupsChanged(payload: { deletedRules: number }) {
  await timeGroups.load()
  if (payload.deletedRules > 0) {
    await loadState()
  }
}

/** 「＋ 新建时间组」 in the sentence's 「什么时候」 dropdown starts as an empty group. */
function createTimeGroup(name: string): Promise<{ id: number; name: string } | null> {
  return timeGroups.create(name, [])
}

function onFormSubmit(payload: CardSentenceInput[]) {
  const next = applyCardInputs(editingCard.value, payload)
  if (editingKey.value !== null) {
    const index = cards.value.findIndex((card) => card.key === editingKey.value)
    if (index >= 0) {
      cards.value[index] = next
    }
  } else {
    // A switch rule has exactly one card; creating it again replaces the old one
    // instead of sending two configs of the same type (the server refuses those).
    const switchTypes = new Set(
      next.sentences.map((sentence) => sentence.type).filter((type) => isSwitchRule(type)),
    )
    if (switchTypes.size > 0) {
      cards.value = cards.value.filter((card) =>
        !card.sentences.some((sentence) => switchTypes.has(sentence.type)))
    }
    cards.value.push(next)
  }
  closeForm()
  void previewImpact(configsOf(next), '这条规则的影响')
}

function onToggle(card: RuleCard) {
  const index = cards.value.findIndex((item) => item.key === card.key)
  if (index >= 0) {
    cards.value[index] = toggleCard(cards.value[index], !cards.value[index].enabled)
  }
}

function onDuplicate(card: RuleCard) {
  if (card.sentences.some((sentence) => isSwitchRule(sentence.type))) {
    return
  }
  cards.value.push(duplicateCard(card))
}

async function onRemove(card: RuleCard) {
  await deleteRuleCardWithConfirmation(card, configsOf(card), {
    configs: configs.value,
    dirty: dirty.value,
    cardChanged: cardChangedSinceLoad(card, loadedCards.value),
    cardLabel: cardLabelOf(card, ruleNames.value),
    previewImpact,
    impactError: impactError.value,
    hideImpact,
    reload: loadState,
    removeLocally: (target) => {
      cards.value = cards.value.filter((item) => item.key !== target.key)
    },
  })
}

function onImpact(card: RuleCard) {
  void previewImpact(configsOf(card), '这条规则的影响')
}

async function save() {
  if (semesterId.value === null || !dirty.value) {
    return
  }
  saving.value = true
  errorMessage.value = null
  try {
    const payload = configsFromCards(configs.value, cards.value, {
      entryBackedTypes: entryBackedTypes.value,
      classIds: classIds.value,
    })
    const preview = await previewImpact(payload, '保存前的影响')
    if (preview && preview.errors.length > 0) {
      ElMessage.error('规则还有错误，未保存')
      return
    }
    // Ticket 07: a warning only. The user may save anyway; the server does not check this.
    const conflicts = findRuleConflicts(cards.value, conflictContext.value)
    if (conflicts.length > 0) {
      try {
        await ElMessageBox.confirm(formatConflictMessage(conflicts), '规则互相卡住', {
          type: 'warning',
          confirmButtonText: '仍然保存',
          cancelButtonText: '返回修改',
          distinguishCancelAndClose: true,
        })
      } catch {
        return
      }
    }
    const ai = aiFields()
    await http.put(
      '/constraints',
      { configs: payload, ...(ai ?? {}) },
      {
        params: {
          semesterId: semesterId.value,
          baseVersion: baseVersion.value,
        },
      },
    )
    ElMessage.success('排课规则已保存')
    hideImpact()
    await loadState()
  } catch (e: any) {
    if (e?.response?.status === 409) {
      const body = e.response.data
      conflict.value = {
        message: serverMessage(e, '排课规则已被他人修改，请加载最新配置后重试'),
        version: typeof body?.version === 'number' ? body.version : baseVersion.value,
      }
      return
    }
    errorMessage.value = serverMessage(e, '保存排课规则失败')
    ElMessage.error(errorMessage.value)
  } finally {
    saving.value = false
  }
}

async function reloadAfterConflict() {
  conflict.value = null
  await loadState()
}

onMounted(async () => {
  loading.value = true
  try {
    await Promise.all([
      loadSemesters(),
      loadTeachers(),
      loadCatalogue(),
      loadCourses(),
      teacherGroups.load(),
      courseGroups.load(),
    ])
    await timeGroups.load()
    if (semesterId.value !== null) {
      await loadClasses()
      await loadState()
    }
  } catch (e: any) {
    errorMessage.value = serverMessage(e, '加载排课规则失败')
    ElMessage.error(errorMessage.value)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.rule-builder {
  padding-bottom: 24px;
}

.alert {
  margin-bottom: 14px;
}

.alert-copy {
  margin: 4px 0 8px;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-word;
}

.content-card {
  border-radius: var(--paiban-radius, 8px);
  margin-top: 12px;
}

.footer {
  display: flex;
  align-items: flex-start;
  justify-content: flex-end;
  gap: 16px;
  margin-top: 14px;
}
</style>
