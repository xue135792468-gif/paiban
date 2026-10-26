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
      <div class="template-area">
        <el-button :disabled="!semesterId" @click="saveAsTemplate"> 存为模板 </el-button>
        <el-select v-model="selectedTemplate" placeholder="选择模板" clearable style="width: 200px">
          <el-option v-for="item in templates" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-button
          :disabled="!semesterId || !selectedTemplate"
          :loading="applyingTemplate"
          @click="applyTemplate"
        >
          应用模板
        </el-button>
        <el-button
          type="danger"
          plain
          :disabled="!selectedTemplate"
          :loading="deletingTemplate"
          @click="deleteTemplate"
        >
          删除模板
        </el-button>
      </div>
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
            :entry-errors="entryErrors"
            :type-errors="typeErrors"
            @toggle-enabled="onToggleEnabled"
            @set-hard="onSetHard"
            @set-weight="onSetWeight"
            @update-value="onUpdateValue"
            @update-entry="onUpdateEntry"
            @add-entry="onAddEntry"
            @remove-entry="onRemoveEntry"
            @set-all-target="onSetAllTarget"
            @add-slot="onAddSlot"
            @remove-slot="onRemoveSlot"
            @update-slot="onUpdateSlot"
          />
        </div>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import ConstraintSummaryBar from '@/components/constraints/ConstraintSummaryBar.vue'
import ConstraintTypeNav from '@/components/constraints/ConstraintTypeNav.vue'
import ConstraintTypePanel from '@/components/constraints/ConstraintTypePanel.vue'
import { useConstraintDraft } from '@/components/constraints/useConstraintDraft'
import { useConstraintSave } from '@/components/constraints/useConstraintSave'
import type {
  ConstraintConfigDto,
  ConstraintTypeDef,
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

interface TemplateItem {
  id: number
  name: string
  createdAt?: string
  configCount?: number
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
const courseNames = computed(() => new Map(courses.value.map((course) => [course.id, course.name])))
const templates = ref<TemplateItem[]>([])
const selectedTemplate = ref<number | null>(null)
const loading = ref(false)
const applyingTemplate = ref(false)
const deletingTemplate = ref(false)

// 互斥条件在保存前就暴露出来（同一课程两个教室类型等）；输入保持原样，由用户决定改哪条。
const conflicts = computed(() => draft.conflicts(courseNames.value))

const { saving, saveMessage, saveReport, save, clearErrors } = useConstraintSave({
  draft,
  semesterId,
  conflicts,
  reload: () => loadConstraints(),
})

const errorCodes = computed(() => saveReport.value.codes)
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
const onUpdateValue = (name: string, value: unknown) =>
  withSelected((code) => draft.updateValue(code, name, value))
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

async function loadTemplates() {
  try {
    const { data } = await http.get<TemplateItem[]>('/constraint-templates')
    templates.value = data ?? []
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '加载约束模板失败'))
  }
}

async function saveAsTemplate() {
  if (!semesterId.value) {
    ElMessage.warning('请先选择学期')
    return
  }
  try {
    const { value } = await ElMessageBox.prompt('请输入模板名称', '存为模板', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPattern: /\S+/,
      inputErrorMessage: '模板名称不能为空',
    })
    if (!value || !value.trim()) return
    await http.post(
      '/constraint-templates',
      { name: value.trim() },
      { params: { semesterId: semesterId.value } },
    )
    ElMessage.success('模板保存成功')
    await loadTemplates()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(serverMessage(e, '保存模板失败'))
    }
  }
}

async function applyTemplate() {
  if (!semesterId.value) {
    ElMessage.warning('请先选择学期')
    return
  }
  if (!selectedTemplate.value) {
    ElMessage.warning('请选择要应用的模板')
    return
  }
  const tpl = templates.value.find((t) => t.id === selectedTemplate.value)
  const name = tpl?.name ?? '所选模板'
  try {
    await ElMessageBox.confirm(
      `确定要应用模板「${name}」吗？当前学期的约束配置将被覆盖。`,
      '应用模板',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  applyingTemplate.value = true
  try {
    await http.post(`/constraint-templates/${selectedTemplate.value}/apply`, null, {
      params: { semesterId: semesterId.value },
    })
    ElMessage.success('模板已应用')
    await loadConstraints()
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '应用模板失败'))
  } finally {
    applyingTemplate.value = false
  }
}

async function deleteTemplate() {
  if (!selectedTemplate.value) {
    ElMessage.warning('请选择要删除的模板')
    return
  }
  const tpl = templates.value.find((t) => t.id === selectedTemplate.value)
  const name = tpl?.name ?? '所选模板'
  try {
    await ElMessageBox.confirm(`确定要删除模板「${name}」吗？此操作不可撤销。`, '删除模板', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }

  deletingTemplate.value = true
  try {
    await http.delete(`/constraint-templates/${selectedTemplate.value}`)
    ElMessage.success('模板已删除')
    selectedTemplate.value = null
    await loadTemplates()
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '删除模板失败'))
  } finally {
    deletingTemplate.value = false
  }
}

onMounted(async () => {
  loading.value = true
  try {
    await Promise.all([loadSemesters(), loadTeachersAndCourses(), loadTemplates()])
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
  display: flex;
  justify-content: flex-end;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
  gap: 12px;
}

.template-area {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
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
