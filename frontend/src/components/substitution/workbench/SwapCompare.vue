<template>
  <el-card shadow="never" data-testid="swap-compare">
    <template #header>
      <div class="card-head">
        <span class="card-title">
          整环调整前后对照（{{ preview.className }} · {{ preview.lessonDate }} →
          {{ preview.targetDate }}）
        </span>
        <span class="hint">
          左 = 本次调整前最新安排，右 = 整环方案后；凭证 {{ preview.voucherId }} 于
          {{ formatTime(preview.expiresAt) }} 过期，开课截止 {{ formatTime(preview.deadlineAt) }}
        </span>
      </div>
    </template>

    <el-alert
      v-if="preview.issues.length > 0"
      type="warning"
      :closable="false"
      show-icon
      class="notice"
      data-testid="swap-preview-issues"
      title="校验提示（硬冲突以确认时服务端裁决为准）"
      :description="preview.issues.join('；')"
    />

    <div class="view-switch">
      <span class="label">视角</span>
      <el-radio-group v-model="view" data-testid="swap-view-switch" @change="onViewChange">
        <el-radio-button value="class">班级</el-radio-button>
        <el-radio-button value="teacher">教师</el-radio-button>
      </el-radio-group>
      <template v-if="view === 'teacher'">
        <el-select v-model="teacherId" style="width: 180px" data-testid="swap-teacher-select">
          <el-option
            v-for="(name, index) in preview.affectedTeacherNames"
            :key="preview.affectedTeacherIds[index]"
            :label="name"
            :value="preview.affectedTeacherIds[index]"
          />
        </el-select>
        <el-select v-model="compareDate" style="width: 150px" data-testid="swap-compare-date">
          <el-option v-for="day in preview.days" :key="day.date" :label="day.date" :value="day.date" />
        </el-select>
      </template>
      <span v-if="view === 'teacher'" class="hint">切换教师或日期后仍指向同一方案的投影。</span>
    </div>

    <!-- 班级视角：受影响日期逐日左右对照（同一方案投影，日期范围两侧联动）。 -->
    <div v-if="view === 'class'" class="days">
      <div v-for="day in preview.days" :key="day.date" class="day-block">
        <div class="day-title">{{ day.date }}（{{ weekdayName(day.date) }}）</div>
        <div class="compare">
          <div class="panel">
            <div class="panel-title before">调整前（最新）</div>
            <el-table :data="day.left" size="small" :data-testid="`swap-left-${day.date}`">
              <el-table-column prop="periodLabel" label="节次" width="96" />
              <el-table-column min-width="90" label="课程">
                <template #default="{ row }">
                  <span :class="{ changed: row.changed }">{{ row.courseName }}</span>
                </template>
              </el-table-column>
              <el-table-column min-width="100" label="教师">
                <template #default="{ row }">
                  <span :class="{ changed: row.changed }">{{ row.teacherName }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="roomName" label="教室" min-width="90" />
            </el-table>
          </div>
          <div class="panel">
            <div class="panel-title after">调整后（方案）</div>
            <el-table :data="day.right" size="small" :data-testid="`swap-right-${day.date}`">
              <el-table-column prop="periodLabel" label="节次" width="96" />
              <el-table-column min-width="90" label="课程">
                <template #default="{ row }">
                  <span :class="{ changed: row.changed }">{{ row.courseName }}</span>
                </template>
              </el-table-column>
              <el-table-column min-width="100" label="教师">
                <template #default="{ row }">
                  <span :class="{ changed: row.changed }">{{ row.teacherName }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="roomName" label="教室" min-width="90" />
            </el-table>
          </div>
        </div>
      </div>
    </div>

    <!-- 教师视角：所选教师与日期的单日左右对照。 -->
    <div v-else class="day-block">
      <div class="day-title">{{ teacherName }} · {{ compareDate }}</div>
      <div v-loading="compareLoading" class="compare">
        <div class="panel">
          <div class="panel-title before">调整前（最新）</div>
          <el-table :data="singleLeft" size="small" data-testid="swap-teacher-left">
            <el-table-column prop="className" label="班级" min-width="100" />
            <el-table-column prop="periodLabel" label="节次" width="96" />
            <el-table-column prop="courseName" label="课程" min-width="90" />
            <el-table-column prop="roomName" label="教室" min-width="90" />
          </el-table>
        </div>
        <div class="panel">
          <div class="panel-title after">调整后（方案）</div>
          <el-table :data="singleRight" size="small" data-testid="swap-teacher-right">
            <el-table-column prop="className" label="班级" min-width="100" />
            <el-table-column prop="periodLabel" label="节次" width="96" />
            <el-table-column min-width="90" label="课程">
              <template #default="{ row }">
                <span :class="{ changed: row.changed }">{{ row.courseName }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="roomName" label="教室" min-width="90" />
          </el-table>
        </div>
      </div>
    </div>

    <div class="changes">
      <div class="panel-title">完整变化清单（环内每一步：课程 / 班级 / 前后实际日期与节次 / 教师 / 教室）</div>
      <el-table :data="preview.changes" size="small" data-testid="swap-change-list">
        <el-table-column prop="className" label="班级" min-width="100" />
        <el-table-column prop="courseName" label="课程" min-width="90" />
        <el-table-column label="调整前" min-width="150">
          <template #default="{ row }">{{ row.fromDate }}（{{ row.fromLabel }}）</template>
        </el-table-column>
        <el-table-column label="调整后" min-width="150">
          <template #default="{ row }">{{ row.toDate }}（{{ row.toLabel }}）</template>
        </el-table-column>
        <el-table-column prop="teacherName" label="教师" min-width="100" />
        <el-table-column prop="roomName" label="教室" min-width="90" />
      </el-table>
    </div>

    <el-collapse class="baseline">
      <el-collapse-item name="baseline">
        <template #title>
          只读基线参考：<span class="baseline-name">学期初原始</span>
          （与上方「本次调整前」区分，基线不作为差异起点）
        </template>
        <el-table v-loading="baselineLoading" :data="baseline" size="small" data-testid="swap-baseline-grid">
          <el-table-column prop="periodLabel" label="节次" width="88" />
          <el-table-column prop="courseName" label="课程" min-width="90" />
          <el-table-column prop="teacherName" label="教师" min-width="100" />
          <el-table-column prop="roomName" label="教室" min-width="90" />
        </el-table>
      </el-collapse-item>
    </el-collapse>

    <div class="actions">
      <span>
        <el-input
          v-model="comment"
          placeholder="备注（可选，随申请或批准记录）"
          style="width: 260px"
          data-testid="swap-consume-comment"
        />
      </span>
      <el-button data-testid="swap-submit-approval" :loading="busy" @click="emit('submitApproval', comment)">
        提交审批
      </el-button>
      <el-button
        v-if="canConfirm"
        type="primary"
        data-testid="swap-personal-confirm"
        :loading="busy"
        @click="emit('confirm', comment)"
      >
        亲自确认生效
      </el-button>
      <el-button text data-testid="swap-cancel-preview" @click="emit('cancel')">取消预览</el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { http } from '@/api/http'
import type { GridCell, SwapPreviewResponse } from './types'

const props = defineProps<{ preview: SwapPreviewResponse; canConfirm: boolean; busy: boolean }>()

const emit = defineEmits<{
  submitApproval: [comment: string]
  confirm: [comment: string]
  cancel: []
}>()

const view = ref<'class' | 'teacher'>('class')
const teacherId = ref<number | null>(props.preview.affectedTeacherIds[0] ?? null)
const compareDate = ref(props.preview.days[0]?.date ?? props.preview.lessonDate)
const singleLeft = ref<GridCell[]>([])
const singleRight = ref<GridCell[]>([])
const compareLoading = ref(false)
const comment = ref('')
const baseline = ref<Array<Record<string, unknown>>>([])
const baselineLoading = ref(false)

const teacherName = computed(() => {
  const index = props.preview.affectedTeacherIds.indexOf(teacherId.value ?? -1)
  return index >= 0 ? props.preview.affectedTeacherNames[index] : ''
})

/** 切换教师或日期后重新投影（同一方案，教师视角单日对照）。 */
watch([teacherId, compareDate], () => {
  if (view.value === 'teacher') {
    loadTeacherCompare()
  }
})

function onViewChange() {
  if (view.value === 'teacher') {
    loadTeacherCompare()
  }
}

async function loadTeacherCompare() {
  if (!teacherId.value || !compareDate.value) return
  compareLoading.value = true
  try {
    const { data } = await http.get(
      `/substitution-workbench/previews/${props.preview.voucherId}/swap-compare`,
      { params: { view: 'teacher', teacherId: teacherId.value, date: compareDate.value } },
    )
    singleLeft.value = data.left
    singleRight.value = data.right
  } catch {
    singleLeft.value = []
    singleRight.value = []
  } finally {
    compareLoading.value = false
  }
}

onMounted(async () => {
  // 只读基线参考：学期初原始（view=baseline），不做差异起点。
  baselineLoading.value = true
  try {
    const { data } = await http.get(`/timetable/class/${props.preview.classId}`, {
      params: { view: 'baseline', date: props.preview.lessonDate },
    })
    baseline.value = data
  } catch {
    baseline.value = []
  } finally {
    baselineLoading.value = false
  }
})

function weekdayName(date: string) {
  const names = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  const value = new Date(`${date}T00:00:00`)
  return Number.isNaN(value.getTime()) ? '' : names[value.getDay()]
}

function formatTime(value: string) {
  return value ? value.replace('T', ' ').slice(0, 16) : ''
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
.notice {
  margin-bottom: 8px;
}
.view-switch {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}
.label {
  color: var(--paiban-muted);
  font-size: 13px;
}
.day-block {
  margin-bottom: 12px;
}
.day-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.compare {
  display: flex;
  gap: 12px;
}
.panel {
  flex: 1;
  min-width: 0;
}
.panel-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.panel-title.before {
  color: var(--paiban-muted);
}
.panel-title.after {
  color: var(--el-color-primary);
}
.changed {
  color: var(--el-color-primary);
  font-weight: 600;
}
.changes {
  margin-top: 12px;
}
.baseline {
  margin-top: 12px;
}
.baseline-name {
  font-weight: 600;
}
.actions {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
@media (max-width: 900px) {
  .compare {
    flex-direction: column;
  }
}
</style>
