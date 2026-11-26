<template>
  <footer class="save-bar" :class="{ 'is-dirty': dirty }" data-testid="rule-save-bar">
    <div class="save-status">
      <el-tag
        v-if="dirty"
        type="warning"
        effect="light"
        data-testid="rule-unsaved-indicator"
      >
        有未保存的修改
      </el-tag>
      <span v-else class="saved-hint" data-testid="rule-saved-indicator">当前规则已保存</span>
    </div>
    <el-button
      type="primary"
      :loading="saving"
      :disabled="disabled"
      data-testid="rule-save"
      @click="emit('save')"
    >
      保存规则
    </el-button>
  </footer>
</template>

<script setup lang="ts">
defineProps<{
  dirty: boolean
  saving: boolean
  disabled: boolean
}>()

const emit = defineEmits<{
  (e: 'save'): void
}>()
</script>

<style scoped>
.save-bar {
  position: sticky;
  bottom: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 14px;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: var(--paiban-radius, 8px);
  box-shadow: 0 -2px 10px rgba(0, 0, 0, 0.04);
}

.save-bar.is-dirty {
  border-color: #f3d19e;
  background: #fdf6ec;
}

.save-status {
  display: flex;
  align-items: center;
  gap: 8px;
}

.saved-hint {
  font-size: 13px;
  color: var(--paiban-muted);
}
</style>
