<template>
  <el-dialog
    :model-value="modelValue"
    title="并排比较草稿"
    width="1120px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div class="compare-grid">
      <div class="compare-side">
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
          <el-table-column prop="roomName" label="教室" width="96" />
        </el-table>
      </div>
      <div class="compare-side">
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
          <el-table-column prop="roomName" label="教室" width="96" />
        </el-table>
      </div>
    </div>
    <p class="compare-hint">得分越高越好（硬分必须为 0 才能发布）。</p>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { http } from '@/api/http'
import type { Draft, DraftLesson } from '@/components/draft/types'

const props = defineProps<{
  modelValue: boolean
  draftId: number
  semesterId?: number
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const allDrafts = ref<Draft[]>([])
const compareA = ref<number | null>(null)
const compareB = ref<number | null>(null)
const compareLessonsA = ref<DraftLesson[]>([])
const compareLessonsB = ref<DraftLesson[]>([])

function draftSummary(id: number | null) {
  const draft = allDrafts.value.find((item) => item.id === id)
  if (!draft) return ''
  return `${draft.scoreHard} hard / ${draft.scoreSoft} soft · ${draft.lessonCount} 节`
}

async function loadCompareSide(side: 'A' | 'B') {
  const id = side === 'A' ? compareA.value : compareB.value
  const target = side === 'A' ? compareLessonsA : compareLessonsB
  if (!id) {
    target.value = []
    return
  }
  try {
    const { data } = await http.get(`/timetable-drafts/${id}`)
    target.value = [...(data.lessons as DraftLesson[])].sort((a, b) => {
      const dayA = a.dayOfWeek ?? 9
      const dayB = b.dayOfWeek ?? 9
      return dayA !== dayB ? dayA - dayB : (a.periodNo ?? 99) - (b.periodNo ?? 99)
    })
  } catch {
    target.value = []
  }
}

watch([compareA, compareB], () => {
  void loadCompareSide('A')
  void loadCompareSide('B')
})

watch(
  () => props.modelValue,
  async (visible) => {
    if (!visible) return
    const { data } = await http.get('/timetable-drafts', { params: { semesterId: props.semesterId } })
    allDrafts.value = data
    compareA.value = props.draftId
    const other = (data as Draft[]).find((item) => item.id !== props.draftId)
    compareB.value = other ? other.id : props.draftId
    await Promise.all([loadCompareSide('A'), loadCompareSide('B')])
  },
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
</style>
