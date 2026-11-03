<template>
  <div class="recipes" data-testid="import-recipes">
    <header class="head">
      <div>
        <h2>导入配方</h2>
        <p class="subtitle">
          配方记住一种表格的列映射。修改后必须用样例文件重跑，数字对得上才能保存；每次保存都会留下版本。
        </p>
      </div>
      <div class="head-actions">
        <router-link to="/data/import/manual" class="link">去手动导入</router-link>
        <el-button size="small" :loading="loading" data-testid="recipe-refresh" @click="load">
          刷新
        </el-button>
      </div>
    </header>

    <el-empty v-if="!loading && recipes.length === 0" description="还没有导入配方">
      <router-link to="/data/import/manual">
        <el-button type="primary">用手动导入创建第一个配方</el-button>
      </router-link>
    </el-empty>

    <el-card v-else shadow="never">
      <el-table v-loading="loading" :data="recipes" stripe row-key="id">
        <el-table-column prop="name" label="名称" min-width="200" />
        <el-table-column label="布局" width="100">
          <template #default="{ row }">{{ layoutLabel(row.layout) }}</template>
        </el-table-column>
        <el-table-column label="最后使用" width="180">
          <template #default="{ row }">
            {{ row.lastUsedAt ? formatDateTime(row.lastUsedAt) : '未使用' }}
          </template>
        </el-table-column>
        <el-table-column label="版本" width="80">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="330">
          <template #default="{ row }">
            <el-button link type="primary" data-testid="recipe-detail" @click="openDetail(row)">
              查看
            </el-button>
            <el-button link type="primary" data-testid="recipe-test" @click="runRowTest(row)">
              测试
            </el-button>
            <el-button link type="primary" data-testid="recipe-edit" @click="openEdit(row)">
              编辑
            </el-button>
            <el-button link type="primary" data-testid="recipe-versions" @click="openVersions(row)">
              版本
            </el-button>
            <el-button link type="danger" data-testid="recipe-delete" @click="remove(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="detailVisible" title="配方详情" size="520px" data-testid="recipe-detail-drawer">
      <template v-if="selected">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="名称">{{ selected.name }}</el-descriptions-item>
          <el-descriptions-item label="布局">{{ layoutLabel(selected.layout) }}</el-descriptions-item>
          <el-descriptions-item label="版本">v{{ selected.version }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ selected.enabled ? '启用' : '停用' }}
          </el-descriptions-item>
          <el-descriptions-item label="最后使用">
            {{ selected.lastUsedAt ? formatDateTime(selected.lastUsedAt) : '未使用' }}
          </el-descriptions-item>
          <el-descriptions-item label="样例文件">
            {{ selected.sample?.fileName || '没有样例文件' }}
          </el-descriptions-item>
          <el-descriptions-item label="映射摘要">{{ mappingSummary(selected) }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="section">预期结果</h4>
        <div class="counts">
          <span v-for="item in expectedItems(selected)" :key="item.label">
            {{ item.label }} <strong>{{ item.value }}</strong>
          </span>
        </div>

        <h4 class="section">最近一次测试</h4>
        <el-alert
          v-if="detailTest"
          :type="detailTest.pass ? 'success' : 'error'"
          :closable="false"
          show-icon
          data-testid="recipe-detail-test"
        >
          {{ detailTest.pass ? '样例测试通过，数字与预期一致。' : '样例测试未通过，请检查映射或预期结果。' }}
          <ul v-if="detailTest.differences.length" class="differences">
            <li v-for="difference in detailTest.differences" :key="difference.field">
              {{ difference.label }}：预期 {{ difference.expected }}，实际 {{ difference.actual }}
            </li>
          </ul>
        </el-alert>
        <el-alert v-else type="info" :closable="false" title="还没有测试结果。" />
        <el-button
          class="retest"
          size="small"
          :loading="testing"
          data-testid="recipe-retest"
          @click="runDetailTest(false)"
        >
          重新测试
        </el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="versionsVisible" title="版本历史" width="640px" data-testid="recipe-versions-dialog">
      <el-table v-loading="versionsLoading" :data="versions" size="small">
        <el-table-column label="版本" width="80">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="createdBy" label="操作人" width="120" />
        <el-table-column prop="summary" label="摘要" min-width="200" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              data-testid="recipe-rollback"
              :disabled="row.version === selected?.version"
              @click="rollback(row.version)"
            >
              回退
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="hint">回退会生成一个新版本，历史不会被删除；回退后仍会用该版本的样例重新测试。</p>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑配方" width="560px" data-testid="recipe-edit-dialog">
      <el-form v-if="editForm" label-width="110px" size="small">
        <el-form-item label="名称">
          <el-input v-model="editForm.name" data-testid="recipe-edit-name" maxlength="60" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editForm.enabled" data-testid="recipe-edit-enabled" />
        </el-form-item>
        <el-form-item label="复合键列">
          <el-input-number v-model="editForm.keyColumn" :min="0" data-testid="recipe-edit-key" />
          <span class="field-hint">0 表示第一列</span>
        </el-form-item>
        <el-form-item label="课程列">
          <el-input-number v-model="editForm.courseColumn" :min="0" data-testid="recipe-edit-course" />
        </el-form-item>
        <el-form-item label="教师列">
          <el-input-number v-model="editForm.teacherColumn" :min="0" data-testid="recipe-edit-teacher" />
        </el-form-item>
        <el-form-item label="分隔符">
          <el-input v-model="editForm.delimiter" data-testid="recipe-edit-delimiter" maxlength="4" />
        </el-form-item>
        <el-divider content-position="left">预期结果</el-divider>
        <div class="count-grid">
          <el-form-item v-for="field in COUNT_FIELDS" :key="field.key" :label="field.label">
            <el-input-number
              v-model="editForm.expected[field.key]"
              :min="0"
              :data-testid="`recipe-edit-${field.key}`"
            />
          </el-form-item>
        </div>
        <el-form-item label="更换样例">
          <input
            type="file"
            accept=".xlsx,.csv"
            data-testid="recipe-edit-sample"
            @change="onEditSample"
          >
          <span class="field-hint">
            {{ editSample ? editSample.name : '不选则沿用已存样例' }}
          </span>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="editDifferences.length"
        type="error"
        :closable="false"
        show-icon
        class="edit-differences"
        data-testid="recipe-edit-differences"
      >
        <p>样例测试未通过，配方没有保存：</p>
        <ul>
          <li v-for="difference in editDifferences" :key="difference.field">
            {{ difference.label }}：预期 {{ difference.expected }}，实际 {{ difference.actual }}
          </li>
        </ul>
      </el-alert>

      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          data-testid="recipe-edit-save"
          @click="saveEdit"
        >
          保存并测试
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { serverDetails, serverMessage } from '@/api/errors'
import {
  deleteImportRecipe,
  listImportRecipes,
  listImportRecipeVersions,
  rollbackImportRecipe,
  testImportRecipe,
  updateImportRecipe,
  type ImportRecipe,
  type ManualMapping,
  type RecipeCountDifference,
  type RecipeTestResult,
  type RecipeVersion,
} from '@/api/imports'

const COUNT_FIELDS = [
  { key: 'slotCount', label: '槽数' },
  { key: 'lessonCount', label: '有课数' },
  { key: 'emptySlotCount', label: '空槽数' },
  { key: 'teacherCount', label: '教师数' },
  { key: 'courseCount', label: '课程数' },
] as const

const loading = ref(false)
const recipes = ref<ImportRecipe[]>([])

const selected = ref<ImportRecipe | null>(null)
const detailVisible = ref(false)
const detailTest = ref<RecipeTestResult | null>(null)
const testing = ref(false)

const versionsVisible = ref(false)
const versionsLoading = ref(false)
const versions = ref<RecipeVersion[]>([])

const editVisible = ref(false)
const saving = ref(false)
const editSample = ref<File | null>(null)
const editDifferences = ref<RecipeCountDifference[]>([])
const editForm = ref<EditForm | null>(null)

interface EditForm {
  name: string
  enabled: boolean
  keyColumn: number | undefined
  courseColumn: number | undefined
  teacherColumn: number | undefined
  delimiter: string
  expected: Record<string, number>
}

onMounted(load)

async function load() {
  loading.value = true
  try {
    recipes.value = await listImportRecipes()
  } catch (error) {
    ElMessage.error(serverMessage(error, '加载配方失败'))
  } finally {
    loading.value = false
  }
}

function layoutLabel(layout: string): string {
  return layout === 'GRID' ? '宽表' : '长表'
}

function formatDateTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

function mappingSummary(recipe: ImportRecipe): string {
  const columns = recipe.columns as ManualMapping
  const parts = [
    `复合键列 ${columnText(columns.keyColumn)}`,
    `课程列 ${columnText(columns.courseColumn)}`,
    `教师列 ${columnText(columns.teacherColumn)}`,
    `分隔符「${columns.delimiter || '|'}」`,
  ]
  return parts.join(' · ')
}

function columnText(value: number | null | undefined): string {
  return value === null || value === undefined ? '未设置' : String(value)
}

function expectedItems(recipe: ImportRecipe) {
  return COUNT_FIELDS.map((field) => ({
    label: field.label,
    value: recipe.expected?.[field.key] ?? '—',
  }))
}

function differencesOf(error: unknown): RecipeCountDifference[] {
  const details = serverDetails(error)
  return Array.isArray(details.differences)
    ? (details.differences as RecipeCountDifference[])
    : []
}

async function openDetail(recipe: ImportRecipe) {
  selected.value = recipe
  detailTest.value = null
  detailVisible.value = true
  await runDetailTest(true)
}

async function runDetailTest(silent: boolean) {
  if (!selected.value) return
  testing.value = true
  try {
    detailTest.value = await testImportRecipe(selected.value.id)
    if (!silent) {
      if (detailTest.value.pass) {
        ElMessage.success('样例测试通过')
      } else {
        ElMessage.error('样例测试未通过')
      }
    }
  } catch (error) {
    detailTest.value = null
    ElMessage.error(serverMessage(error, '样例测试失败'))
  } finally {
    testing.value = false
  }
}

async function runRowTest(recipe: ImportRecipe) {
  selected.value = recipe
  await runDetailTest(false)
  if (detailTest.value) {
    detailVisible.value = false
  }
}

function openEdit(recipe: ImportRecipe) {
  selected.value = recipe
  const columns = recipe.columns as ManualMapping
  editForm.value = {
    name: recipe.name,
    enabled: recipe.enabled,
    keyColumn: columns.keyColumn ?? undefined,
    courseColumn: columns.courseColumn ?? undefined,
    teacherColumn: columns.teacherColumn ?? undefined,
    delimiter: columns.delimiter || '|',
    expected: {
      slotCount: recipe.expected?.slotCount ?? 0,
      lessonCount: recipe.expected?.lessonCount ?? 0,
      emptySlotCount: recipe.expected?.emptySlotCount ?? 0,
      teacherCount: recipe.expected?.teacherCount ?? 0,
      courseCount: recipe.expected?.courseCount ?? 0,
    },
  }
  editSample.value = null
  editDifferences.value = []
  editVisible.value = true
}

function onEditSample(event: Event) {
  const input = event.target as HTMLInputElement
  editSample.value = input.files?.[0] ?? null
}

async function saveEdit() {
  if (!selected.value || !editForm.value) return
  const form = editForm.value
  saving.value = true
  editDifferences.value = []
  try {
    await updateImportRecipe(
      selected.value.id,
      {
        name: form.name,
        enabled: form.enabled,
        columns: {
          ...(selected.value.columns as ManualMapping),
          keyColumn: form.keyColumn ?? null,
          courseColumn: form.courseColumn ?? null,
          teacherColumn: form.teacherColumn ?? null,
          delimiter: form.delimiter || '|',
        },
        expected: { ...form.expected },
      },
      editSample.value,
    )
    ElMessage.success('配方已保存，样例测试通过')
    editVisible.value = false
    await load()
  } catch (error) {
    const differences = differencesOf(error)
    editDifferences.value = differences
    ElMessage.error(serverMessage(error, '保存配方失败'))
  } finally {
    saving.value = false
  }
}

async function openVersions(recipe: ImportRecipe) {
  selected.value = recipe
  versions.value = []
  versionsVisible.value = true
  versionsLoading.value = true
  try {
    versions.value = await listImportRecipeVersions(recipe.id)
  } catch (error) {
    ElMessage.error(serverMessage(error, '加载版本历史失败'))
  } finally {
    versionsLoading.value = false
  }
}

async function rollback(version: number) {
  if (!selected.value) return
  try {
    await ElMessageBox.confirm(
      `回退到 v${version}？会生成一个新版本，历史不会被删除。`,
      '回退配方',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const updated = await rollbackImportRecipe(selected.value.id, version)
    ElMessage.success(`已回退，当前版本 v${updated.version}`)
    versionsVisible.value = false
    selected.value = updated
    await load()
  } catch (error) {
    ElMessage.error(serverMessage(error, '回退失败'))
  }
}

async function remove(recipe: ImportRecipe) {
  try {
    await ElMessageBox.confirm(
      `删除配方「${recipe.name}」？删除后进入最近删除，7 天内可恢复。`,
      '删除配方',
      { type: 'warning', confirmButtonText: '删除' },
    )
  } catch {
    return
  }
  try {
    await deleteImportRecipe(recipe.id)
    ElMessage.success('已删除，可在「最近删除」中恢复')
    await load()
  } catch (error) {
    ElMessage.error(serverMessage(error, '删除失败'))
  }
}
</script>

<style scoped>
.recipes {
  display: grid;
  gap: 14px;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}
.head h2 {
  margin: 0 0 4px;
  font-size: 18px;
}
.subtitle {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 13px;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.link {
  font-size: 13px;
  color: var(--paiban-primary);
  text-decoration: none;
}
.section {
  margin: 18px 0 8px;
  font-size: 13px;
}
.counts {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 13px;
  color: var(--paiban-muted);
}
.counts strong {
  color: var(--paiban-ink);
  font-size: 16px;
  margin-left: 4px;
}
.differences {
  margin: 6px 0 0;
  padding-left: 18px;
}
.retest {
  margin-top: 10px;
}
.hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--paiban-muted);
}
.field-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--paiban-muted);
}
.count-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 10px;
}
.edit-differences {
  margin-top: 8px;
}
.edit-differences ul {
  margin: 4px 0 0;
  padding-left: 18px;
}
</style>
