<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-head">
        <span class="card-title">待审批申请</span>
        <el-tag v-if="pending.length" size="small" type="warning">{{ pending.length }} 条待处理</el-tag>
      </div>
    </template>
    <EmptyStateGuide
      v-if="pending.length === 0"
      title="没有待审批的申请"
      description="老师提交代课申请后会出现在这里。批准前后系统都会按当前课表再检查一次。"
      help-slug="substitution"
      help-anchor="approval"
    />
    <el-table v-else :data="pending" stripe>
      <el-table-column label="日期" width="120">
        <template #default="{ row }">{{ row.effectiveDate }}</template>
      </el-table-column>
      <el-table-column prop="periodLabel" label="节次" width="140" />
      <el-table-column prop="className" label="行政班" min-width="110" />
      <el-table-column prop="courseName" label="课程" width="90" />
      <el-table-column prop="originalTeacherName" label="申请人" width="100" />
      <el-table-column label="期望代课" width="110">
        <template #default="{ row }">{{ row.expectedSubstituteTeacherName || row.substituteTeacherName || '未指定' }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="150" show-overflow-tooltip />
      <el-table-column v-if="canApprove" label="操作" width="150">
        <template #default="{ row }">
          <el-button :data-testid="`approve-${row.id}`" link type="primary" @click="openApprove(row)">批准</el-button>
          <el-button :data-testid="`reject-${row.id}`" link type="danger" @click="openReject(row)">驳回</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="approveVisible" title="批准代课申请" width="480px">
      <p v-if="active" class="dialog-summary">
        {{ active.effectiveDate }} {{ active.periodLabel }} · {{ active.className }} · {{ active.courseName }}
        （{{ active.originalTeacherName }} 申请，期望 {{ active.expectedSubstituteTeacherName || '未指定' }}）
      </p>
      <el-form label-width="90px">
        <el-form-item label="代课教师">
          <el-select v-model="approveTeacherId" data-testid="approval-teacher" style="width: 240px">
            <el-option v-for="teacher in teachers" :key="teacher.id" :label="teacher.name" :value="teacher.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="审批意见">
          <el-input
            v-model="approveComment"
            data-testid="approve-comment"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="可不填"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="approveVisible = false">取消</el-button>
        <el-button data-testid="approve-confirm" type="primary" :loading="submitting" @click="confirmApprove">
          确认批准
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rejectVisible" title="驳回代课申请" width="480px">
      <p v-if="active" class="dialog-summary">
        {{ active.effectiveDate }} {{ active.periodLabel }} · {{ active.className }} · {{ active.courseName }}
      </p>
      <el-form label-width="90px">
        <el-form-item label="驳回理由">
          <el-input
            v-model="rejectReason"
            data-testid="reject-reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="请填写驳回理由，老师会看到这句话"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button data-testid="reject-confirm" type="danger" :loading="submitting" @click="confirmReject">
          确认驳回
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import type { RequestRow, Teacher } from './types'

const props = defineProps<{
  requests: RequestRow[]
  teachers: Teacher[]
  /** 没有 substitution:approve 的账号既不显示也不调用管理动作。 */
  canApprove: boolean
}>()
const emit = defineEmits<{ changed: [] }>()

/** 只看待审批：即便上层多给了别的状态，也不给它们管理动作。 */
const pending = computed(() => props.requests.filter((row) => row.status === 'SUBMITTED'))

const approveVisible = ref(false)
const rejectVisible = ref(false)
const submitting = ref(false)
const active = ref<RequestRow | null>(null)
const approveTeacherId = ref<number | null>(null)
const approveComment = ref('')
const rejectReason = ref('')

function openApprove(row: RequestRow) {
  if (!props.canApprove) return
  active.value = row
  approveTeacherId.value = row.expectedSubstituteTeacherId ?? row.substituteTeacherId ?? null
  approveComment.value = ''
  approveVisible.value = true
}

function openReject(row: RequestRow) {
  if (!props.canApprove) return
  active.value = row
  rejectReason.value = ''
  rejectVisible.value = true
}

async function confirmApprove() {
  if (!props.canApprove || !active.value) return
  const payload: Record<string, unknown> = {}
  if (approveTeacherId.value) {
    payload.substituteTeacherId = approveTeacherId.value
  }
  const comment = approveComment.value.trim()
  if (comment) {
    payload.comment = comment
  }
  submitting.value = true
  try {
    await http.post(`/substitution-requests/${active.value.id}/approve`, payload)
    ElMessage.success('已批准，代课已生效')
    approveVisible.value = false
    emit('changed')
  } catch (error) {
    ElMessage.error(serverMessage(error, '批准失败，请按当前课表核对后重试'))
  } finally {
    submitting.value = false
  }
}

async function confirmReject() {
  if (!props.canApprove || !active.value) return
  const reason = rejectReason.value.trim()
  if (!reason) {
    ElMessage.warning('请填写驳回理由')
    return
  }
  submitting.value = true
  try {
    await http.post(`/substitution-requests/${active.value.id}/reject`, { reason })
    ElMessage.success('已驳回')
    rejectVisible.value = false
    emit('changed')
  } catch (error) {
    ElMessage.error(serverMessage(error, '驳回失败'))
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-title {
  font-weight: 600;
}
.dialog-summary {
  margin: 0 0 12px;
  color: var(--paiban-muted);
  font-size: 13px;
}
</style>
