<template>
  <el-dialog
    :model-value="modelValue"
    title="按行政班补固定教室"
    width="720px"
    @update:model-value="onVisible"
  >
    <div v-loading="loading" class="room-fill" data-testid="room-fill-dialog">
      <p class="hint">
        将为下面每个缺教室的行政班准备一间固定普通教室，只填还没有教室的课节；已人工分配的教室不会被改动。
      </p>

      <el-table
        v-if="classes.length"
        :data="classes"
        size="small"
        border
        data-testid="room-fill-classes"
      >
        <el-table-column prop="className" label="行政班" min-width="120" />
        <el-table-column prop="roomName" label="固定教室" min-width="220" />
        <el-table-column label="方式" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.action === 'CREATE' ? 'primary' : 'info'" effect="plain">
              {{ row.action === 'CREATE' ? '新建' : '复用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="人数依据" width="150">
          <template #default="{ row }">
            {{ row.studentCount }} 人 · {{ row.requiredCapacity }} 座
          </template>
        </el-table-column>
        <el-table-column prop="lessonCount" label="涉及课节" width="90" />
      </el-table>

      <el-alert
        v-if="manualItems.length"
        data-testid="room-fill-manual"
        type="error"
        show-icon
        :closable="false"
        title="以下班级需要人工处理，本次不会创建教室，也不会改动任何课节"
      >
        <ul class="list">
          <li v-for="item in manualItems" :key="item.code + item.message">{{ item.message }}</li>
        </ul>
      </el-alert>

      <p v-if="!classes.length && !manualItems.length" class="hint" data-testid="room-fill-empty">
        没有缺教室的课节。
      </p>

      <el-alert
        v-if="blockers.length"
        data-testid="room-fill-remaining"
        type="warning"
        show-icon
        :closable="false"
        title="补齐后仍会阻止发布"
      >
        <ul class="list">
          <li v-for="blocker in blockers" :key="blocker.code">{{ blocker.message }}</li>
        </ul>
      </el-alert>
    </div>

    <template #footer>
      <el-button @click="onVisible(false)">取消</el-button>
      <el-button
        type="primary"
        data-testid="room-fill-confirm"
        :disabled="!applicable"
        :loading="applying"
        @click="apply"
      >
        确认补教室
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import type { RoomFillPlan } from './roomFill'

const props = defineProps<{ modelValue: boolean; draftId: number }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void; (e: 'applied'): void }>()

const loading = ref(false)
const applying = ref(false)
const plan = ref<RoomFillPlan | null>(null)

const classes = computed(() => plan.value?.classes ?? [])
const manualItems = computed(() => plan.value?.manualItems ?? [])
const blockers = computed(() => plan.value?.blockers ?? [])
/** Nothing may be confirmed while a class still needs a person, or the plan is blocked. */
const applicable = computed(
  () => plan.value?.applicable === true && !loading.value && !applying.value,
)

async function load() {
  loading.value = true
  try {
    const { data } = await http.get<RoomFillPlan>(`/timetable-drafts/${props.draftId}/room-fill/plan`)
    plan.value = data
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '加载补教室计划失败'))
  } finally {
    loading.value = false
  }
}

async function apply() {
  applying.value = true
  try {
    const { data } = await http.post<RoomFillPlan>(`/timetable-drafts/${props.draftId}/room-fill`)
    ElMessage.success(
      `已新建 ${data.createdRooms} 间教室，复用 ${data.reusedRooms} 间，为 ${data.assignedLessons} 节课分配了固定教室`,
    )
    plan.value = data
    emit('applied')
    emit('update:modelValue', false)
  } catch (e: any) {
    // A refused plan answers 409 with the same shape, so its reasons can be shown here.
    if (e?.response?.data?.manualItems) {
      plan.value = e.response.data as RoomFillPlan
    }
    ElMessage.error(serverMessage(e, '补教室失败'))
  } finally {
    applying.value = false
  }
}

function onVisible(value: boolean) {
  emit('update:modelValue', value)
}

// The preview must never be stale: it is recomputed every time the dialog opens.
watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      void load()
    }
  },
  { immediate: true },
)
</script>

<style scoped>
.room-fill {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 60px;
}
.hint {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 13px;
}
.list {
  margin: 4px 0 0;
  padding-left: 18px;
  line-height: 1.8;
}
</style>
