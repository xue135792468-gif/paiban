<template>
  <div v-if="draft" class="optimization-section">
    <div class="optimization-entry" data-testid="optimization-entry" />
    <OptimizationJobPanel
      v-if="job"
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

function openLaunch() {
  if (canStart.value) dialogVisible.value = true
}

defineExpose({ openLaunch, hasJob: () => job.value != null })

const canStart = computed(
  () => props.canOptimize && props.lessonCount > 0 && props.lockedCount < props.lessonCount,
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
    await ElMessageBox.confirm('确定要取消这次继续优化吗？原草稿不会被修改。', '取消优化任务', {
      confirmButtonText: '确定取消',
      cancelButtonText: '再等等',
      type: 'warning',
    })
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
