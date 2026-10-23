<template>
  <el-card v-if="groups.length" shadow="never" data-testid="mapping-step">
    <template #header>
      <div class="card-head">
        <span class="card-title">映射与修正（{{ issues.length }} 项未解决）</span>
        <span class="hint">未匹配的名称必须映射到系统数据或确认创建后才能入库。</span>
      </div>
    </template>

    <div class="batch-bar">
      <span class="hint">已选 {{ selected.length }} 项</span>
      <el-button size="small" :disabled="!selected.length" @click="clearSelection">清空选择</el-button>
      <el-button
        size="small"
        type="primary"
        :disabled="!selectedCreatable.length"
        :loading="busy === 'batch'"
        @click="batchCreate"
      >
        批量创建所选（{{ selectedCreatable.length }}）
      </el-button>
      <template v-if="selectedHasClasses">
        <span class="hint">班级人数</span>
        <el-input-number
          v-model="batchStudentCount"
          size="small"
          :min="0"
          :step="1"
          step-strictly
          controls-position="right"
          data-testid="batch-student-count"
        />
      </template>
    </div>

    <el-table
      ref="tableRef"
      :data="groups"
      size="small"
      border
      row-key="key"
      @selection-change="onSelectionChange"
    >
      <el-table-column type="selection" width="42" :selectable="isSelectable" />
      <el-table-column label="行号" width="100">
        <template #default="{ row }">{{ row.rows.join('、') }}</template>
      </el-table-column>
      <el-table-column prop="label" label="字段" width="80" />
      <el-table-column prop="value" label="原值" min-width="140" />
      <el-table-column label="年级" min-width="150">
        <template #default="{ row }">
          <template v-if="row.field === 'className'">
            <el-tag
              v-if="row.inferredGrade"
              size="small"
              :type="gradeStatusOf(row) === 'existing' ? 'success' : 'warning'"
              data-testid="grade-hint"
            >
              {{ row.inferredGrade }} {{ gradeStatusOf(row) === 'existing' ? '已有' : '新建' }}
            </el-tag>
            <el-select
              v-else
              v-model="manualGrades[row.key]"
              size="small"
              placeholder="选择年级"
              class="grade-select"
              data-testid="manual-grade"
            >
              <el-option
                v-for="grade in grades"
                :key="grade.id"
                :label="grade.name"
                :value="grade.id"
              />
            </el-select>
          </template>
          <span v-else class="hint">—</span>
        </template>
      </el-table-column>
      <el-table-column label="处理" min-width="420">
        <template #default="{ row }">
          <div v-if="row.kind === 'entity'" class="resolve">
            <el-select
              :model-value="null"
              size="small"
              filterable
              clearable
              placeholder="选择系统中已有的记录"
              :loading="busy === row.key"
              class="resolve-select"
              :data-testid="`resolve-${row.field}`"
              remote
              :remote-method="(query: string) => search(row, query)"
              @visible-change="(open: boolean) => toggleOptions(row, open)"
              @change="(value: string) => mapToEntity(row, value)"
            >
              <el-option
                v-for="candidate in optionsOf(row)"
                :key="candidate"
                :label="candidate"
                :value="candidate"
              />
            </el-select>
            <div class="create-line">
              <el-checkbox v-model="createEnabled[row.key]">系统中没有，创建</el-checkbox>
              <template v-if="createEnabled[row.key]">
                <el-input
                  :model-value="createNames[row.key] ?? row.value"
                  size="small"
                  class="create-name"
                  placeholder="新记录名称"
                  @update:model-value="(value: string) => setCreateName(row, value)"
                />
                <template v-if="createRequiresGrade(row.field)">
                  <span class="hint">人数</span>
                  <el-input-number
                    :model-value="studentCountOf(row.key)"
                    size="small"
                    :min="0"
                    :step="1"
                    step-strictly
                    controls-position="right"
                    class="student-count"
                    data-testid="student-count"
                    @update:model-value="(value: number | null | undefined) => setStudentCount(row.key, value)"
                  />
                </template>
                <el-button
                  size="small"
                  type="primary"
                  :loading="busy === row.key"
                  @click="create(row)"
                >
                  确认创建
                </el-button>
              </template>
            </div>
          </div>
          <div v-else class="resolve">
            <el-select
              :model-value="null"
              size="small"
              placeholder="选择正确的值"
              class="resolve-select"
              :data-testid="`resolve-${row.field}`"
              @change="(value: string) => fixSlot(row, value)"
            >
              <el-option
                v-for="candidate in row.candidates"
                :key="candidate"
                :label="candidate"
                :value="candidate"
              />
            </el-select>
            <span class="hint">把第 {{ row.rows.join('、') }} 行改成所选值</span>
          </div>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-alert
    v-else
    type="success"
    show-icon
    :closable="false"
    title="所有名称都已匹配，可以确认入库"
    data-testid="mapping-clear"
  />
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import {
  applySlotValue,
  createRequiresGrade,
  gradeStatusOf,
  lookupType,
  mappingGroups,
  normalizeGradeName,
  type CreateItemPayload,
  type ImportPreview,
  type LookupItem,
  type MappingGroup,
  type MappingItemPayload,
  type PreviewIssue,
  type PreviewRow,
} from './timetablePreview'

const props = defineProps<{
  sessionId: number
  rows: PreviewRow[]
  issues: PreviewIssue[]
}>()

const emit = defineEmits<{
  (e: 'updated', preview: ImportPreview): void
}>()

interface GradeOption {
  id: number
  name: string
}

const groups = computed(() => mappingGroups(props.issues))
const busy = ref('')
const tableRef = ref()
const selected = ref<MappingGroup[]>([])
const DEFAULT_STUDENT_COUNT = 45
const batchStudentCount = ref(DEFAULT_STUDENT_COUNT)
const selectedCreatable = computed(() => selected.value.filter((group) => group.kind === 'entity'))
const selectedHasClasses = computed(() =>
  selectedCreatable.value.some((group) => createRequiresGrade(group.field)),
)
const grades = ref<GradeOption[]>([])
const lookupOptions = reactive<Record<string, string[]>>({})
const createEnabled = reactive<Record<string, boolean>>({})
const createNames = reactive<Record<string, string>>({})
const createStudentCounts = reactive<Record<string, number>>({})
const manualGrades = reactive<Record<string, number | undefined>>({})

onMounted(async () => {
  try {
    const { data } = await http.get<GradeOption[]>('/grades')
    grades.value = data ?? []
  } catch {
    grades.value = []
  }
})

function optionsOf(group: MappingGroup): string[] {
  const searched = lookupOptions[group.key]
  return searched?.length ? searched : group.candidates
}

async function search(group: MappingGroup, query: string) {
  const type = lookupType(group.field)
  if (!type) return
  try {
    const { data } = await http.get<LookupItem[]>('/ai/import/lookup', {
      params: { type, q: query ?? '' },
    })
    lookupOptions[group.key] = (data ?? []).map((item) => item.name)
  } catch {
    // Keep the previous options; the candidates from the preview still work.
  }
}

function toggleOptions(group: MappingGroup, open: boolean) {
  if (open && !lookupOptions[group.key]) {
    void search(group, '')
  }
}

function setCreateName(group: MappingGroup, value: string) {
  createNames[group.key] = value
}

function studentCountOf(key: string): number {
  return createStudentCounts[key] ?? DEFAULT_STUDENT_COUNT
}

function setStudentCount(key: string, value: number | null | undefined) {
  createStudentCounts[key] = value ?? 0
}

/**
 * The grade to create a class under: an existing semester grade is sent by id, an
 * inferred-but-missing grade by name (the backend creates it), and a class with no
 * inference needs the user's manual pick. Null means the class cannot be created yet.
 */
function classGrade(group: MappingGroup): { gradeId?: number; gradeName?: string } | null {
  if (gradeStatusOf(group) === 'existing') {
    const match = grades.value.find(
      (grade) => normalizeGradeName(grade.name) === normalizeGradeName(group.inferredGrade),
    )
    if (match) return { gradeId: match.id }
  }
  if (group.inferredGrade) return { gradeName: group.inferredGrade }
  const manual = manualGrades[group.key]
  return manual ? { gradeId: manual } : null
}

function createItem(
  group: MappingGroup,
  name: string,
  studentCount: number,
): CreateItemPayload | null {
  const payload: CreateItemPayload = { field: group.field, source: group.value, name }
  if (!createRequiresGrade(group.field)) return payload
  const grade = classGrade(group)
  return grade ? { ...payload, ...grade, studentCount } : null
}

async function post(body: {
  mappings?: MappingItemPayload[]
  creates?: CreateItemPayload[]
}) {
  const { data } = await http.post<ImportPreview>(
    `/ai/import/sessions/${props.sessionId}/mapping`,
    { mappings: body.mappings ?? [], creates: body.creates ?? [] },
  )
  emit('updated', data)
}

function isSelectable(row: MappingGroup): boolean {
  return row.kind === 'entity'
}

function onSelectionChange(rows: MappingGroup[]) {
  selected.value = rows
}

function clearSelection() {
  tableRef.value?.clearSelection()
  selected.value = []
}

async function batchCreate() {
  const targets = selectedCreatable.value
  if (!targets.length) return
  const creates = targets.map((group) => createItem(group, group.value, batchStudentCount.value))
  if (creates.some((item) => item === null)) {
    ElMessage.warning('有班级推断不出年级，请先选择年级')
    return
  }
  const counts = new Map<string, number>()
  for (const group of targets) {
    counts.set(group.label, (counts.get(group.label) ?? 0) + 1)
  }
  const summary = [...counts.entries()].map(([label, count]) => `${label} ${count} 项`).join('、')
  try {
    await ElMessageBox.confirm(
      `将创建 ${targets.length} 条记录：${summary}。确认继续？`,
      '批量创建',
      { type: 'warning' },
    )
  } catch {
    return
  }
  busy.value = 'batch'
  try {
    await post({ creates: creates as CreateItemPayload[] })
    clearSelection()
    batchStudentCount.value = DEFAULT_STUDENT_COUNT
    ElMessage.success(`已创建并映射 ${targets.length} 项`)
  } catch (error) {
    ElMessage.error(serverMessage(error, '批量创建失败，请重试'))
  } finally {
    busy.value = ''
  }
}

async function mapToEntity(group: MappingGroup, name: string) {
  const type = lookupType(group.field)
  if (!type || !name) return
  busy.value = group.key
  try {
    const { data } = await http.get<LookupItem[]>('/ai/import/lookup', {
      params: { type, q: name },
    })
    const match = (data ?? []).find((item) => item.name.trim() === name.trim())
    if (!match) {
      ElMessage.error(`找不到记录：${name}`)
      return
    }
    await post({ mappings: [{ field: group.field, source: group.value, targetId: match.id }] })
    ElMessage.success('已映射到系统记录')
  } catch (error) {
    ElMessage.error(serverMessage(error, '映射失败，请重试'))
  } finally {
    busy.value = ''
  }
}

async function create(group: MappingGroup) {
  const name = (createNames[group.key] ?? group.value).trim() || group.value
  const item = createItem(group, name, studentCountOf(group.key))
  if (!item) {
    ElMessage.warning('创建班级需要先选择年级')
    return
  }
  busy.value = group.key
  try {
    await post({ creates: [item] })
    createEnabled[group.key] = false
    ElMessage.success(`已创建并映射：${name}`)
  } catch (error) {
    ElMessage.error(serverMessage(error, '创建失败，请重试'))
  } finally {
    busy.value = ''
  }
}

async function fixSlot(group: MappingGroup, value: string) {
  if (!value || (group.field !== 'dayOfWeek' && group.field !== 'periodNo')) return
  busy.value = group.key
  try {
    const rows = applySlotValue(props.rows, group.field, value, group.rows)
    const { data } = await http.put<ImportPreview>(
      `/ai/import/sessions/${props.sessionId}/rows`,
      { rows },
    )
    emit('updated', data)
    ElMessage.success('已修正')
  } catch (error) {
    ElMessage.error(serverMessage(error, '修正失败，请重试'))
  } finally {
    busy.value = ''
  }
}
</script>

<style scoped>
.card-head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 12px;
}

.card-title {
  font-weight: 600;
}

.batch-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.resolve {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.resolve-select {
  width: 240px;
}

.create-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.create-name {
  width: 180px;
}

.grade-select {
  width: 140px;
}

.student-count {
  width: 110px;
}

.hint {
  color: #6b7280;
  font-size: 12px;
}
</style>
