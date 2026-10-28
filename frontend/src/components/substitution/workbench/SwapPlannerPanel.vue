<template>
  <el-card shadow="never" data-testid="swap-planner">
    <template #header>
      <div class="card-head">
        <span class="card-title">同班调课：{{ courseName }}（{{ lessonDate }}）→ 指定目标日期与课节</span>
        <span class="hint">目标槽必须是本班当天已有课的格子；系统求闭合置换环（最多 3 个候选）</span>
      </div>
    </template>

    <div class="form-row">
      <span class="label">目标日期</span>
      <span data-testid="swap-target-date">
        <el-date-picker
          v-model="targetDate"
          type="date"
          value-format="YYYY-MM-DD"
          :disabled-date="disableDate"
          placeholder="选择目标日期"
          style="width: 160px"
          @change="loadTargets"
        />
      </span>
      <span class="label">目标课节</span>
      <el-select
        v-model="targetPeriodId"
        placeholder="先选目标日期"
        style="width: 260px"
        :disabled="slots.length === 0"
        data-testid="swap-target-period"
      >
        <el-option
          v-for="slot in slots"
          :key="slot.periodId"
          :label="`第${slot.periodNo}节 · ${slot.courseName} · ${slot.teacherName}`"
          :value="slot.periodId"
        />
      </el-select>
      <el-input
        v-model="reason"
        placeholder="调课原因（可选）"
        style="width: 220px"
        data-testid="swap-reason"
      />
      <el-button
        type="primary"
        data-testid="generate-swap-plans"
        :loading="generating"
        :disabled="!targetPeriodId"
        @click="generate"
      >
        生成置换方案
      </el-button>
      <el-button text data-testid="swap-cancel" @click="emit('cancel')">取消</el-button>
    </div>

    <el-alert
      v-if="emptyDay"
      type="warning"
      :closable="false"
      show-icon
      class="notice"
      data-testid="swap-empty-day"
      title="本班当天没有课（空格）：空格只挪不补，请换一个目标日期。"
    />

    <el-alert
      v-if="plans.length === 0 && diagnoses.length > 0"
      type="error"
      :closable="false"
      show-icon
      class="notice"
      data-testid="swap-no-solution"
      title="没有可行的置换环（原课表未动）"
      :description="diagnoses.map((item) => item.message).join('；')"
    />

    <div v-if="plans.length > 0" class="candidates" data-testid="swap-candidates">
      <div class="panel-title">候选方案（{{ rankingLabel }}，共 {{ plans.length }} 个）</div>
      <div v-for="(plan, index) in plans" :key="plan.planId" class="plan-card" data-testid="swap-plan-card">
        <div class="plan-head">
          <span class="plan-name">方案 {{ index + 1 }}</span>
          <el-tag size="small">{{ plan.cycleLength }} 人环</el-tag>
          <el-tag size="small" type="info">影响 {{ plan.impactDays }} 天</el-tag>
          <el-tag size="small" type="info">{{ plan.impactTeachers }} 位教师</el-tag>
          <el-tag v-if="!plan.sameWeek" size="small" type="warning">跨周</el-tag>
          <el-button
            size="small"
            type="primary"
            plain
            :data-testid="`choose-swap-plan-${index}`"
            :loading="choosing"
            @click="choose(plan)"
          >
            查看整环对照
          </el-button>
        </div>
        <el-table :data="plan.moves" size="small" class="move-table">
          <el-table-column label="课程" min-width="90">
            <template #default="{ row }">{{ row.courseName }}</template>
          </el-table-column>
          <el-table-column label="教师" min-width="110">
            <template #default="{ row }">{{ row.teacherName }}</template>
          </el-table-column>
          <el-table-column label="调整前" min-width="150">
            <template #default="{ row }">{{ row.fromDate }}（{{ row.fromPeriodLabel }}）</template>
          </el-table-column>
          <el-table-column label="调整后" min-width="150">
            <template #default="{ row }">{{ row.toDate }}（{{ row.toPeriodLabel }}）</template>
          </el-table-column>
          <el-table-column prop="roomName" label="教室" min-width="90" />
        </el-table>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import type {
  PlanDiagnosis,
  SwapCandidate,
  SwapPreviewResponse,
  SwapPlansResponse,
  SwapTargetSlot,
  SwapTargetsResponse,
} from './types'
import type { Entry } from '../types'

const props = defineProps<{ entry: Entry; lessonDate: string }>()

const emit = defineEmits<{
  preview: [response: SwapPreviewResponse]
  cancel: []
}>()

const targetDate = ref('')
const slots = ref<SwapTargetSlot[]>([])
const targetPeriodId = ref<number | null>(null)
const reason = ref('')
const generating = ref(false)
const choosing = ref(false)
const plans = ref<SwapCandidate[]>([])
const diagnoses = ref<PlanDiagnosis[]>([])
const rankingLabel = ref('')

const courseName = computed(() => props.entry.courseName ?? '')
/** 窗口从来源日期向后展开：目标日期不能早于来源日。 */
const lessonTime = new Date(`${props.lessonDate}T00:00:00`).getTime()

const emptyDay = computed(
  () => targetDate.value !== '' && slots.value.length === 0 && !generating.value,
)

function disableDate(value: Date) {
  return value.getTime() < lessonTime
}

async function loadTargets() {
  targetPeriodId.value = null
  plans.value = []
  diagnoses.value = []
  if (!targetDate.value) {
    slots.value = []
    return
  }
  try {
    const { data } = await http.get<SwapTargetsResponse>('/substitution-workbench/swap-targets', {
      params: { entryId: props.entry.id, lessonDate: props.lessonDate, targetDate: targetDate.value },
    })
    slots.value = data.slots
  } catch (error) {
    slots.value = []
    ElMessage.error(serverMessage(error, '加载目标槽位失败'))
  }
}

async function generate() {
  if (!targetDate.value || !targetPeriodId.value) return
  generating.value = true
  plans.value = []
  diagnoses.value = []
  try {
    const { data } = await http.post<SwapPlansResponse>('/substitution-workbench/swap-plans', {
      entryId: props.entry.id,
      lessonDate: props.lessonDate,
      targetDate: targetDate.value,
      targetPeriodId: targetPeriodId.value,
      reason: reason.value || undefined,
    })
    plans.value = data.plans
    diagnoses.value = data.diagnoses
    rankingLabel.value = data.rankingPresetLabel
  } catch (error) {
    ElMessage.error(serverMessage(error, '生成方案失败'))
  } finally {
    generating.value = false
  }
}

async function choose(plan: SwapCandidate) {
  choosing.value = true
  try {
    const { data } = await http.post<SwapPreviewResponse>('/substitution-workbench/swap-previews', {
      entryId: props.entry.id,
      lessonDate: props.lessonDate,
      planId: plan.planId,
      reason: reason.value || undefined,
    })
    emit('preview', data)
  } catch (error) {
    ElMessage.error(serverMessage(error, '创建预览失败'))
  } finally {
    choosing.value = false
  }
}
</script>

<style scoped>
.card-head {
  align-items: baseline;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.card-title {
  font-weight: 600;
}
.hint {
  color: var(--paiban-muted);
  font-size: 12px;
}
.form-row {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.label {
  color: var(--paiban-muted);
  font-size: 13px;
}
.notice {
  margin-bottom: 8px;
  margin-top: 8px;
}
.candidates {
  margin-top: 12px;
}
.panel-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.plan-card {
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  margin-bottom: 8px;
  padding: 8px;
}
.plan-head {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 6px;
}
.plan-name {
  font-weight: 600;
}
.move-table {
  width: 100%;
}
</style>
