<template>
  <div class="manual-import" data-testid="manual-import">
    <header class="head">
      <div>
        <h2>手动选列导入</h2>
        <p class="subtitle">
          上传 Excel / CSV，自己指定班级、星期、节次、课程、教师对应的列，预览后生成草稿。
          整个过程不调用 AI。
        </p>
      </div>
      <router-link to="/data/import" class="back">去 AI 导入</router-link>
    </header>

    <el-card shadow="never" class="block">
      <template #header><span class="card-title">1. 选择文件</span></template>
      <div class="upload">
        <input
          ref="fileInput"
          type="file"
          accept=".xlsx,.xls,.csv"
          data-testid="manual-file"
          @change="onFileChange"
        >
        <span v-if="file" class="file-name">{{ file.name }}</span>
        <el-button v-if="inspecting" size="small" loading>正在识别工作表…</el-button>
      </div>
      <p class="hint">支持 Excel 与 CSV；复合键「班级|星期|节次」可以用任意分隔符。</p>
    </el-card>

    <el-card v-if="inspect" shadow="never" class="block">
      <template #header>
        <div class="card-head">
          <span class="card-title">2. 选择工作表与列</span>
          <el-button size="small" :loading="previewing" data-testid="manual-preview-button" @click="runPreview">
            预览
          </el-button>
        </div>
      </template>
      <ManualImportMapper v-if="mapping" v-model="mapping" :sheets="inspect.sheets" />
    </el-card>

    <el-card v-if="preview" shadow="never" class="block">
      <template #header>
        <div class="card-head">
          <span class="card-title">3. 预览与入库</span>
          <el-tag v-if="preview.recipeMatch.kind !== 'NONE'" size="small" type="success" effect="plain">
            {{ preview.recipeMatch.kind === 'EXACT' ? '已套用配方' : '配方相近' }}
          </el-tag>
        </div>
      </template>

      <ManualImportPreview :preview="preview" />

      <el-alert
        v-if="commitResult?.errors.length"
        type="error"
        show-icon
        :closable="false"
        class="result"
        data-testid="manual-commit-errors"
      >
        <p>有 {{ commitResult.errors.length }} 行无法入库，草稿没有生成：</p>
        <ul>
          <li v-for="(error, index) in commitResult.errors.slice(0, 10)" :key="index">
            第 {{ error.row }} 行：{{ error.message }}
          </li>
        </ul>
      </el-alert>

      <el-alert
        v-if="commitResult?.draftId"
        type="success"
        show-icon
        :closable="false"
        class="result"
        data-testid="manual-commit-result"
      >
        已生成草稿「{{ draftName || '导入课表' }}」：写入 {{ commitResult.imported }} 节，跳过
        {{ commitResult.skippedEmpty }} 个空槽。
        <router-link :to="{ name: 'draft-editor', params: { id: commitResult.draftId } }">
          打开草稿
        </router-link>
        <span v-if="commitResult.appliedRule">，已生成规则「{{ commitResult.appliedRule.text }}」。</span>
      </el-alert>

      <ManualCommitPanel
        v-model:semester-id="semesterId"
        v-model:draft-name="draftName"
        v-model:apply-rule="applyRule"
        v-model:teacher-mappings="teacherMappings"
        :preview="preview"
        :semesters="semesters"
        :teachers="teachers"
        :committing="committing"
        @commit="runCommit"
      />

      <div v-if="canSaveRecipe" class="save-recipe" data-testid="manual-save-recipe">
        <el-button type="primary" plain data-testid="open-save-recipe" @click="recipeDialog = true">
          保存为配方
        </el-button>
        <span class="hint">保存后，下次上传同格式文件会自动套用。</span>
      </div>
      <el-alert
        v-if="savedRecipe"
        type="success"
        show-icon
        :closable="false"
        class="result"
        data-testid="recipe-saved"
      >
        已保存配方「{{ savedRecipe.name }}」，样例测试通过，下次上传同格式文件会自动套用。
      </el-alert>

      <el-dialog v-model="recipeDialog" title="保存为配方" width="480px">
        <el-input
          v-model="recipeName"
          data-testid="recipe-name"
          placeholder="例如：八年级长表"
          maxlength="60"
        />
        <div class="sample-pick">
          <span class="sample-label">样例文件</span>
          <input
            type="file"
            accept=".xlsx,.csv"
            data-testid="recipe-sample-file"
            @change="onRecipeSampleChange"
          >
          <span class="file-name">{{ sampleFileName }}</span>
        </div>
        <p class="hint">
          保存时服务端会用样例文件重跑一遍；数字与下方预览不一致时不会保存。
        </p>
        <el-alert
          v-if="recipeSaveDifferences.length"
          type="error"
          :closable="false"
          show-icon
          class="result"
          data-testid="recipe-save-differences"
        >
          <p>样例测试未通过，配方没有保存：</p>
          <ul>
            <li v-for="difference in recipeSaveDifferences" :key="difference.field">
              {{ difference.label }}：预期 {{ difference.expected }}，实际 {{ difference.actual }}
            </li>
          </ul>
        </el-alert>
        <template #footer>
          <el-button data-testid="cancel-save-recipe" @click="recipeDialog = false">取消</el-button>
          <el-button
            type="primary"
            :loading="savingRecipe"
            data-testid="save-recipe-button"
            @click="saveRecipe"
          >
            保存
          </el-button>
        </template>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverDetails, serverMessage } from '@/api/errors'
import {
  commitManualImport,
  inspectManualImport,
  matchImportRecipe,
  previewManualImport,
  saveImportRecipe,
  type ImportRecipe,
  type ManualCommitResult,
  type ManualInspect,
  type ManualMapping,
  type ManualPreview,
  type RecipeCountDifference,
} from '@/api/imports'
import ManualImportMapper from '@/components/manualImport/ManualImportMapper.vue'
import ManualImportPreview from '@/components/manualImport/ManualImportPreview.vue'
import ManualCommitPanel from '@/components/manualImport/ManualCommitPanel.vue'
import { activeOnly } from '@/utils/activeOptions'

interface SemesterOption {
  id: number
  name: string
  current?: boolean
}

interface TeacherOption {
  id: number
  name: string
  active?: boolean
}

const fileInput = ref<HTMLInputElement | null>(null)
const file = ref<File | null>(null)
const inspect = ref<ManualInspect | null>(null)
const mapping = ref<ManualMapping | null>(null)
const preview = ref<ManualPreview | null>(null)
const commitResult = ref<ManualCommitResult | null>(null)
const inspecting = ref(false)
const previewing = ref(false)
const committing = ref(false)
const savingRecipe = ref(false)
const recipeDialog = ref(false)
const savedRecipe = ref<ImportRecipe | null>(null)
const recipeName = ref('')
const recipeSampleFile = ref<File | null>(null)
const recipeSaveDifferences = ref<RecipeCountDifference[]>([])
const semesters = ref<SemesterOption[]>([])
const teachers = ref<TeacherOption[]>([])
const semesterId = ref<number | null>(null)
const draftName = ref('')
const applyRule = ref(true)
const teacherMappings = ref<Record<string, string>>({})

const canSaveRecipe = computed(
  () => !!commitResult.value?.draftId && !commitResult.value.appliedRecipeId && !savedRecipe.value,
)

/** Default to the file being imported; the dialog can pick a separate sample. */
const sampleFileName = computed(
  () => recipeSampleFile.value?.name ?? file.value?.name ?? '未选择',
)

function onRecipeSampleChange(event: Event) {
  const input = event.target as HTMLInputElement
  recipeSampleFile.value = input.files?.[0] ?? null
}

onMounted(async () => {
  await Promise.all([loadSemesters(), loadTeachers()])
})

async function loadSemesters() {
  try {
    const { data } = await http.get<SemesterOption[]>('/semesters')
    semesters.value = data
    const current = data.find((item) => item.current) ?? data[0]
    semesterId.value = current?.id ?? null
  } catch (error) {
    ElMessage.error(serverMessage(error, '加载学期失败'))
  }
}

async function loadTeachers() {
  try {
    const { data } = await http.get<TeacherOption[]>('/teachers')
    teachers.value = activeOnly(data ?? [])
  } catch (error) {
    ElMessage.error(serverMessage(error, '加载教师列表失败'))
  }
}

async function onFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const selected = target.files?.[0] ?? null
  resetWorkflow()
  file.value = selected
  if (!selected) return
  inspecting.value = true
  try {
    const result = await inspectManualImport(selected)
    inspect.value = result
    const first = result.sheets[0]
    mapping.value = first ? { ...first.suggestedMapping, sheetName: first.name } : null
    const match = await matchImportRecipe(selected).catch(() => null)
    if (match && match.mapping && match.kind !== 'NONE') {
      mapping.value = { ...match.mapping }
      await runPreview()
    }
  } catch (error) {
    ElMessage.error(serverMessage(error, '识别工作表失败'))
  } finally {
    inspecting.value = false
  }
}

function resetWorkflow() {
  inspect.value = null
  mapping.value = null
  preview.value = null
  commitResult.value = null
  savedRecipe.value = null
  recipeName.value = ''
  recipeSampleFile.value = null
  recipeSaveDifferences.value = []
  recipeDialog.value = false
  teacherMappings.value = {}
  applyRule.value = true
  draftName.value = ''
}

async function runPreview() {
  if (!file.value || !mapping.value) return
  previewing.value = true
  commitResult.value = null
  savedRecipe.value = null
  try {
    preview.value = await previewManualImport(file.value, mapping.value, semesterId.value)
    teacherMappings.value = {}
    applyRule.value = !!preview.value.suggestedRule
    if (!draftName.value) {
      draftName.value = `手动导入 ${new Date().toLocaleDateString()}`
    }
  } catch (error) {
    ElMessage.error(serverMessage(error, '预览失败'))
  } finally {
    previewing.value = false
  }
}

async function runCommit() {
  if (!file.value || !mapping.value) return
  committing.value = true
  commitResult.value = null
  try {
    commitResult.value = await commitManualImport(file.value, mapping.value, {
      semesterId: semesterId.value,
      draftName: draftName.value,
      teacherNameMappings: teacherMappings.value,
      applySuggestedRule: applyRule.value,
    })
    if (commitResult.value.draftId) {
      ElMessage.success(`已写入 ${commitResult.value.imported} 节`)
    } else {
      ElMessage.error(`有 ${commitResult.value.errors.length} 行无法入库`)
    }
  } catch (error) {
    ElMessage.error(serverMessage(error, '入库失败'))
  } finally {
    committing.value = false
  }
}

async function saveRecipe() {
  if (!preview.value || !mapping.value) return
  if (!recipeName.value.trim()) {
    ElMessage.warning('请先给配方起个名字')
    return
  }
  savingRecipe.value = true
  recipeSaveDifferences.value = []
  try {
    savedRecipe.value = await saveImportRecipe(
      {
        name: recipeName.value.trim(),
        layout: preview.value.layout,
        fingerprint: preview.value.fingerprint,
        columns: {
          ...mapping.value,
          delimiter: mapping.value.delimiter || '|',
          ignoreBlankRows: mapping.value.ignoreBlankRows !== false,
        },
        options: {
          delimiter: mapping.value.delimiter || '|',
          ignoreBlankRows: mapping.value.ignoreBlankRows !== false,
        },
        expected: {
          slotCount: preview.value.slotCount,
          lessonCount: preview.value.lessonCount,
          emptySlotCount: preview.value.emptySlotCount,
          teacherCount: preview.value.teacherCount,
          courseCount: preview.value.courseCount,
        },
      },
      recipeSampleFile.value ?? file.value,
    )
    recipeDialog.value = false
    ElMessage.success('配方已保存，样例测试通过')
  } catch (error) {
    const details = serverDetails(error)
    recipeSaveDifferences.value = Array.isArray(details.differences)
      ? (details.differences as RecipeCountDifference[])
      : []
    ElMessage.error(serverMessage(error, '保存配方失败'))
  } finally {
    savingRecipe.value = false
  }
}
</script>

<style scoped>
.manual-import {
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
  margin: 0;
  font-size: 18px;
}
.subtitle {
  margin: 6px 0 0;
  color: var(--paiban-muted);
  font-size: 13px;
}
.back {
  font-size: 13px;
  color: var(--paiban-primary);
  text-decoration: none;
}
.block {
  border: 1px solid #eef0f5;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-title {
  font-weight: 600;
}
.upload {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.file-name {
  font-size: 13px;
  color: var(--paiban-ink);
}
.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--paiban-muted);
}
.result {
  margin-top: 12px;
}
.result ul {
  margin: 4px 0 0;
  padding-left: 18px;
}
.save-recipe {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed #e5e7ef;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  color: var(--paiban-muted);
  min-width: 240px;
}
.sample-pick {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  font-size: 13px;
}
.sample-label {
  color: var(--paiban-muted);
}
.file-name {
  font-size: 13px;
  color: var(--paiban-ink);
}
</style>
