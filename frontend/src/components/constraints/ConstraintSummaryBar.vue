<template>
  <header class="summary-bar" data-testid="constraint-summary">
    <div class="bar-stats">
      <span class="bar-stat" data-testid="constraint-summary-enabled">已启用 <b>{{ counts.enabledTypes }}</b> / {{ counts.totalTypes }} 类</span>
      <span class="bar-stat" data-testid="constraint-summary-hard">硬约束 <b>{{ counts.hardTypes }}</b> 类</span>
      <span class="bar-stat accent" data-testid="constraint-summary-entries">具体条目 <b>{{ counts.entries }}</b> 条</span>
    </div>
    <div class="bar-actions">
      <span v-if="dirty" class="unsaved" data-testid="constraint-unsaved">有未保存的修改</span>
      <el-button
        v-if="canManage"
        data-testid="constraint-save"
        type="primary"
        :loading="saving"
        @click="emit('save')"
      >
        保存配置
      </el-button>
      <span v-else class="read-only" data-testid="constraint-read-only">只读：没有约束管理权限</span>
    </div>
  </header>
</template>

<script setup lang="ts">
import { ElButton } from 'element-plus'
import type { ConstraintCounts } from './types'

defineProps<{
  counts: ConstraintCounts
  dirty: boolean
  canManage: boolean
  saving: boolean
}>()

const emit = defineEmits<{ save: [] }>()
</script>

<style scoped>
.summary-bar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 10px 16px;
  margin-bottom: 14px;
  border-radius: var(--paiban-radius, 8px);
  background: #fff;
  border: 1px solid #ebeef5;
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.05);
}

.bar-stats {
  display: flex;
  align-items: center;
  gap: 18px;
  flex: 1;
  font-size: 12px;
  color: var(--paiban-muted);
}

.bar-stat b {
  font-size: 15px;
  color: var(--paiban-ink);
}

.bar-stat.accent b {
  color: var(--el-color-primary, #4f6ef7);
}

.bar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.unsaved {
  font-size: 12px;
  color: var(--el-color-warning, #e6a23c);
}

.read-only {
  font-size: 12px;
  color: var(--paiban-muted);
}
</style>
