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
        <span>代课</span>
        <span class="hint">原教师请假，由另一位教师接替；提交后由教务审批</span>
      </el-form-item>
      <el-form-item label="请假日期">
        <el-date-picker
          v-model="form.date"
          type="date"
          value-format="YYYY-MM-DD"
          :disabled-date="disabledDate"
          @change="onDateChange"
        />
        <span class="hint">只会列出当天有课的课节</span>
      </el-form-item>
      <el-form-item label="我的课节">
        <el-select v-model="form.entryId" placeholder="选择课节" style="width: 340px" :loading="loadingEntries">
          <el-option
            v-for="entry in entries"
            :key="entry.id"
            :label="`第${entry.periodNo}节 · ${entry.courseName} · ${entry.className}`"
            :value="entry.id"
          />
        </el-select>
        <span v-if="entries.length === 0" class="hint">当天没有可调代的课</span>
      </el-form-item>
      <el-form-item label="期望代课">
        <el-select
          v-model="form.expectedSubstituteTeacherId"
          data-testid="expected-substitute"
          filterable
          clearable
          placeholder="请选择代课教师"
          style="width: 260px"
        >
          <el-option v-for="teacher in otherTeachers" :key="teacher.id" :label="teacher.name" :value="teacher.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="请假原因">
        <el-input v-model="form.reason" type="textarea" :rows="3" placeholder="如：家中有事，需要请假半天" />
        <span class="hint">想调课请写明原因，由教务处理</span>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="submitting" @click="submit">提交申请</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import type { Entry, Teacher } from './types'
import { isPastDate, today, weekdayOf } from './types'
import { isoOf } from './workbench/swapTargetRules'

const props = defineProps<{ myTeacherId: number | null; teachers: Teacher[] }>()
const emit = defineEmits<{ submitted: [] }>()

function disabledDate(d: Date): boolean {
  return isoOf(d) < today()
}

function onDateChange(value: string | null) {
  if (value && isPastDate(value)) {
    ElMessage.warning('只能选择今天及以后的日期')
    form.date = today()
  }
  void loadEntries()
}

const form = reactive({
  date: today(),
  entryId: null as number | null,
  expectedSubstituteTeacherId: null as number | null,
  reason: '',
})
const entries = ref<Entry[]>([])
const loadingEntries = ref(false)
const submitting = ref(false)

const otherTeachers = computed(() => props.teachers.filter((teacher) => teacher.id !== props.myTeacherId))

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
  }
}

async function submit() {
  if (!form.entryId) {
    ElMessage.warning('请选择课节')
    return
  }
  if (!form.expectedSubstituteTeacherId) {
    ElMessage.warning('请选择代课教师')
    return
  }
  submitting.value = true
  try {
    const payload: Record<string, unknown> = {
      entryId: form.entryId,
      effectiveDate: form.date,
      reason: form.reason,
      type: 'COVER',
      expectedSubstituteTeacherId: form.expectedSubstituteTeacherId,
    }
    await http.post('/substitution-requests', payload)
    ElMessage.success('申请已提交，等待教务审批')
    form.entryId = null
    form.reason = ''
    form.expectedSubstituteTeacherId = null
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
