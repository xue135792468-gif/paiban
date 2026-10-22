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
          已启用 {{ enabledCount }} 条 / 共 {{ visibleRows.length }} 条
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
        <el-button
          v-if="canManage"
          type="primary"
          :disabled="!semesterId"
          :loading="saving"
          @click="save"
        >
          保存配置
        </el-button>
      </div>
    </div>

    <div v-if="semesters.length > 0" class="sub-toolbar">
      <div class="summary-area">
        <el-tag type="info" effect="plain" class="count-tag">
          已启用 {{ enabledCount }} 条 / 共 {{ visibleRows.length }} 条
        </el-tag>
      </div>
      <div v-if="canManage && !isJunior" class="template-area">
        <el-button :disabled="!semesterId" @click="saveAsTemplate">
          存为模板
        </el-button>
        <el-select
          v-model="selectedTemplate"
          placeholder="选择模板"
          clearable
          style="width: 200px"
        >
          <el-option
            v-for="item in templates"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
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

    <el-card v-else v-loading="loading" shadow="never" class="content-card">
      <EmptyStateGuide
        v-if="!loading && groups.length === 0"
        title="还没有可配置的约束"
        description="当前学期没有返回任何排课约束。可以查看帮助文档，了解各类约束的作用与配置方式。"
        help-slug="scheduling"
        help-anchor="constraints"
      />
      <el-collapse v-else v-model="activeCategories">
        <el-collapse-item
          v-for="group in groups"
          :key="group.categoryLabel"
          :name="group.categoryLabel"
        >
          <template #title>
            <div class="collapse-header">
              <span class="group-title">{{ group.categoryLabel }}</span>
              <span class="group-summary">
                （已启用 {{ group.rows.filter((r) => r.enabled).length }} / {{ group.rows.length }} 条）
              </span>
            </div>
          </template>

          <div class="constraint-list">
            <div
              v-for="row in group.rows"
              :key="row.type"
              class="constraint-card"
              :class="{ 'is-disabled': !row.enabled }"
            >
              <div class="constraint-head">
                <div class="constraint-main">
                  <el-switch
                    v-model="row.enabled"
                    active-text="启用"
                    :disabled="!canManage"
                  />
                  <div class="constraint-info">
                    <span class="constraint-name">{{ row.name }}</span>
                    <span class="constraint-desc">{{ row.description }}</span>
                  </div>
                </div>

                <div class="constraint-controls">
                  <template v-if="isJunior">
                    <el-radio-group
                      v-model="row.hard"
                      size="small"
                      :disabled="!canManage || !row.enabled"
                    >
                      <el-radio-button :value="true">必须</el-radio-button>
                      <el-radio-button :value="false">尽量</el-radio-button>
                    </el-radio-group>
                    <InfoTooltip field="constraint.hard" />
                  </template>

                  <template v-else>
                    <el-radio-group
                      v-model="row.hard"
                      size="small"
                      :disabled="!canManage || !row.enabled"
                    >
                      <el-radio-button :value="true">硬约束</el-radio-button>
                      <el-radio-button :value="false">软约束</el-radio-button>
                    </el-radio-group>
                    <InfoTooltip field="constraint.hard" />

                    <div class="weight-box">
                      <span class="weight-label">
                        权重 {{ row.weight }}
                        <InfoTooltip field="constraint.weight" />
                      </span>
                      <el-slider
                        v-model="row.weight"
                        :min="0"
                        :max="100"
                        :disabled="row.hard || !canManage || !row.enabled"
                        style="width: 130px"
                      />
                    </div>
                  </template>
                </div>
              </div>

              <div v-if="row.params.length > 0" class="params-box">
                <div
                  v-for="param in row.params"
                  :key="param.name"
                  class="param-field"
                >
                  <span class="param-label">
                    <span v-if="param.required" class="required-star">*</span>
                    {{ param.label }}：
                    <InfoTooltip field="constraint.param" />
                  </span>

                  <el-select
                    v-if="param.type === 'ID'"
                    v-model="row.values[param.name]"
                    filterable
                    clearable
                    :placeholder="`请选择${param.label}`"
                    :disabled="!canManage || !row.enabled"
                    style="width: 180px"
                  >
                    <el-option
                      v-for="opt in idOptions(param)"
                      :key="opt.id"
                      :label="opt.name"
                      :value="opt.id"
                    />
                  </el-select>

                  <el-select
                    v-else-if="param.type === 'DAY'"
                    v-model="row.values[param.name]"
                    placeholder="选择星期"
                    clearable
                    :disabled="!canManage || !row.enabled"
                    style="width: 130px"
                  >
                    <el-option
                      v-for="day in dayOptions"
                      :key="day.value"
                      :label="day.label"
                      :value="day.value"
                    />
                  </el-select>

                  <el-input-number
                    v-else-if="param.type === 'PERIOD'"
                    v-model="row.values[param.name]"
                    :min="1"
                    :max="20"
                    controls-position="right"
                    :disabled="!canManage || !row.enabled"
                    style="width: 120px"
                  />

                  <el-input-number
                    v-else-if="param.type === 'INT'"
                    v-model="row.values[param.name]"
                    :min="param.min ?? 0"
                    :max="param.max ?? 100"
                    controls-position="right"
                    :disabled="!canManage || !row.enabled"
                    style="width: 120px"
                  />

                  <el-select
                    v-else-if="param.type === 'ROOM_TYPE'"
                    v-model="row.values[param.name]"
                    placeholder="选择教室类型"
                    clearable
                    :disabled="!canManage || !row.enabled"
                    style="width: 160px"
                  >
                    <el-option
                      v-for="opt in roomTypeOptions"
                      :key="opt.value"
                      :label="opt.label"
                      :value="opt.value"
                    />
                  </el-select>

                  <el-input
                    v-else-if="param.type === 'TEXT'"
                    v-model="row.values[param.name]"
                    :placeholder="`请输入${param.label}`"
                    clearable
                    :disabled="!canManage || !row.enabled"
                    style="width: 180px"
                  />

                  <el-input
                    v-else
                    v-model="row.values[param.name]"
                    :placeholder="`请输入${param.label}`"
                    clearable
                    :disabled="!canManage || !row.enabled"
                    style="width: 180px"
                  />
                </div>
              </div>
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import InfoTooltip from '@/components/InfoTooltip.vue'
import { serverMessage } from '@/api/errors'

interface ParamDef {
  name: string
  label: string
  type: string
  required: boolean
  min?: number | null
  max?: number | null
  defaultValue?: unknown
  options?: string[]
}

interface ConstraintType {
  code: string
  name: string
  category: string
  categoryLabel: string
  description: string
  defaultHard: boolean
  defaultWeight: number
  params: ParamDef[]
}

interface ConstraintConfig {
  type: string
  params: Record<string, unknown>
  weight: number
  hard: boolean
  enabled: boolean
}

interface Row {
  type: string
  name: string
  category: string
  categoryLabel: string
  description: string
  params: ParamDef[]
  values: Record<string, any>
  weight: number
  hard: boolean
  enabled: boolean
}

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

interface OptionItem {
  id: number
  name: string
}

interface CategoryGroup {
  category: string
  categoryLabel: string
  rows: Row[]
}

const auth = useAuthStore()
const settings = useSettingsStore()
const canManage = computed(() => auth.hasPermission('constraint:manage'))
const isJunior = computed(() => settings.schoolType === 'junior')

const semesters = ref<Semester[]>([])
const semesterId = ref<number | null>(null)
const types = ref<ConstraintType[]>([])
const rows = ref<Row[]>([])
const teachers = ref<OptionItem[]>([])
const courses = ref<OptionItem[]>([])
const templates = ref<TemplateItem[]>([])
const selectedTemplate = ref<number | null>(null)
const loading = ref(false)
const saving = ref(false)
const applyingTemplate = ref(false)
const deletingTemplate = ref(false)
const activeCategories = ref<string[]>([])

const dayOptions = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 7, label: '周日' },
]

const roomTypeOptions = [
  { value: 'NORMAL', label: '普通教室' },
  { value: 'LAB', label: '实验室' },
  { value: 'PLAYGROUND', label: '操场' },
  { value: 'MULTIMEDIA', label: '多媒体' },
  { value: 'OTHER', label: '其他' },
]

// ADR-0018: junior mode hides senior-only constraints from the editor. Hidden
// rows stay in `rows` so saving in junior mode does not erase a senior config.
const visibleRows = computed(() =>
  isJunior.value
    ? rows.value.filter((row) => row.type !== 'TEACHER_MEETING_BLOCKED')
    : rows.value,
)

const enabledCount = computed(() => visibleRows.value.filter((row) => row.enabled).length)

const groups = computed<CategoryGroup[]>(() => {
  const map = new Map<string, CategoryGroup>()
  visibleRows.value.forEach((row) => {
    let group = map.get(row.categoryLabel)
    if (!group) {
      group = {
        category: row.category,
        categoryLabel: row.categoryLabel,
        rows: [],
      }
      map.set(row.categoryLabel, group)
    }
    group.rows.push(row)
  })
  return Array.from(map.values())
})

function idOptions(param: ParamDef): OptionItem[] {
  const lower = param.name.toLowerCase()
  if (lower.includes('teacher')) {
    return teachers.value
  }
  if (lower.includes('course')) {
    return courses.value
  }
  return []
}

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
    rows.value = []
    return
  }
  loading.value = true
  try {
    const [typeRes, configRes] = await Promise.all([
      http.get<ConstraintType[]>('/constraint-types'),
      http.get<ConstraintConfig[]>('/constraints', { params: { semesterId: semesterId.value } }),
    ])
    types.value = typeRes.data ?? []
    const configs = configRes.data ?? []

    rows.value = types.value.map((type) => {
      const saved = configs.find((config) => config.type === type.code)
      const values: Record<string, any> = {}
      type.params.forEach((param) => {
        const savedValue = saved?.params?.[param.name]
        values[param.name] = savedValue !== undefined ? savedValue : (param.defaultValue ?? null)
      })
      return {
        type: type.code,
        name: type.name,
        category: type.category,
        categoryLabel: type.categoryLabel,
        description: type.description,
        params: type.params,
        values,
        weight: saved?.weight !== undefined ? saved.weight : type.defaultWeight,
        hard: saved?.hard !== undefined ? saved.hard : type.defaultHard,
        enabled: saved?.enabled !== undefined ? saved.enabled : false,
      }
    })

    if (activeCategories.value.length === 0) {
      activeCategories.value = Array.from(new Set(rows.value.map((r) => r.categoryLabel)))
    }
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '加载约束配置失败'))
  } finally {
    loading.value = false
  }
}

async function onSemesterChange() {
  await loadConstraints()
}

async function save() {
  if (!semesterId.value) return

  const missing = visibleRows.value
    .filter((row) => row.enabled)
    .flatMap((row) =>
      row.params
        .filter((param) => param.required && (row.values[param.name] === null || row.values[param.name] === undefined || row.values[param.name] === ''))
        .map((param) => `「${row.name}」的「${param.label}」`),
    )
  if (missing.length > 0) {
    ElMessage.warning(`请先填写必填参数：${missing.slice(0, 3).join('、')}${missing.length > 3 ? ' 等' : ''}`)
    return
  }

  saving.value = true
  try {
    const configs = rows.value.map((row) => {
      const cleanParams: Record<string, any> = {}
      if (row.enabled) {
        row.params.forEach((param) => {
          const val = row.values[param.name]
          if (val !== null && val !== undefined && val !== '') {
            cleanParams[param.name] = val
          }
        })
      }
      return {
        type: row.type,
        params: cleanParams,
        // Junior mode only exposes "must / prefer": must = weight 100, prefer = 50.
        weight: isJunior.value ? (row.hard ? 100 : 50) : row.weight,
        hard: row.hard,
        enabled: row.enabled,
      }
    })

    await http.put(
      '/constraints',
      { configs },
      { params: { semesterId: semesterId.value } },
    )
    ElMessage.success('约束配置已保存')
    await loadConstraints()
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '保存约束配置失败'))
  } finally {
    saving.value = false
  }
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
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
      },
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
    await Promise.all([
      loadSemesters(),
      loadTeachersAndCourses(),
      loadTemplates(),
    ])
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
  margin-bottom: 16px;
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
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 12px;
}

.summary-area {
  display: flex;
  align-items: center;
}

.count-tag {
  font-size: 13px;
  padding: 6px 12px;
}

.template-area {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.content-card {
  border-radius: var(--paiban-radius, 8px);
}

.collapse-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
}

.group-title {
  font-weight: 600;
  color: var(--paiban-ink);
}

.group-summary {
  font-size: 13px;
  color: var(--paiban-muted);
  font-weight: normal;
}

.constraint-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 8px 0;
}

.constraint-card {
  padding: 14px 16px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid #ebeef5;
  transition: all 0.2s ease;
}

.constraint-card:hover {
  border-color: #dcdfe6;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.constraint-card.is-disabled {
  background: #fafafa;
  opacity: 0.75;
}

.constraint-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.constraint-main {
  display: flex;
  align-items: center;
  gap: 16px;
  flex: 1;
  min-width: 280px;
}

.constraint-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.constraint-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--paiban-ink);
}

.constraint-desc {
  font-size: 12px;
  color: var(--paiban-muted);
  line-height: 1.4;
}

.constraint-controls {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-shrink: 0;
}

.weight-box {
  display: flex;
  align-items: center;
  gap: 8px;
}

.weight-label {
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  color: var(--paiban-muted);
  white-space: nowrap;
  flex-shrink: 0;
}

.params-box {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed #ebeef5;
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: center;
}

.param-field {
  display: flex;
  align-items: center;
  gap: 6px;
}

.param-label {
  font-size: 13px;
  color: #4b5563;
}

.required-star {
  color: var(--el-color-danger, #f56c6c);
  margin-right: 2px;
  font-weight: bold;
}
</style>
