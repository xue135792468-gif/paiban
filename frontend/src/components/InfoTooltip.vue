<template>
  <el-tooltip :content="content" placement="top" :show-after="200">
    <span
      class="info-tooltip"
      role="img"
      tabindex="0"
      :aria-label="`字段说明：${content}`"
      data-testid="info-tooltip"
    >
      <el-icon><QuestionFilled /></el-icon>
    </span>
  </el-tooltip>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElIcon, ElTooltip } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import { TOOLTIPS, type TooltipKey } from '@/help/tooltips'

const props = defineProps<{ field: TooltipKey }>()

const content = computed(() => TOOLTIPS[props.field])
</script>

<style scoped>
.info-tooltip {
  display: inline-flex;
  align-items: center;
  margin-left: 4px;
  color: var(--paiban-muted, #9ca3af);
  font-size: 13px;
  line-height: 1;
  cursor: help;
  vertical-align: middle;
}

.info-tooltip:hover,
.info-tooltip:focus-visible {
  color: var(--el-color-primary, #4f6ef7);
  outline: none;
}
</style>
