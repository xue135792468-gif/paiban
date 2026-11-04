<template>
  <div class="toolbar">
    <div>
      <el-button link @click="emit('back')">
        <el-icon><ArrowLeft /></el-icon>
        <span>返回排课</span>
      </el-button>
      <h2>
        {{ draftName ?? '课表草稿' }}
        <el-tag size="small" :type="published ? 'success' : 'info'" effect="light">
          {{ published ? '已发布' : '草稿' }}
        </el-tag>
      </h2>
      <p class="subtitle">
        同班拖动=直接移动或换位；跨班换人点上方“跨班换人”。同班交换失败会自动列出联动方案，选中并应用后整单生效、一次撤销。刷新页面会清空本次会话的撤销记录。
      </p>
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
    <div class="actions">
      <el-button :disabled="!canUndo || undoing || busy" @click="emit('undo')">
        <el-icon><RefreshLeft /></el-icon>
        <span>撤销{{ undoCount > 0 ? `（${undoCount}）` : '' }}</span>
      </el-button>
      <el-button :disabled="busy" @click="emit('refresh')">刷新</el-button>
      <el-button v-if="!isJunior" @click="emit('compare')">比较草稿</el-button>
      <el-button v-if="canEdit" @click="emit('rename')">重命名</el-button>
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
        plain
        :disabled="busy"
        @click="emit('withdraw')"
      >
        撤回为草稿
      </el-button>
      <el-button v-if="canEdit" type="danger" plain @click="emit('remove')">删除</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, RefreshLeft } from '@element-plus/icons-vue'

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
    publishable: boolean
    isJunior: boolean
    /** Substitutions a withdrawal cancelled that still cover future dates, not re-done yet. */
    cancelledSubstitutions?: number
  }>(),
  { draftName: null, status: null, cancelledSubstitutions: 0 },
)

const emit = defineEmits<{
  (e: 'back'): void
  (e: 'undo'): void
  (e: 'refresh'): void
  (e: 'compare'): void
  (e: 'rename'): void
  (e: 'publish'): void
  (e: 'withdraw'): void
  (e: 'remove'): void
  (e: 'dismiss-reprocess'): void
}>()

const router = useRouter()

const published = computed(() => props.status === 'PUBLISHED')

/** The records tab always asks the API for {@code status=ALL}, cancelled rows included. */
function openSubstitutionRecords() {
  router.push({ path: '/substitution/requests', query: { tab: 'records' } })
}
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}
h2 {
  margin: 4px 0;
  font-size: 20px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.subtitle {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 13px;
  max-width: 760px;
}
.reprocess {
  margin-top: 10px;
  max-width: 760px;
}
.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
</style>
