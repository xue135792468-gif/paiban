<template>
  <el-card shadow="never" data-testid="preview-compare">
    <template #header>
      <div class="card-head">
        <span class="card-title">调整前后对照（{{ preview.className }} · {{ preview.lessonDate }}）</span>
        <span class="hint">
          左 = 本次调整前最新安排，右 = 方案后；凭证 {{ preview.voucherId }} 于
          {{ formatTime(preview.expiresAt) }} 过期，开课截止 {{ formatTime(preview.deadlineAt) }}
        </span>
      </div>
    </template>

    <el-alert
      v-if="preview.revisionKind"
      type="info"
      :closable="false"
      show-icon
      class="notice"
      data-testid="revision-banner"
      :title="revisionBanner"
    />
    <el-alert
      v-if="preview.issues.length > 0"
      type="warning"
      :closable="false"
      show-icon
      class="notice"
      data-testid="preview-issues"
      title="校验提示（硬冲突以确认时服务端裁决为准）"
      :description="preview.issues.join('；')"
    />
    <el-alert
      v-if="!preview.sameSubject"
      type="warning"
      :closable="false"
      show-icon
      class="notice"
      data-testid="cross-subject-warning"
      title="代课教师与课程学科不同（跨科代课），请确认可以接受。"
    />

    <div class="compare" data-testid="compare-panels">
      <div class="panel">
        <div class="panel-title before">调整前（最新）</div>
        <el-table :data="preview.left" size="small" data-testid="left-grid">
          <el-table-column prop="periodLabel" label="节次" width="88" />
          <el-table-column prop="courseName" label="课程" min-width="90" />
          <el-table-column prop="teacherName" label="教师" min-width="100" />
          <el-table-column prop="roomName" label="教室" min-width="90" />
        </el-table>
      </div>
      <div class="panel">
        <div class="panel-title after">调整后（方案）</div>
        <el-table :data="preview.right" size="small" data-testid="right-grid">
          <el-table-column prop="periodLabel" label="节次" width="88" />
          <el-table-column min-width="90" label="课程">
            <template #default="{ row }">
              <span :class="{ changed: row.changed }">{{ row.courseName }}</span>
            </template>
          </el-table-column>
          <el-table-column min-width="110" label="教师">
            <template #default="{ row }">
              <span :class="{ changed: row.changed }">
                {{ row.teacherName }}
                <el-tag v-if="row.changed" size="small" type="success">新教师</el-tag>
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="roomName" label="教室" min-width="90" />
        </el-table>
      </div>
    </div>

    <div class="changes">
      <div class="panel-title list">完整变化清单（受影响教师与日期）</div>
      <el-table :data="preview.changes" size="small" data-testid="change-list">
        <el-table-column prop="className" label="班级" min-width="100" />
        <el-table-column prop="courseName" label="课程" min-width="90" />
        <el-table-column prop="lessonDate" label="授课日期" min-width="100" />
        <el-table-column prop="periodLabelBefore" label="节次" min-width="88" />
        <el-table-column label="教师变化" min-width="180">
          <template #default="{ row }">
            <span class="leave">{{ row.teacherNameBefore }}</span>
            <span class="arrow"> → </span>
            <span class="take-over">{{ row.teacherNameAfter }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="roomNameBefore" label="教室" min-width="90" />
      </el-table>
    </div>

    <el-collapse class="baseline">
      <el-collapse-item name="baseline">
        <template #title>
          只读基线参考：<span class="baseline-name">学期初原始</span>
          （与上方「本次调整前」区分，基线不作为差异起点）
        </template>
        <el-table v-loading="baselineLoading" :data="baseline" size="small" data-testid="baseline-grid">
          <el-table-column prop="periodLabel" label="节次" width="88" />
          <el-table-column prop="courseName" label="课程" min-width="90" />
          <el-table-column prop="teacherName" label="教师" min-width="100" />
          <el-table-column prop="roomName" label="教室" min-width="90" />
        </el-table>
      </el-collapse-item>
    </el-collapse>

    <div class="actions">
      <span class="reason">
        <el-input
          v-model="comment"
          placeholder="备注（可选，随申请或批准记录）"
          style="width: 260px"
          data-testid="consume-comment"
        />
      </span>
      <el-button data-testid="submit-approval" :loading="busy" @click="emit('submitApproval', comment)">
        提交审批
      </el-button>
      <el-button
        v-if="canConfirm"
        type="primary"
        data-testid="personal-confirm"
        :loading="busy"
        @click="emit('confirm', comment)"
      >
        亲自确认生效
      </el-button>
      <el-button text data-testid="cancel-preview" @click="emit('cancel')">取消预览</el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { http } from '@/api/http'
import type { PreviewResponse } from './types'

const props = defineProps<{ preview: PreviewResponse; canConfirm: boolean; busy: boolean }>()

const emit = defineEmits<{
  submitApproval: [comment: string]
  confirm: [comment: string]
  cancel: []
}>()

/** 修订预览（票 04）：说明本次再次调整的类型、被取代的申请与「生效前旧安排继续执行」。 */
const revisionBanner = computed(() => {
  const kind = props.preview.revisionKind === 'RESTORE_BASELINE' ? '恢复基线原任课教师' : '更换代课人'
  const base = props.preview.revisesRequestId ? `，取代申请 #${props.preview.revisesRequestId}` : ''
  return `课前再次调整（${kind}${base}）：新修订生效前，当前安排继续执行；生效后原子切换，历史保留。`
})

const comment = ref('')
const baseline = ref<Array<Record<string, unknown>>>([])
const baselineLoading = ref(false)

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
.leave {
  color: var(--el-color-danger);
  text-decoration: line-through;
}
.take-over {
  color: var(--el-color-success);
  font-weight: 600;
}
.arrow {
  color: var(--paiban-muted);
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
