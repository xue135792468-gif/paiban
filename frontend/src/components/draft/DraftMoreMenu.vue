<template>
  <el-dropdown trigger="click" @command="onCommand">
    <el-button :disabled="busy" data-testid="draft-more-menu">
      更多 <el-icon class="el-icon--right"><ArrowDown /></el-icon>
    </el-button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-if="status === 'DRAFT'"
          command="optimize"
          :disabled="!canOptimize || lessonCount === 0 || allLocked"
          :title="
            allLocked
              ? '全部课节已锁定'
              : lessonCount === 0
                ? '草稿还没有课节'
                : !canOptimize
                  ? '需要排课执行权限'
                  : `继承锁定 ${lockedCount} 节`
          "
          data-testid="optimization-entry"
        >
          <span class="menu-label" :data-testid="allLocked ? 'optimization-all-locked' : undefined">
            <span data-testid="optimization-continue">继续优化</span>
            <small>继承锁定 {{ lockedCount }} 节<template v-if="allLocked"> · 全部课节已锁定</template></small>
          </span>
        </el-dropdown-item>
        <el-dropdown-item command="compare" data-testid="draft-more-compare">比较草稿</el-dropdown-item>
        <el-dropdown-item v-if="canEdit" command="rename" data-testid="draft-more-rename">重命名</el-dropdown-item>
        <el-dropdown-item command="refresh" data-testid="draft-more-refresh">刷新</el-dropdown-item>
        <el-dropdown-item v-if="canEdit && status === 'DRAFT'" command="remove" divided data-testid="draft-more-remove">
          <span class="danger">删除草稿</span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'

const props = withDefaults(
  defineProps<{
    status?: string | null
    canEdit: boolean
    canOptimize: boolean
    lockedCount: number
    lessonCount: number
    busy?: boolean
  }>(),
  { status: null, busy: false },
)

const emit = defineEmits<{
  (event: 'optimize' | 'compare' | 'rename' | 'refresh' | 'remove'): void
}>()

const allLocked = computed(() => props.lessonCount > 0 && props.lockedCount >= props.lessonCount)

function onCommand(command: string | number | object) {
  if (typeof command === 'string') {
    emit(command as 'optimize' | 'compare' | 'rename' | 'refresh' | 'remove')
  }
}
</script>

<style scoped>
.menu-label {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.menu-label small {
  color: var(--paiban-muted);
  font-size: 12px;
}
.danger {
  color: var(--el-color-danger);
}
</style>
