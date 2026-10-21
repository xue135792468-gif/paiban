<template>
  <div v-loading="loading" class="candidate-panel" data-testid="swap-candidate-panel">
    <div v-if="lesson" class="source">
      <div class="source-title">{{ lesson.courseName }} · {{ lesson.teacherName }}</div>
      <div class="source-meta">
        {{ lesson.className }} · {{ lesson.periodLabel ?? '未排节次' }}
      </div>
    </div>

    <div class="toolbar">
      <el-radio-group v-model="groupMode" size="small">
        <el-radio-button value="ALL">全部</el-radio-button>
        <el-radio-button value="INTRA_CLASS">同班换位</el-radio-button>
        <el-radio-button value="CROSS_CLASS">跨班换人</el-radio-button>
      </el-radio-group>
      <el-switch
        :model-value="includeUnavailable"
        active-text="显示不可用"
        @update:model-value="(value: string | number | boolean) => emit('update:includeUnavailable', Boolean(value))"
      />
    </div>

    <div class="filters">
      <el-select v-model="filters.subject" clearable placeholder="科目" size="small">
        <el-option v-for="subject in options.subjects" :key="subject" :label="subject" :value="subject" />
      </el-select>
      <el-select v-model="filters.gradeId" clearable placeholder="年级" size="small">
        <el-option v-for="grade in options.grades" :key="grade.id" :label="grade.name" :value="grade.id" />
      </el-select>
      <el-select v-model="filters.classId" clearable filterable placeholder="班级" size="small">
        <el-option v-for="item in options.classes" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-select v-model="filters.teacherId" clearable filterable placeholder="教师" size="small">
        <el-option v-for="item in options.teachers" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-button link size="small" :disabled="!hasFilters" @click="resetFilters">重置</el-button>
    </div>

    <el-empty v-if="!loading && total === 0" :description="emptyText" />

    <template v-else>
      <section v-if="showIntra" class="group">
        <h4>同班换位 <span class="count">{{ groups.intraClass.length }}</span></h4>
        <p v-if="groups.intraClass.length === 0" class="group-empty">没有同班换位候选</p>
        <div
          v-for="candidate in intraVisible"
          :key="candidate.lessonId"
          class="candidate"
          :class="{ unavailable: !candidate.available }"
          @click="pick(candidate)"
        >
          <div class="candidate-head">
            <span class="course">{{ candidate.courseName }}</span>
            <span class="period">{{ candidate.periodLabel }}</span>
            <el-tag v-if="!candidate.available" size="small" type="danger" effect="plain">不可用</el-tag>
          </div>
          <div class="candidate-meta">
            {{ candidate.teacherName }} · {{ candidate.className }}
            <template v-if="candidate.gradeName"> · {{ candidate.gradeName }}</template>
            <template v-if="candidate.roomName"> · {{ candidate.roomName }}</template>
          </div>
          <ul v-if="candidate.conflicts.length > 0" class="reasons">
            <li v-for="(conflict, index) in candidate.conflicts" :key="index">{{ conflict.message }}</li>
          </ul>
          <ul v-if="candidate.warnings.length > 0" class="warnings">
            <li v-for="(warning, index) in candidate.warnings" :key="index">{{ warning }}</li>
          </ul>
        </div>
        <p v-if="groups.intraClass.length > intraVisible.length" class="more">
          仅显示前 {{ renderLimit }} 条，请用筛选缩小范围。
        </p>
      </section>

      <section v-if="showCross" class="group">
        <h4>跨班换人 <span class="count">{{ groups.crossClass.length }}</span></h4>
        <p v-if="groups.crossClass.length === 0" class="group-empty">没有跨班换人候选</p>
        <div
          v-for="candidate in crossVisible"
          :key="candidate.lessonId"
          class="candidate"
          :class="{ unavailable: !candidate.available }"
          @click="pick(candidate)"
        >
          <div class="candidate-head">
            <span class="course">{{ candidate.courseName }}</span>
            <span class="period">{{ candidate.periodLabel }}</span>
            <el-tag v-if="!candidate.available" size="small" type="danger" effect="plain">不可用</el-tag>
          </div>
          <div class="candidate-meta">
            {{ candidate.teacherName }} · {{ candidate.className }}
            <template v-if="candidate.gradeName"> · {{ candidate.gradeName }}</template>
            <template v-if="candidate.roomName"> · {{ candidate.roomName }}</template>
          </div>
          <ul v-if="candidate.conflicts.length > 0" class="reasons">
            <li v-for="(conflict, index) in candidate.conflicts" :key="index">{{ conflict.message }}</li>
          </ul>
          <ul v-if="candidate.warnings.length > 0" class="warnings">
            <li v-for="(warning, index) in candidate.warnings" :key="index">{{ warning }}</li>
          </ul>
        </div>
        <p v-if="groups.crossClass.length > crossVisible.length" class="more">
          仅显示前 {{ renderLimit }} 条，请用筛选缩小范围。
        </p>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { candidateFilterOptions, groupCandidates, type CandidateFilters } from './swapCandidates'
import type { DraftLesson, SwapCandidate, SwapCandidatesResponse } from './types'

type GroupMode = 'ALL' | 'INTRA_CLASS' | 'CROSS_CLASS'

const props = defineProps<{
  lesson: DraftLesson | null
  response: SwapCandidatesResponse | null
  loading: boolean
  includeUnavailable: boolean
  /** The drop-on-occupied-cell flow opens straight into the same-class group. */
  initialGroup?: GroupMode
}>()

const emit = defineEmits<{
  (e: 'update:includeUnavailable', value: boolean): void
  (e: 'select', candidate: SwapCandidate): void
}>()

const renderLimit = 150
const emptyResponse: SwapCandidatesResponse = { lessonId: 0, intraClass: [], crossClass: [] }
const groupMode = ref<GroupMode>(props.initialGroup ?? 'ALL')
const filters = ref<CandidateFilters>({
  subject: null,
  gradeId: null,
  classId: null,
  teacherId: null,
})

const allCandidates = computed(() => [
  ...(props.response?.intraClass ?? []),
  ...(props.response?.crossClass ?? []),
])
const options = computed(() => candidateFilterOptions(allCandidates.value))
const groups = computed(() => groupCandidates(props.response ?? emptyResponse, filters.value))
const total = computed(() => groups.value.intraClass.length + groups.value.crossClass.length)
const showIntra = computed(() => groupMode.value !== 'CROSS_CLASS')
const showCross = computed(() => groupMode.value !== 'INTRA_CLASS')
const intraVisible = computed(() => groups.value.intraClass.slice(0, renderLimit))
const crossVisible = computed(() => groups.value.crossClass.slice(0, renderLimit))
const hasFilters = computed(
  () =>
    filters.value.subject != null ||
    filters.value.gradeId != null ||
    filters.value.classId != null ||
    filters.value.teacherId != null,
)
const emptyText = computed(() => {
  if (hasFilters.value) return '没有符合筛选条件的候选'
  if (!props.includeUnavailable) return '没有可用的换课候选，可打开「显示不可用」查看原因'
  return '没有换课候选'
})

function resetFilters() {
  filters.value = { subject: null, gradeId: null, classId: null, teacherId: null }
}

function pick(candidate: SwapCandidate) {
  if (!candidate.available) return
  emit('select', candidate)
}

watch(
  () => props.lesson?.id,
  () => {
    resetFilters()
    groupMode.value = props.initialGroup ?? 'ALL'
  },
)
</script>

<style scoped>
.candidate-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 200px;
}
.source {
  background: #f7f9ff;
  border: 1px solid #e6ecff;
  border-radius: 8px;
  padding: 10px 12px;
}
.source-title {
  font-weight: 600;
}
.source-meta {
  color: var(--paiban-muted);
  font-size: 12px;
  margin-top: 3px;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.filters {
  display: flex;
  align-items: center;
  gap: 8px;
}
.filters .el-select {
  width: 116px;
}
.group h4 {
  margin: 6px 0;
  font-size: 14px;
}
.count {
  color: var(--paiban-muted);
  font-weight: 400;
  font-size: 12px;
}
.group-empty {
  color: var(--paiban-muted);
  font-size: 12px;
  margin: 4px 0 8px;
}
.candidate {
  border: 1px solid #eef0f5;
  border-radius: 8px;
  padding: 8px 10px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: border-color 0.12s ease;
}
.candidate:hover {
  border-color: #409eff;
}
.candidate.unavailable {
  cursor: not-allowed;
  background: #fbfbfc;
  opacity: 0.85;
}
.candidate.unavailable:hover {
  border-color: #eef0f5;
}
.candidate-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.course {
  font-weight: 600;
}
.period {
  color: #4b5563;
}
.candidate-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--paiban-muted);
}
.reasons,
.warnings {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.6;
}
.reasons {
  color: #b43b3b;
}
.warnings {
  color: #b7791f;
}
.more {
  color: var(--paiban-muted);
  font-size: 12px;
  margin: 2px 0 8px;
}
</style>
