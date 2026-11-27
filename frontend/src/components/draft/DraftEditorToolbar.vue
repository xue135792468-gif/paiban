<template>
  <div class="toolbar">
    <div class="identity">
      <el-button link @click="emit('back')">
        <el-icon><ArrowLeft /></el-icon>
        <span>返回排课</span>
      </el-button>
      <h2>{{ draftName ?? '课表草稿' }}</h2>
      <el-tag size="small" :type="published ? 'success' : 'info'" effect="light">
        {{ published ? '已发布' : '草稿' }}
      </el-tag>
      <InfoTooltip field="draft.editing" />
    </div>
    <div class="actions">
      <el-button :disabled="!canUndo || undoing || busy" @click="emit('undo')">
        <el-icon><RefreshLeft /></el-icon>
        <span>撤销{{ undoCount > 0 ? `（${undoCount}）` : '' }}</span>
      </el-button>
      <DraftMoreMenu
        :status="status"
        :can-edit="canEdit"
        :can-optimize="canOptimize"
        :locked-count="lockedCount"
        :lesson-count="lessonCount"
        :busy="busy"
        @optimize="emit('optimize')"
        @compare="emit('compare')"
        @rename="emit('rename')"
        @refresh="emit('refresh')"
        @remove="emit('remove')"
      />
      <el-button
        v-if="canPublish && !published"
        type="success"
        :disabled="!publishable || busy"
        @click="emit('publish')"
      >
        发布课表
      </el-button>
      <el-button
        v-if="canPublish && published"
        data-testid="withdraw-draft"
        type="warning"
        :disabled="busy"
        @click="emit('withdraw')"
      >
        撤回为草稿
      </el-button>
    </div>
    <el-alert
      v-if="cancelledSubstitutions > 0"
      class="reprocess"
      data-testid="reprocess-substitutions"
      type="warning"
      show-icon
      @close="emit('dismiss-reprocess')"
    >
      <template #title>
        有 {{ cancelledSubstitutions }} 条撤回时取消的调代需要重新处理
        <el-button
          link
          type="primary"
          size="small"
          data-testid="reprocess-substitutions-link"
          @click="openSubstitutionRecords"
        >
          前往调代记录
        </el-button>
      </template>
    </el-alert>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, RefreshLeft } from '@element-plus/icons-vue'
import InfoTooltip from '@/components/InfoTooltip.vue'
import DraftMoreMenu from './DraftMoreMenu.vue'

const props = withDefaults(
  defineProps<{
    draftName?: string | null
    status?: string | null
    undoCount: number
    canUndo: boolean
    undoing: boolean
    busy?: boolean
    canEdit: boolean
    canPublish: boolean
    canOptimize: boolean
    lockedCount: number
    lessonCount: number
    publishable: boolean
    /** Substitutions a withdrawal cancelled that still cover future dates, not re-done yet. */
    cancelledSubstitutions?: number
  }>(),
  { draftName: null, status: null, cancelledSubstitutions: 0 },
)

const emit = defineEmits<{
  (
    e:
      | 'back'
      | 'undo'
      | 'refresh'
      | 'compare'
      | 'rename'
      | 'publish'
      | 'withdraw'
      | 'remove'
      | 'optimize'
      | 'dismiss-reprocess',
  ): void
}>()

const router = useRouter()
const published = computed(() => props.status === 'PUBLISHED')

/** The records tab always asks the API for status=ALL, cancelled rows included. */
function openSubstitutionRecords() {
  router.push({ path: '/substitution/requests', query: { tab: 'records' } })
}
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
}
.identity,
.actions {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}
h2 {
  margin: 0;
  font-size: 20px;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.reprocess {
  flex-basis: 100%;
}
</style>
