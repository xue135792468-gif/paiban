<template>
  <div v-if="draft" class="optimization-section">
    <el-card v-if="!job" shadow="never" class="optimization-card" data-testid="optimization-entry">
      <div class="opt-head">
        <div>
          <strong>基于当前草稿继续优化</strong>
          <el-tag size="small" type="info" effect="light">
            继承锁定 {{ lockedCount }} 节
          </el-tag>
        </div>
        <el-button
          type="primary"
          data-testid="optimization-continue"
          :disabled="!canStart"
          @click="dialogVisible = true"
        >
          继续优化
        </el-button>
      </div>
      <p class="opt-desc">
        以当前草稿及锁定为初始解，生成另一份草稿；原稿保留，锁定会继承。任务完成不等于可发布，
        发布仍以新草稿的整稿校验为准。
      </p>
      <el-alert
        v-if="allLocked"
        data-testid="optimization-all-locked"
        type="warning"
        :closable="false"
        show-icon
        title="全部课节已锁定，没有可优化的课程"
      />
      <p v-else-if="lessonCount === 0" class="opt-desc">草稿还没有课节，无法继续优化。</p>
      <p v-else-if="!canOptimize" class="opt-desc">需要排课执行权限才能发起优化。</p>
    </el-card>

    <OptimizationJobPanel
      v-else
      :job="job"
      :source-draft-id="sourceDraftId"
      :source-draft-name="draft.name"
      :cancelling="cancelling"
      @cancel="confirmCancel"
      @open-draft="openDraft"
      @compare="openCompare"
      @retry="dialogVisible = true"
    />

    <OptimizationLaunchDialog
      v-model="dialogVisible"
      :source-draft-id="sourceDraftId"
      :source-draft-name="draft.name"
      :locked-count="lockedCount"
      :lesson-count="lessonCount"
      :starting="starting"
      :submit-error="error"
      @submit="launch"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import OptimizationJobPanel from './OptimizationJobPanel.vue'
import OptimizationLaunchDialog from './OptimizationLaunchDialog.vue'
import { useOptimizationJob, isOptimizationActive } from '@/composables/useOptimizationJob'
import type { Draft } from './types'

const props = withDefaults(
  defineProps<{
    draft: Draft | null
    sourceDraftId: number
    semesterId?: number
    canOptimize: boolean
    lockedCount: number
    lessonCount: number
  }>(),
  {
    semesterId: undefined,
  },
)

const route = useRoute()
const router = useRouter()

const { job, starting, cancelling, error, start, resume, cancel } = useOptimizationJob({
  notify: ElMessage,
})

const dialogVisible = ref(false)

const allLocked = computed(() => props.lessonCount > 0 && props.lockedCount >= props.lessonCount)
const canStart = computed(
  () => props.canOptimize && props.lessonCount > 0 && !allLocked.value,
)

// `?job=` is the only way a reload restores the panel; it resumes polling and never
// submits a second task.
watch(
  () => route.query.job,
  (value) => {
    const id = Number(value)
    if (value == null || !Number.isFinite(id) || id <= 0) return
    if (id === job.value?.id) return
    void resume(id)
  },
  { immediate: true },
)

async function launch(payload: { draftName: string; maxSeconds: number }) {
  if (!props.semesterId) {
    ElMessage.error('尚未加载学期信息，无法发起优化')
    return
  }
  const created = await start({
    semesterId: props.semesterId,
    sourceDraftId: props.sourceDraftId,
    draftName: payload.draftName,
    maxSeconds: payload.maxSeconds,
  })
  if (!created) return
  dialogVisible.value = false
  // The query keeps the job visible across a refresh without ever restarting it.
  await router.replace({ query: { ...route.query, job: String(created.id) } })
}

function openDraft(draftId: number) {
  void router.push(`/admin/drafts/${draftId}`)
}

function openCompare(draftId: number) {
  void router.push(`/admin/drafts/${draftId}?compare=${props.sourceDraftId}`)
}

async function confirmCancel() {
  if (!isOptimizationActive(job.value) || cancelling.value) return
  try {
    await ElMessageBox.confirm(
      '确定要取消这次继续优化吗？原草稿不会被修改。',
      '取消优化任务',
      { confirmButtonText: '确定取消', cancelButtonText: '再等等', type: 'warning' },
    )
  } catch {
    return
  }
  await cancel()
}
</script>

<style scoped>
.optimization-section {
  display: flex;
  flex-direction: column;
}
.optimization-card {
  border: none;
  background: #f7f9fc;
}
.opt-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.opt-head strong {
  margin-right: 8px;
}
.opt-desc,
.opt-hint {
  margin: 8px 0 0;
  color: var(--paiban-muted);
  font-size: 12px;
}
</style>
