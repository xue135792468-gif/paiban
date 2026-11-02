<template>
  <el-dialog
    :model-value="modelValue"
    title="并排比较草稿"
    width="1120px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div class="compare-grid">
      <div class="compare-side">
        <p v-if="pairedMode" class="side-label">原稿（比较 A）</p>
        <el-select v-model="compareA" placeholder="选择草稿 A" style="width: 100%">
          <el-option v-for="item in allDrafts" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <div v-if="draftSummary(compareA)" class="compare-score">
          {{ draftSummary(compareA) }}
        </div>
        <el-table :data="compareLessonsA" size="small" max-height="420" border>
          <el-table-column prop="periodLabel" label="课节" width="96" />
          <el-table-column prop="courseName" label="课程" width="96" />
          <el-table-column prop="teacherName" label="教师" width="96" />
          <el-table-column label="锁" width="56">
            <template #default="{ row }">
              <el-tag v-if="row.locked" size="small" type="warning" effect="light">锁</el-tag>
              <span v-else class="no-lock">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="compare-side">
        <p v-if="pairedMode" class="side-label">新稿（比较 B）</p>
        <el-select v-model="compareB" placeholder="选择草稿 B" style="width: 100%">
          <el-option v-for="item in allDrafts" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <div v-if="draftSummary(compareB)" class="compare-score">
          {{ draftSummary(compareB) }}
        </div>
        <el-table :data="compareLessonsB" size="small" max-height="420" border>
          <el-table-column prop="periodLabel" label="课节" width="96" />
          <el-table-column prop="courseName" label="课程" width="96" />
          <el-table-column prop="teacherName" label="教师" width="96" />
          <el-table-column label="锁" width="56">
            <template #default="{ row }">
              <el-tag v-if="row.locked" size="small" type="warning" effect="light">锁</el-tag>
              <span v-else class="no-lock">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div v-if="diffReady" class="compare-diff" data-testid="compare-diff">
      <p v-for="(line, index) in diffLines" :key="index" class="diff-line">
        {{ line }}
      </p>
      <el-alert
        v-if="diff.lockedChanges > 0"
        data-testid="compare-locked-warning"
        type="error"
        :closable="false"
        show-icon
        :title="`锁定课节发生变化（${diff.lockedChanges} 节），请检查数据`"
      />
      <p v-if="diff.changedLessons > 0" class="diff-meta">
        涉及 {{ diff.affectedTeachers.length }} 位教师、{{ diff.affectedDays.length }} 天。
      </p>
    </div>

    <p class="compare-hint">发布以整草稿校验为准；求解得分仅作参考。</p>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import { compareDrafts, summaryLines } from './compareDiff'
import type { Draft, DraftLesson } from './types'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    draftId: number
    semesterId?: number
    /** Compare A: the draft the optimization started from (ticket 08). */
    sourceDraftId?: number | null
    /** Compare B: the newly generated draft; usually the current draft. */
    compareWithDraftId?: number | null
  }>(),
  {
    semesterId: undefined,
    sourceDraftId: null,
    compareWithDraftId: null,
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const allDrafts = ref<Draft[]>([])
const compareA = ref<number | null>(null)
const compareB = ref<number | null>(null)
const compareLessonsA = ref<DraftLesson[]>([])
const compareLessonsB = ref<DraftLesson[]>([])
const loadedA = ref(false)
const loadedB = ref(false)

const pairedMode = computed(() => props.sourceDraftId != null && props.compareWithDraftId != null)
const diffReady = computed(
  () => loadedA.value && loadedB.value && compareA.value != null && compareB.value != null,
)
const diff = computed(() => compareDrafts(compareLessonsA.value, compareLessonsB.value))
const diffLines = computed(() => summaryLines(diff.value))

function draftSummary(id: number | null) {
  const draft = allDrafts.value.find((item) => item.id === id)
  if (!draft) return ''
  return `求解得分：${draft.scoreHard} hard / ${draft.scoreSoft} soft · ${draft.lessonCount} 节`
}

async function loadCompareSide(side: 'A' | 'B') {
  const id = side === 'A' ? compareA.value : compareB.value
  const target = side === 'A' ? compareLessonsA : compareLessonsB
  const loaded = side === 'A' ? loadedA : loadedB
  loaded.value = false
  if (!id) {
    target.value = []
    loaded.value = true
    return
  }
  try {
    const { data } = await http.get(`/timetable-drafts/${id}`)
    target.value = [...(data.lessons as DraftLesson[])].sort((a, b) => {
      const dayA = a.dayOfWeek ?? 9
      const dayB = b.dayOfWeek ?? 9
      return dayA !== dayB ? dayA - dayB : (a.periodNo ?? 99) - (b.periodNo ?? 99)
    })
  } catch (error) {
    target.value = []
    ElMessage.error(serverMessage(error, '加载比较草稿失败'))
  } finally {
    loaded.value = true
  }
}

watch([compareA, compareB], () => {
  void loadCompareSide('A')
  void loadCompareSide('B')
})

/**
 * `immediate` matters for the `?compare=` entry: the dialog can be mounted with
 * `modelValue` already true, and the first open still has to load both sides.
 */
watch(
  () => props.modelValue,
  async (visible) => {
    if (!visible) return
    const { data } = await http.get('/timetable-drafts', { params: { semesterId: props.semesterId } })
    allDrafts.value = data
    if (pairedMode.value) {
      // Ticket 08: the entry names both sides explicitly, so no "first other draft" guess.
      compareA.value = props.sourceDraftId
      compareB.value = props.compareWithDraftId
    } else {
      compareA.value = props.draftId
      const other = (data as Draft[]).find((item) => item.id !== props.draftId)
      compareB.value = other ? other.id : props.draftId
    }
    await Promise.all([loadCompareSide('A'), loadCompareSide('B')])
  },
  { immediate: true },
)
</script>

<style scoped>
.compare-hint {
  margin: 12px 0 0;
  color: var(--paiban-muted);
  font-size: 12px;
}
.compare-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.compare-side {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.compare-score {
  font-size: 12px;
  color: var(--paiban-muted);
}
.side-label {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}
.no-lock {
  color: var(--paiban-muted);
}
.compare-diff {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.diff-line,
.diff-meta {
  margin: 0;
  font-size: 13px;
}
.diff-meta {
  color: var(--paiban-muted);
  font-size: 12px;
}
</style>
