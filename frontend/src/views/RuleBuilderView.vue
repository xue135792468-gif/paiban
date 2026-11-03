<template>
  <div class="rule-builder" data-testid="rule-builder">
    <header class="head">
      <div>
        <h2>排课规则</h2>
        <p class="subtitle">
          用「对谁 / 什么时候 / 要求」拼一条规则。保存后自动排课、草稿校验和调代都按同一条规则执行。
        </p>
      </div>
      <div class="head-actions">
        <router-link to="/rules/advanced" class="advanced-link" data-testid="rule-advanced-link">
          按类型配置
        </router-link>
      </div>
    </header>

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
        <el-button type="primary" :disabled="semesterId === null" data-testid="rule-new" @click="openNew">
          新建规则
        </el-button>
      </div>

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

      <el-card v-loading="loading" shadow="never" class="content-card">
        <EmptyStateGuide
          v-if="!loading && cards.length === 0"
          title="还没有规则"
          description="点「新建规则」，选好对谁、什么时候，就能拼出第一条规则。"
          help-slug="scheduling"
          help-anchor="constraints"
        />
        <div v-else class="cards" data-testid="rule-cards">
          <RuleCardItem
            v-for="card in cards"
            :key="card.key"
            :card="card"
            :sentence="sentenceOf(card)"
            @edit="openEdit"
            @toggle="onToggle"
            @remove="onRemove"
          />
        </div>
      </el-card>

      <footer class="footer">
        <div class="notes">
          <p v-if="otherCount > 0" class="other-note" data-testid="rule-other-note">
            还有 {{ otherCount }} 条其它类型规则，在
            <router-link to="/rules/advanced">「按类型配置」</router-link>
            里管理。
          </p>
          <p
            v-if="unrepresentedCount > 0"
            class="other-note"
            data-testid="rule-unrepresented-note"
          >
            还有 {{ unrepresentedCount }} 条本页未覆盖的规则（如跨多天或指定班级），在
            <router-link to="/rules/advanced">「按类型配置」</router-link>
            里管理。
          </p>
        </div>
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
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import RuleCardItem from '@/components/rules/RuleCardItem.vue'
import RuleForm from '@/components/rules/RuleForm.vue'
import type {
  ConstraintConfigDto,
  ConstraintTypeDef,
  OptionItem,
} from '@/components/constraints/types'
import {
  cardFromInput,
  cardsFromState,
  catalogueTypeCodes,
  configsFromCards,
  describeCard,
  entryBackedTypeCodes,
  isSwitchRule,
  newGroupKey,
  otherRuleCount,
  unrepresentedGroupCount,
  type PeriodOption,
  type RuleCard,
  type RuleInput,
  type RuleNames,
} from '@/rules/compiler'

interface Semester {
  id: number
  name: string
  current: boolean
}

interface ConstraintState {
  version: number
  configs: ConstraintConfigDto[]
}

interface PeriodDto {
  dayOfWeek: number
  periodNo: number
  section?: string | null
}

const semesters = ref<Semester[]>([])
const semesterId = ref<number | null>(null)
const teachers = ref<OptionItem[]>([])
const classes = ref<OptionItem[]>([])
const courses = ref<OptionItem[]>([])
const periods = ref<PeriodOption[]>([])
const configs = ref<ConstraintConfigDto[]>([])
const baseVersion = ref(0)
const cards = ref<RuleCard[]>([])
const entryBackedTypes = ref<Set<string>>(new Set())
const catalogueCodes = ref<Set<string>>(new Set())
const loadedSignature = ref('')
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref<string | null>(null)
const conflict = ref<{ message: string; version: number } | null>(null)
const formOpen = ref(false)
const formKey = ref(0)
const editingKey = ref<string | null>(null)

const availableDays = computed(() =>
  Array.from(new Set(periods.value.map((period) => period.dayOfWeek))).sort(
    (left, right) => left - right,
  ),
)
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
const dirty = computed(() => signatureOf(cards.value) !== loadedSignature.value)
const otherCount = computed(() =>
  otherRuleCount(
    configs.value,
    catalogueCodes.value.size > 0 ? catalogueCodes.value : undefined,
  ),
)
const unrepresentedCount = computed(() => unrepresentedGroupCount(configs.value))
const editingCard = computed(
  () => cards.value.find((card) => card.key === editingKey.value) ?? null,
)

function signatureOf(rows: RuleCard[]): string {
  return JSON.stringify(
    rows.map((card) => ({
      key: card.key,
      groupKey: card.groupKey,
      type: card.type,
      classId: card.classId,
      teacherId: card.teacherId,
      courseId: card.courseId,
      dayOfWeek: card.dayOfWeek,
      periodNos: [...card.periodNos].sort((left, right) => left - right),
      amount: card.amount,
      strength: card.strength,
      enabled: card.enabled,
    })),
  )
}

function sentenceOf(card: RuleCard): string {
  return describeCard(card, ruleNames.value, periods.value)
}

async function loadSemesters() {
  const [listRes, currentRes] = await Promise.all([
    http.get<Semester[]>('/semesters'),
    http.get<Semester>('/semesters/current', { validateStatus: (status) => status < 500 }),
  ])
  semesters.value = listRes.data ?? []
  if (currentRes.status === 200 && currentRes.data?.id) {
    semesterId.value = currentRes.data.id
    return
  }
  const currentInList = semesters.value.find((semester) => semester.current)
  semesterId.value = currentInList?.id ?? semesters.value[0]?.id ?? null
}

async function loadTeachers() {
  const { data } = await http.get<OptionItem[]>('/teachers/options')
  teachers.value = (data ?? []).map((teacher) => ({ id: teacher.id, name: teacher.name }))
}

async function loadCourses() {
  const { data } = await http.get<Array<{ id: number; name: string; active?: boolean }>>('/courses')
  courses.value = (data ?? [])
    .filter((course) => course.active !== false)
    .map((course) => ({ id: course.id, name: course.name }))
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
  classes.value = (data ?? [])
    .filter((item) => item.active !== false)
    .map((item) => ({ id: item.id, name: item.name }))
}

async function loadCatalogue() {
  const { data } = await http.get<ConstraintTypeDef[]>('/constraint-types')
  const types = data ?? []
  entryBackedTypes.value = entryBackedTypeCodes(types)
  catalogueCodes.value = catalogueTypeCodes(types)
}

async function loadState() {
  if (semesterId.value === null) {
    configs.value = []
    periods.value = []
    cards.value = []
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
      section: period.section ?? null,
    }))
    cards.value = cardsFromState(configs.value)
    loadedSignature.value = signatureOf(cards.value)
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

function onFormSubmit(input: RuleInput) {
  const next = cardFromInput(input)
  if (editingKey.value !== null) {
    const index = cards.value.findIndex((card) => card.key === editingKey.value)
    if (index >= 0) {
      const current = cards.value[index]
      // Changing the requirement changes the storage type; an id of the old type
      // would be rejected by the server, so such an edit starts without ids.
      const typeChanged = current.type !== next.type
      cards.value[index] = {
        ...current,
        ...next,
        key: current.key,
        groupKey: typeChanged ? newGroupKey() : current.groupKey,
        enabled: current.enabled,
        entryIds: typeChanged ? {} : current.entryIds,
      }
    }
  } else {
    // A switch rule has exactly one card; creating it again replaces the old one
    // instead of sending two configs of the same type (the server refuses those).
    if (isSwitchRule(next.type)) {
      cards.value = cards.value.filter((card) => card.type !== next.type)
    }
    cards.value.push(next)
  }
  closeForm()
}

function onToggle(card: RuleCard) {
  const target = cards.value.find((item) => item.key === card.key)
  if (target) {
    target.enabled = !target.enabled
  }
}

function onRemove(card: RuleCard) {
  cards.value = cards.value.filter((item) => item.key !== card.key)
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
    })
    await http.put(
      '/constraints',
      { configs: payload },
      {
        params: {
          semesterId: semesterId.value,
          baseVersion: baseVersion.value,
        },
      },
    )
    ElMessage.success('排课规则已保存')
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
      await Promise.all([loadClasses(), loadState()])
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

.advanced-link {
  color: var(--el-color-primary);
  font-size: 13px;
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
}

.footer {
  display: flex;
  align-items: flex-start;
  justify-content: flex-end;
  gap: 16px;
  margin-top: 14px;
}

.notes {
  flex: 1 1 auto;
}

.other-note {
  margin: 0 0 4px;
  color: var(--paiban-muted);
  font-size: 12px;
}
</style>
