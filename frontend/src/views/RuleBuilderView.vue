<template>
  <div class="rule-builder" data-testid="rule-builder">
    <header class="head">
      <div>
        <h2>排课规则</h2>
        <p class="subtitle">
          用「谁 / 什么课 / 什么时候 / 要求」拼一句规则。一张卡可以装 1–3 句，共用一个开关；
          保存后自动排课、草稿校验和调代都按同一份规则执行。
        </p>
      </div>
    </header>

    <section class="system-baseline" data-testid="rule-system-baseline">
      <div class="baseline-title">系统自带，不能关</div>
      <p>一个老师同一节只能在一个班上课</p>
      <p>一个班同一节只能上一门课</p>
    </section>

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
      <div class="toolbar">
        <el-select
          v-model="semesterId"
          placeholder="请选择学期"
          :disabled="semesters.length === 0"
          class="semester-select"
          data-testid="rule-semester"
          @change="onSemesterChange"
        >
          <el-option
            v-for="semester in semesters"
            :key="semester.id"
            :label="semester.current ? `${semester.name} (当前)` : semester.name"
            :value="semester.id"
          />
        </el-select>
        <el-input
          v-model="query"
          clearable
          placeholder="搜索规则（名称或句子）"
          class="search-input"
          data-testid="rule-search"
        />
        <el-button type="primary" :disabled="semesterId === null" data-testid="rule-new" @click="openNew">
          新建规则
        </el-button>
        <el-button :disabled="semesterId === null" data-testid="rule-ai-toggle" @click="toggleAi">
          AI 帮填
        </el-button>
      </div>

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

      <RuleForm
        v-if="formOpen"
        :key="formKey"
        :initial="editingCard"
        :teachers="teachers"
        :classes="classes"
        :courses="courses"
        :days="availableDays"
        :periods="periods"
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
        <RuleGroupedList
          v-else
          :cards="cards"
          :query="query"
          :names="ruleNames"
          :periods="periods"
          @edit="openEdit"
          @toggle="onToggle"
          @duplicate="onDuplicate"
          @remove="onRemove"
          @impact="onImpact"
        />
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
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import { activeOnly } from '@/utils/activeOptions'
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import ConstraintAiPanel from '@/components/constraints/ConstraintAiPanel.vue'
import RuleForm from '@/components/rules/RuleForm.vue'
import RuleGroupedList from '@/components/rules/RuleGroupedList.vue'
import RuleImpactSummary from '@/components/rules/RuleImpactSummary.vue'
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
import { deleteRuleCardWithConfirmation } from '@/rules/deleteRuleCard'
import { useRuleImpact } from '@/rules/useRuleImpact'
import { useRuleAi } from '@/rules/useRuleAi'
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
const {
  visible: impactVisible,
  loading: impactLoading,
  error: impactError,
  impact,
  title: impactTitle,
  preview: previewImpact,
  hide: hideImpact,
} = useRuleImpact(semesterId)

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
  }
})
const dirty = computed(() => cardsSignature(cards.value) !== loadedSignature.value)
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
  await loadClasses()
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
    await Promise.all([loadSemesters(), loadTeachers(), loadCatalogue(), loadCourses()])
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

.head {
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

.system-baseline {
  margin: 0 0 14px;
  padding: 10px 14px;
  border-radius: var(--paiban-radius, 8px);
  background: var(--el-fill-color-light, #f5f7fa);
  color: var(--paiban-muted);
  font-size: 13px;
  line-height: 1.7;
}

.baseline-title {
  margin-bottom: 2px;
  color: var(--el-text-color-primary, #303133);
  font-size: 14px;
  font-weight: 600;
}

.system-baseline p {
  margin: 0;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.semester-select {
  width: 240px;
}

.search-input {
  width: 220px;
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
