<template>
  <el-card
    v-if="plan && plan.items.length"
    v-loading="loading"
    shadow="never"
    data-testid="missing-periods"
  >
    <template #header>
      <div class="card-head">
        <span class="card-title">作息缺少的节次</span>
        <el-tag size="small" type="warning" effect="plain">
          {{ plan.items.length }} 项
        </el-tag>
        <el-tag v-if="proposed.length" size="small" type="success" effect="plain">
          {{ proposed.length }} 项可自动补
        </el-tag>
      </div>
    </template>

    <el-alert
      v-if="plan.published"
      data-testid="missing-periods-published"
      type="warning"
      show-icon
      :closable="false"
      title="该学期已发布课表，作息不可补充"
      class="notice"
    />
    <p class="hint">
      文件里用到了这些节次，但学期「{{ plan.semesterName }}」的作息还没有。可推算项勾选后一次补齐；
      标黄的项没有可靠参照，需要到作息管理里人工设置。
    </p>

    <el-table :data="plan.items" size="small" border data-testid="missing-periods-table">
      <el-table-column v-if="!plan.published" label="选择" width="60">
        <template #default="{ row }">
          <el-checkbox
            v-if="row.status === 'PROPOSED'"
            :model-value="selected.includes(keyOf(row))"
            :data-testid="`missing-period-check-${keyOf(row)}`"
            @change="toggle(row, $event as boolean)"
          />
        </template>
      </el-table-column>
      <el-table-column label="星期" width="80">
        <template #default="{ row }">{{ dayLabel(row.dayOfWeek) }}</template>
      </el-table-column>
      <el-table-column prop="label" label="节次" width="90" />
      <el-table-column label="时段" width="90">
        <template #default="{ row }">{{ sectionLabel(row.section) }}</template>
      </el-table-column>
      <el-table-column label="起止时间" min-width="140">
        <template #default="{ row }">{{ timeRange(row) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag
            size="small"
            effect="plain"
            :type="row.status === 'PROPOSED' ? 'success' : 'warning'"
          >
            {{ row.status === 'PROPOSED' ? '可自动补' : '需人工' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="说明" min-width="240">
        <template #default="{ row }">
          <span :class="{ manual: row.status === 'MANUAL' }">
            {{ row.reason || '按相邻课节的时长与间隔推算' }}
          </span>
          <el-button
            v-if="row.status === 'MANUAL' && !plan.published"
            link
            type="primary"
            data-testid="missing-periods-manage"
            @click="goSemesters"
          >
            去作息管理
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="!plan.published && proposed.length" class="actions">
      <el-button
        type="primary"
        :loading="applying"
        :disabled="!selected.length"
        data-testid="missing-periods-apply"
        @click="apply"
      >
        补齐选中的 {{ selected.length }} 节
      </el-button>
      <span class="hint">只追加缺失的课节，已有课节的 id、时间与标签保持不变。</span>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import {
  applyItem,
  dayLabel,
  describeItem,
  keyOf,
  proposedItems,
  sectionLabel,
  timeRange,
  type ApplyMissingPeriodsResult,
  type MissingPeriodItem,
  type MissingPeriodsPlan,
} from './missingPeriods'

const props = defineProps<{ sessionId: number }>()
const emit = defineEmits<{ (e: 'applied'): void }>()

const router = useRouter()
const plan = ref<MissingPeriodsPlan | null>(null)
const loading = ref(false)
const applying = ref(false)
const selected = ref<string[]>([])

const proposed = computed(() => proposedItems(plan.value))
const selectedItems = computed(() =>
  proposed.value.filter((item) => selected.value.includes(keyOf(item))),
)

async function load() {
  if (!props.sessionId) return
  loading.value = true
  try {
    const { data } = await http.get<MissingPeriodsPlan>(
      `/ai/import/sessions/${props.sessionId}/missing-periods`,
    )
    // An older server or a failed read leaves the panel out of the way rather than
    // showing a list that was never computed.
    if (data && Array.isArray(data.items)) {
      setPlan(data)
    } else {
      plan.value = null
    }
  } catch {
    plan.value = null
  } finally {
    loading.value = false
  }
}

/** New plan from the server: everything still proposed is ticked again. */
function setPlan(next: MissingPeriodsPlan) {
  plan.value = next
  selected.value = proposedItems(next).map(keyOf)
}

function toggle(item: MissingPeriodItem, checked: boolean) {
  const key = keyOf(item)
  if (checked) {
    if (!selected.value.includes(key)) selected.value = [...selected.value, key]
  } else {
    selected.value = selected.value.filter((value) => value !== key)
  }
}

function goSemesters() {
  router.push('/admin/semesters')
}

async function apply() {
  const current = plan.value
  if (!current || !selectedItems.value.length) return
  const items = selectedItems.value.map(applyItem).filter((item) => item !== null)
  if (!items.length) return
  try {
    await ElMessageBox.confirm(
      `将向学期「${current.semesterName}」追加以下 ${items.length} 节课时：\n${selectedItems.value
        .map(describeItem)
        .join('\n')}`,
      '补齐作息缺少的节次',
      { type: 'warning', confirmButtonText: '确认补节', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  applying.value = true
  try {
    const { data } = await http.post<ApplyMissingPeriodsResult>(
      `/ai/import/sessions/${props.sessionId}/missing-periods`,
      { semesterId: current.semesterId, items },
    )
    setPlan(data.plan)
    ElMessage.success(`已补充 ${data.created} 节课时`)
    // The page re-reads the preview so anything derived from the period table moves too.
    emit('applied')
  } catch (error) {
    // A stale plan is refused with 409; re-reading it shows what changed.
    ElMessage.error(serverMessage(error, '补节课时失败，请稍后重试'))
    await load()
  } finally {
    applying.value = false
  }
}

onMounted(load)
watch(() => props.sessionId, load)
</script>

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.card-title {
  font-weight: 600;
}

.notice {
  margin-bottom: 8px;
}

.hint {
  color: #6b7280;
  font-size: 12px;
}

.manual {
  color: #b26a00;
}

.actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
</style>
