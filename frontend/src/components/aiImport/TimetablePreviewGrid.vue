<template>
  <div class="timetable-preview">
    <div v-if="summary" class="summary" data-testid="timetable-summary">
      <el-tag size="small" type="info" effect="plain">共 {{ summary.lessons }} 节课</el-tag>
      <el-tag size="small" type="success" effect="plain">
        将创建 {{ summary.newAssignments }} 条授课任务
      </el-tag>
      <el-tag size="small" type="warning" effect="plain">
        将更新 {{ summary.updatedAssignments }} 条授课任务
      </el-tag>
      <span class="hint">{{ summary.groups }} 个班级 × 课程组合</span>
    </div>

    <div v-if="viewOptions.length" class="view-bar">
      <el-radio-group v-model="mode" size="small" data-testid="timetable-view-mode">
        <el-radio-button value="class">按班级</el-radio-button>
        <el-radio-button value="teacher">按教师</el-radio-button>
      </el-radio-group>
      <el-select
        v-model="selected"
        size="small"
        class="view-select"
        :placeholder="mode === 'class' ? '选择班级' : '选择教师'"
        data-testid="timetable-view-select"
      >
        <el-option v-for="name in viewOptions" :key="name" :label="name" :value="name" />
      </el-select>
      <span class="hint">只读预览，一次显示一个{{ mode === 'class' ? '班级' : '教师' }}的课表</span>
    </div>

    <DraftGrid
      v-if="grid.periods.length"
      :periods="grid.periods"
      :lessons="grid.lessons"
      :courses="grid.courses"
      :draggable="false"
      :highlight-lesson-ids="grid.issueLessonIds"
      :conflict-lesson-ids="grid.conflictLessonIds"
      compact
    />
    <el-empty v-else description="预览里没有可显示的课表行" />

    <div class="legend">
      <span class="swatch conflict" /> 红框：同一{{ mode === 'class' ? '班级' : '教师' }}同一节次有多条记录
      <span class="swatch issue" /> 蓝框：该行有未匹配的名称，见下方映射步骤
    </div>
    <p v-if="grid.unplacedCount" class="unplaced">
      有 {{ grid.unplacedCount }} 行无法识别星期或节次，未画进网格，请在下方映射步骤修正。
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import DraftGrid from '@/components/draft/DraftGrid.vue'
import {
  buildTimetableGridFor,
  timetableClasses,
  timetableTeachers,
  type PreviewIssue,
  type PreviewRow,
  type TimetableSummary,
} from './timetablePreview'

const props = defineProps<{
  rows: PreviewRow[]
  issues?: PreviewIssue[]
  summary?: TimetableSummary | null
}>()

const mode = ref<'class' | 'teacher'>('class')
const selectedClass = ref('')
const selectedTeacher = ref('')

const classes = computed(() => timetableClasses(props.rows))
const teachers = computed(() => timetableTeachers(props.rows))
const viewOptions = computed(() => (mode.value === 'class' ? classes.value : teachers.value))

const selected = computed({
  get: () => (mode.value === 'class' ? selectedClass.value : selectedTeacher.value),
  set: (value: string) => {
    if (mode.value === 'class') {
      selectedClass.value = value
    } else {
      selectedTeacher.value = value
    }
  },
})

watch(
  viewOptions,
  (options) => {
    if (!options.includes(selected.value)) {
      selected.value = options[0] ?? ''
    }
  },
  { immediate: true },
)

watch(mode, () => {
  selected.value = viewOptions.value[0] ?? ''
})

const grid = computed(() =>
  buildTimetableGridFor(
    props.rows,
    mode.value === 'class'
      ? { className: selectedClass.value }
      : { teacherName: selectedTeacher.value },
    props.issues ?? [],
  ),
)
</script>

<style scoped>
.timetable-preview {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.view-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.view-select {
  width: 200px;
}

.legend {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  color: #6b7280;
  font-size: 12px;
}

.swatch {
  width: 14px;
  height: 14px;
  border-radius: 3px;
}

.swatch.conflict {
  border: 2px solid #eb5757;
}

.swatch.issue {
  border: 2px solid #409eff;
}

.unplaced {
  margin: 0;
  color: #b26a00;
  font-size: 12px;
}

.hint {
  color: #6b7280;
  font-size: 12px;
}
</style>
