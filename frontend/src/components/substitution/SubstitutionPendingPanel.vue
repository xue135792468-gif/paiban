<template>
  <el-card shadow="never">
    <el-table v-if="requests.length > 0" :data="requests" stripe>
      <el-table-column label="类型" width="80">
        <template #default="{ row }">
          <el-tag :type="row.type === 'SWAP' ? 'warning' : 'info'" size="small">{{ typeLabel(row.type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="originalTeacherName" label="申请人" width="100" />
      <el-table-column prop="effectiveDate" label="日期" width="115" />
      <el-table-column label="节次" width="180">
        <template #default="{ row }">
          <span v-if="row.type === 'SWAP'">{{ row.periodLabel }} → {{ row.targetPeriodLabel }}</span>
          <span v-else>{{ row.periodLabel }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="className" label="行政班" min-width="110" />
      <el-table-column prop="courseName" label="课程" width="90" />
      <el-table-column label="期望代课" width="100">
        <template #default="{ row }">{{ row.type === 'COVER' ? (row.substituteTeacherName || '未指定') : '—' }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="140" show-overflow-tooltip />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button v-if="row.type === 'SWAP'" link type="primary" :loading="generatingId === row.id" @click="generate(row)">
            生成方案
          </el-button>
          <el-button v-else link type="primary" @click="openApprove(row)">批准</el-button>
          <el-button link type="danger" @click="reject(row)">驳回</el-button>
        </template>
      </el-table-column>
    </el-table>
    <EmptyStateGuide
      v-else
      title="没有待审批的申请"
      description="新的调代申请会出现在这里。调课会先生成候选方案，再由教务整单批准。"
      help-slug="substitution"
      help-anchor="approve"
    />
  </el-card>

  <el-dialog v-model="plansVisible" title="候选调课方案" width="780px">
    <div v-loading="loadingPlans">
      <el-alert
        v-if="presetLabel"
        type="info"
        :closable="false"
        show-icon
        class="preset-bar"
        :title="`当前排序：${presetLabel}。每个方案显示影响天数、影响教师数与软约束扣分。`"
      />
      <el-empty v-if="!loadingPlans && plans.length === 0" :description="diagnosisText || '没有可用的置换方案'" />
      <div v-for="plan in plans" :key="plan.id" class="plan-card">
        <div class="plan-head">
          <el-tag size="small" type="warning">{{ plan.rankingPresetLabel }}</el-tag>
          <span>环长 {{ plan.cycleLength }} · 影响 {{ plan.impactDays }} 天 · {{ plan.impactTeachers }} 位教师 · 软分 {{ plan.softPenalty }}</span>
          <span class="hint">有效期至 {{ formatTime(plan.expiresAt) }}</span>
        </div>
        <div v-for="move in plan.moves" :key="move.entryId" class="plan-move">
          <el-tag size="small" type="info">{{ move.teacherName }}</el-tag>
          {{ move.courseName }} {{ move.fromPeriodLabel }} → {{ move.toPeriodLabel }}
          <span class="hint">{{ move.toDate }}</span>
        </div>
        <div class="plan-actions">
          <el-button type="primary" :loading="approvingPlanId === plan.id" @click="approvePlan(plan)">整单批准</el-button>
          <span class="hint">一次性生效，不会出现半截状态</span>
        </div>
      </div>
    </div>
  </el-dialog>

  <el-dialog v-model="approveVisible" title="批准代课申请" width="600px" @closed="recommendations = []">
    <div class="ai-recommend-bar">
      <el-tooltip content="请先在设置中配置 AI 供应商" :disabled="aiRecommendConfigured" placement="top">
        <span>
          <el-button type="primary" plain :disabled="!aiRecommendConfigured" :loading="recommending" @click="requestRecommendations">
            AI 推荐代课人
          </el-button>
        </span>
      </el-tooltip>
      <span class="hint">AI 只提供建议，最终由你确认</span>
    </div>
    <div v-if="recommendations.length" class="recommend-list">
      <div
        v-for="item in recommendations"
        :key="item.teacherId"
        class="recommend-card"
        :class="{ selected: approveForm.substituteTeacherId === item.teacherId }"
      >
        <div class="recommend-head">
          <span class="recommend-name">{{ item.name }}</span>
          <el-tag v-if="item.score !== null && item.score !== undefined" type="success" size="small">评分 {{ item.score }}</el-tag>
        </div>
        <p class="recommend-reason">{{ item.reason }}</p>
        <el-button size="small" type="primary" plain @click="approveForm.substituteTeacherId = item.teacherId">选择</el-button>
      </div>
    </div>
    <el-form label-width="90px">
      <el-form-item label="代课教师" required>
        <el-select v-model="approveForm.substituteTeacherId" filterable style="width: 100%">
          <el-option
            v-for="teacher in otherTeachers"
            :key="teacher.id"
            :label="teacher.name"
            :value="teacher.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="审批意见">
        <el-input v-model="approveForm.comment" type="textarea" :rows="3" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="approveVisible = false">取消</el-button>
      <el-button type="primary" :loading="approving" @click="approveCover">确认批准</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import type {
  AiStatus,
  Diagnosis,
  Plan,
  Recommendation,
  RequestRow,
  Teacher,
} from './types'
import { formatTime, typeLabel } from './types'

const props = defineProps<{ requests: RequestRow[]; teachers: Teacher[] }>()
const emit = defineEmits<{ refresh: [] }>()

const plansVisible = ref(false)
const loadingPlans = ref(false)
const generatingId = ref(0)
const plans = ref<Plan[]>([])
const diagnoses = ref<Diagnosis[]>([])
const presetLabel = ref('')
const approvingPlanId = ref(0)

const approveVisible = ref(false)
const approving = ref(false)
const recommendations = ref<Recommendation[]>([])
const recommending = ref(false)
const aiRecommendConfigured = ref(false)
const approveForm = reactive({
  requestId: 0,
  originalTeacherId: 0,
  substituteTeacherId: null as number | null,
  comment: '',
})

const diagnosisText = computed(() =>
  diagnoses.value.map((item) => `${item.code}：${item.message}`).join('；'),
)
const otherTeachers = computed(() =>
  props.teachers.filter((teacher) => teacher.id !== approveForm.originalTeacherId),
)

async function generate(row: RequestRow) {
  generatingId.value = row.id
  plans.value = []
  diagnoses.value = []
  presetLabel.value = ''
  try {
    const { data } = await http.post(`/substitution-requests/${row.id}/plans`)
    plans.value = data.plans
    diagnoses.value = data.diagnoses
    presetLabel.value = data.rankingPresetLabel
    plansVisible.value = true
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '生成方案失败'))
  } finally {
    generatingId.value = 0
  }
}

async function approvePlan(plan: Plan) {
  approvingPlanId.value = plan.id
  try {
    await http.post(`/substitution-plans/${plan.id}/approve`, {})
    ElMessage.success('方案已整单批准并生效')
    plansVisible.value = false
    emit('refresh')
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '方案批准失败，请重新生成'))
  } finally {
    approvingPlanId.value = 0
  }
}

function openApprove(row: RequestRow) {
  approveForm.requestId = row.id
  approveForm.substituteTeacherId = row.substituteTeacherId ?? null
  approveForm.comment = ''
  recommendations.value = []
  const original = props.teachers.find((teacher) => teacher.name === row.originalTeacherName)
  approveForm.originalTeacherId = original ? original.id : 0
  approveVisible.value = true
}

async function approveCover() {
  if (!approveForm.substituteTeacherId) {
    ElMessage.warning('请选择代课教师')
    return
  }
  approving.value = true
  try {
    await http.post(`/substitution-requests/${approveForm.requestId}/approve`, {
      substituteTeacherId: approveForm.substituteTeacherId,
      comment: approveForm.comment,
    })
    ElMessage.success('已批准，代课已生效')
    approveVisible.value = false
    emit('refresh')
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '审批失败'))
  } finally {
    approving.value = false
  }
}

async function reject(row: RequestRow) {
  try {
    const { value } = await ElMessageBox.prompt('请填写驳回原因', '驳回申请', {
      inputType: 'textarea',
      inputValidator: (input) => Boolean(input && input.trim()),
    })
    await http.post(`/substitution-requests/${row.id}/reject`, { comment: value })
    ElMessage.success('已驳回')
    emit('refresh')
  } catch {
    // cancelled
  }
}

async function requestRecommendations() {
  if (!approveForm.requestId) return
  recommending.value = true
  try {
    const { data } = await http.post<{ recommendations?: Recommendation[] }>(
      `/substitution-requests/${approveForm.requestId}/recommend`,
    )
    recommendations.value = data.recommendations ?? []
    if (recommendations.value.length === 0) {
      ElMessage.warning('AI 没有返回可用的代课教师，请手动选择')
    }
  } catch (e: any) {
    ElMessage.error(serverMessage(e, 'AI 推荐失败，请稍后重试或手动选择代课教师'))
  } finally {
    recommending.value = false
  }
}

async function loadAiStatus() {
  try {
    const { data } = await http.get<AiStatus>('/ai/status')
    aiRecommendConfigured.value = data.tasks?.substitution_recommend?.configured === true
  } catch {
    aiRecommendConfigured.value = false
  }
}

onMounted(loadAiStatus)
</script>

<style scoped>
.hint {
  color: var(--paiban-muted);
  font-size: 12px;
  margin-left: 8px;
}
.preset-bar {
  margin-bottom: 12px;
}
.plan-card {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
}
.plan-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  flex-wrap: wrap;
}
.plan-move {
  font-size: 13px;
  margin-top: 8px;
}
.plan-actions {
  margin-top: 12px;
  display: flex;
  align-items: center;
}
.ai-recommend-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.recommend-list {
  display: grid;
  gap: 10px;
  margin-bottom: 16px;
}
.recommend-card {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 10px 12px;
}
.recommend-card.selected {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.recommend-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.recommend-name {
  font-weight: 600;
  font-size: 14px;
}
.recommend-reason {
  margin: 6px 0 8px;
  color: var(--paiban-muted);
  font-size: 13px;
  line-height: 1.5;
}
</style>
