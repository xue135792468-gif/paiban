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
        :reload="loadConstraints"
      />
    </div>

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
        :dirty="dirty"
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
          />
        </div>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import ConstraintSummaryBar from '@/components/constraints/ConstraintSummaryBar.vue'
import ConstraintTypeNav from '@/components/constraints/ConstraintTypeNav.vue'
import ConstraintTypePanel from '@/components/constraints/ConstraintTypePanel.vue'
import ConstraintTemplateBar from '@/components/constraints/ConstraintTemplateBar.vue'
import { useConstraintDraft } from '@/components/constraints/useConstraintDraft'
import { useConstraintSave } from '@/components/constraints/useConstraintSave'
import type {
  ConstraintConfigDto,
  ConstraintTypeDef,
  ObjectNames,
  ObjectOptions,
  OptionItem,
} from '@/components/constraints/types'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import { serverMessage } from '@/api/errors'

interface Semester {
  id: number
  name: string
  current: boolean
}

const auth = useAuthStore()
const settings = useSettingsStore()
const canManage = computed(() => auth.hasPermission('constraint:manage'))
const isJunior = computed(() => settings.isJunior)

const draft = useConstraintDraft()
const { counts, groups, selected, selectedCode, dirty } = draft

const semesters = ref<Semester[]>([])
const semesterId = ref<number | null>(null)
const teachers = ref<OptionItem[]>([])
const courses = ref<OptionItem[]>([])
const classes = ref<OptionItem[]>([])
const loading = ref(false)

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

const { saving, saveMessage, saveReport, save, clearErrors } = useConstraintSave({
  draft,
  semesterId,
  conflicts,
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
const onSetThresholdValue = (key: string, value: unknown) =>
  withSelected((code) => draft.setThresholdExceptionValue(code, key, value))

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
    const [typeRes, configRes] = await Promise.all([
      http.get<ConstraintTypeDef[]>('/constraint-types'),
      http.get<ConstraintConfigDto[]>('/constraints', {
        params: { semesterId: semesterId.value },
      }),
      loadClasses(),
    ])
    draft.hydrate(typeRes.data ?? [], configRes.data ?? [], { isJunior: isJunior.value })
    clearErrors()
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '加载约束配置失败'))
  } finally {
    loading.value = false
  }
}

async function onSemesterChange() {
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
