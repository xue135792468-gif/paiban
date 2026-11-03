<template>
  <div class="preview" data-testid="manual-preview">
    <el-alert
      v-if="preview.recipeMatch.kind === 'EXACT'"
      type="success"
      show-icon
      :closable="false"
      data-testid="recipe-match"
      class="recipe"
    >
      已套用配方「{{ preview.recipeMatch.recipeName }}」，预览结果与保存时一致。
    </el-alert>
    <el-alert
      v-else-if="preview.recipeMatch.kind === 'SIMILAR'"
      type="warning"
      show-icon
      :closable="false"
      data-testid="recipe-match"
      class="recipe"
    >
      <template #title>
        配方「{{ preview.recipeMatch.recipeName }}」与当前文件不完全一致，以下变化请确认：
      </template>
      <ul class="differences" data-testid="recipe-differences">
        <li v-for="(difference, index) in preview.recipeMatch.differences" :key="index">
          <el-tag size="small" :type="differenceKindType(difference.kind)" effect="plain">
            {{ differenceKindLabel(difference.kind) }}
          </el-tag>
          {{ difference.detail }}
        </li>
      </ul>
      <p class="hint">仍可直接入库，或返回上一步修改列映射。</p>
    </el-alert>
    <el-alert
      v-else
      type="info"
      show-icon
      :closable="false"
      data-testid="recipe-match"
      class="recipe"
    >
      未找到配方。确认列映射并成功入库后，可以把它保存为配方，下次同格式自动套用。
    </el-alert>

    <div class="stats">
      <div class="stat" data-testid="stat-slots"><span>槽数</span><strong>{{ preview.slotCount }}</strong></div>
      <div class="stat" data-testid="stat-lessons"><span>有课</span><strong>{{ preview.lessonCount }}</strong></div>
      <div class="stat" data-testid="stat-empty"><span>空槽（不上课）</span><strong>{{ preview.emptySlotCount }}</strong></div>
      <div class="stat" data-testid="stat-teachers"><span>教师</span><strong>{{ preview.teacherCount }}</strong></div>
      <div class="stat" data-testid="stat-courses"><span>课程</span><strong>{{ preview.courseCount }}</strong></div>
    </div>

    <el-alert
      v-if="preview.suggestedRule"
      type="success"
      show-icon
      :closable="false"
      data-testid="suggested-rule"
      class="rule"
    >
      空槽都集中在同一时段：{{ preview.suggestedRule.text }}。入库时可一并生成这条规则。
    </el-alert>

    <div v-if="preview.teacherWarnings.length" class="warnings" data-testid="teacher-warnings">
      <h4>这些“教师”看起来像课程名或占位名，已标黄；可在入库时映射到真实教师。</h4>
      <el-tag
        v-for="warning in preview.teacherWarnings"
        :key="warning.value"
        type="warning"
        effect="plain"
        data-testid="teacher-warning"
      >
        {{ warning.value }}（{{ warning.lessonCount }} 节）
      </el-tag>
    </div>

    <el-collapse v-if="preview.warnings.length" class="notes">
      <el-collapse-item :title="`解析提示（${preview.warnings.length}）`">
        <ul>
          <li v-for="(warning, index) in preview.warnings" :key="index">{{ warning }}</li>
        </ul>
      </el-collapse-item>
    </el-collapse>

    <el-table :data="preview.rows" size="small" max-height="360" class="grid" :row-class-name="rowClass">
      <el-table-column prop="row" label="行" width="64" />
      <el-table-column prop="className" label="班级" min-width="110" />
      <el-table-column label="星期" width="90">
        <template #default="{ row }">{{ dayLabel(row.dayOfWeek) }}</template>
      </el-table-column>
      <el-table-column label="节次" width="80">
        <template #default="{ row }">第{{ row.periodNo }}节</template>
      </el-table-column>
      <el-table-column label="课程" min-width="120">
        <template #default="{ row }">{{ row.empty ? '不上课' : row.courseName }}</template>
      </el-table-column>
      <el-table-column prop="teacherName" label="教师" min-width="110" />
    </el-table>
    <p v-if="preview.truncated" class="hint">仅显示前 {{ preview.rows.length }} 行，统计覆盖整个文件。</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ManualPreview, RecipeDifferenceKind } from '@/api/imports'

const props = defineProps<{ preview: ManualPreview }>()

const DAY_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const warningNames = computed(
  () => new Set(props.preview.teacherWarnings.map((warning) => warning.value)),
)

function dayLabel(day: number): string {
  return DAY_LABELS[day - 1] ?? `第${day}天`
}

function rowClass({ row }: { row: ManualPreview['rows'][number] }): string {
  if (warningNames.value.has(row.teacherName)) return 'row-warning'
  return row.empty ? 'row-empty' : ''
}

function differenceKindLabel(kind: RecipeDifferenceKind): string {
  if (kind === 'ADDED') return '新增'
  if (kind === 'REMOVED') return '删除'
  return '变化'
}

function differenceKindType(kind: RecipeDifferenceKind): 'success' | 'danger' | 'warning' {
  if (kind === 'ADDED') return 'success'
  if (kind === 'REMOVED') return 'danger'
  return 'warning'
}
</script>

<style scoped>
.preview {
  display: grid;
  gap: 12px;
}
.differences {
  margin: 6px 0;
  padding-left: 18px;
}
.recipe :deep(.el-alert__content) {
  width: 100%;
}
.hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--paiban-muted);
}
.stats {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.stat {
  min-width: 120px;
  padding: 10px 14px;
  border: 1px solid #eef0f5;
  border-radius: var(--paiban-radius);
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.stat span {
  font-size: 12px;
  color: var(--paiban-muted);
}
.stat strong {
  font-size: 22px;
}
.warnings {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.warnings h4 {
  width: 100%;
  margin: 0;
  font-size: 13px;
  font-weight: 500;
  color: #b26a00;
}
.notes ul {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
}
.grid :deep(.row-warning) {
  background: #fff7e0;
}
.grid :deep(.row-empty) {
  color: #9aa0aa;
}
</style>
