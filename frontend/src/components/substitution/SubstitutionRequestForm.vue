<template>
  <el-card shadow="never" class="submit-card">
    <template #header><span class="card-title">提交调代申请</span></template>
    <el-alert
      v-if="!myTeacherId"
      title="当前账号未关联教师，无法提交调代申请。请联系教务管理员在用户管理中关联教师。"
      type="warning"
      :closable="false"
      show-icon
    />
    <el-form v-else label-width="100px" class="form">
      <el-form-item label="类型">
        <el-radio-group v-model="form.type" @change="onTypeChange">
          <el-radio-button value="COVER">代课</el-radio-button>
          <el-radio-button value="SWAP">调课</el-radio-button>
        </el-radio-group>
        <span class="hint">{{ form.type === 'SWAP' ? '与同班其他课节置换，需教务整单批准' : '原教师请假，由另一位教师接替' }}</span>
      </el-form-item>
      <el-form-item :label="form.type === 'SWAP' ? '调课日期' : '请假日期'">
        <el-date-picker v-model="form.date" type="date" value-format="YYYY-MM-DD" @change="loadEntries" />
        <span class="hint">只会列出当天有课的课节</span>
      </el-form-item>
      <el-form-item label="我的课节">
        <el-select v-model="form.entryId" placeholder="选择课节" style="width: 340px" :loading="loadingEntries" @change="loadTargets">
          <el-option
            v-for="entry in entries"
            :key="entry.id"
            :label="`第${entry.periodNo}节 · ${entry.courseName} · ${entry.className}`"
            :value="entry.id"
          />
        </el-select>
        <span v-if="entries.length === 0" class="hint">当天没有可调代的课</span>
      </el-form-item>
      <el-form-item v-if="form.type === 'SWAP'" label="换到">
        <el-select v-model="form.targetPeriodId" placeholder="选择同班目标节次" style="width: 340px" :loading="loadingTargets">
          <el-option
            v-for="target in targetOptions"
            :key="target.periodId"
            :label="`第${target.periodNo}节 · ${target.courseName} · ${target.teacherName}`"
            :value="target.periodId"
          />
        </el-select>
        <span class="hint">系统会生成 2–6 位教师的置换环</span>
      </el-form-item>
      <el-form-item v-if="form.type === 'COVER'" label="期望代课">
        <el-select v-model="form.expectedSubstituteTeacherId" filterable clearable placeholder="可不指定" style="width: 260px">
          <el-option v-for="teacher in otherTeachers" :key="teacher.id" :label="teacher.name" :value="teacher.id" />
        </el-select>
      </el-form-item>
      <el-form-item :label="form.type === 'SWAP' ? '调课原因' : '请假原因'">
        <el-input v-model="form.reason" type="textarea" :rows="3" :placeholder="form.type === 'SWAP' ? '如：周三第 3 节想换到第 2 节' : '如：家中有事，需要请假半天'" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="submitting" @click="submit">提交申请</el-button>
        <span v-if="form.type === 'SWAP'" class="hint">调课必须经教务审批，不能直接生效</span>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import type { Entry, SwapTarget, Teacher } from './types'
import { today, weekdayOf } from './types'

const props = defineProps<{ myTeacherId: number | null; teachers: Teacher[] }>()
const emit = defineEmits<{ submitted: [] }>()

const form = reactive({
  type: 'COVER' as 'COVER' | 'SWAP',
  date: today(),
  entryId: null as number | null,
  targetPeriodId: null as number | null,
  expectedSubstituteTeacherId: null as number | null,
  reason: '',
})
const entries = ref<Entry[]>([])
const targetOptions = ref<SwapTarget[]>([])
const loadingEntries = ref(false)
const loadingTargets = ref(false)
const submitting = ref(false)

const otherTeachers = computed(() => props.teachers.filter((teacher) => teacher.id !== props.myTeacherId))
const selectedEntry = computed(() => entries.value.find((entry) => entry.id === form.entryId) ?? null)

async function loadEntries() {
  if (!props.myTeacherId || !form.date) return
  loadingEntries.value = true
  try {
    const { data } = await http.get(`/timetable/teacher/${props.myTeacherId}`, {
      params: { view: 'baseline', date: form.date },
    })
    const weekday = weekdayOf(form.date)
    entries.value = (data as Entry[]).filter((entry) => entry.dayOfWeek === weekday)
  } catch {
    entries.value = []
  } finally {
    loadingEntries.value = false
    form.entryId = null
    form.targetPeriodId = null
    targetOptions.value = []
  }
}

async function loadTargets() {
  form.targetPeriodId = null
  targetOptions.value = []
  const entry = selectedEntry.value
  if (!entry || form.type !== 'SWAP') return
  loadingTargets.value = true
  try {
    const { data } = await http.get('/substitution-swap-targets', {
      params: { entryId: entry.id, date: form.date },
    })
    targetOptions.value = data as SwapTarget[]
  } catch {
    targetOptions.value = []
  } finally {
    loadingTargets.value = false
  }
}

function onTypeChange() {
  form.targetPeriodId = null
  form.expectedSubstituteTeacherId = null
  if (form.type === 'SWAP' && form.entryId) {
    loadTargets()
  }
}

async function submit() {
  if (!form.entryId) {
    ElMessage.warning('请选择课节')
    return
  }
  if (form.type === 'SWAP' && !form.targetPeriodId) {
    ElMessage.warning('请选择要换到的目标节次')
    return
  }
  submitting.value = true
  try {
    const payload: Record<string, unknown> = {
      entryId: form.entryId,
      effectiveDate: form.date,
      reason: form.reason,
      type: form.type,
    }
    if (form.type === 'SWAP') {
      payload.targetPeriodId = form.targetPeriodId
    } else if (form.expectedSubstituteTeacherId) {
      payload.expectedSubstituteTeacherId = form.expectedSubstituteTeacherId
    }
    const { data } = await http.post('/substitution-requests', payload)
    ElMessage.success(
      data.status === 'EFFECTIVE'
        ? '调代已直接生效'
        : form.type === 'SWAP'
          ? '调课申请已提交，等待教务生成方案'
          : '申请已提交，等待教务审批',
    )
    form.entryId = null
    form.targetPeriodId = null
    form.reason = ''
    form.expectedSubstituteTeacherId = null
    targetOptions.value = []
    emit('submitted')
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '提交失败'))
  } finally {
    submitting.value = false
  }
}

onMounted(loadEntries)
</script>

<style scoped>
.submit-card {
  margin-bottom: 16px;
}
.form {
  max-width: 720px;
}
.card-title {
  font-weight: 600;
}
.hint {
  color: var(--paiban-muted);
  font-size: 12px;
  margin-left: 10px;
}
</style>
