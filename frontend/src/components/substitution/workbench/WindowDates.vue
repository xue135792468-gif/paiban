<template>
  <div class="chips" data-testid="window-dates">
    <button
      v-for="chip in chips"
      :key="chip.date"
      type="button"
      class="chip"
      :data-testid="`swap-date-chip-${chip.date}`"
      :aria-pressed="chip.date === selected ? 'true' : 'false'"
      :disabled="chip.disabled"
      :title="chip.reason"
      @click="emit('select', chip.date)"
    >
      <span class="d">{{ chip.monthDay }} {{ chip.dayLabel }}</span>
      <!-- 置灰 chip 显示置灰原因（周末没有课），可用 chip 显示相对标签（今天/下周…）。 -->
      <span class="w">{{ chip.disabled ? chip.reason ?? '' : chip.relative ?? '' }}</span>
    </button>
  </div>
</template>

<script setup lang="ts">
import type { WindowDateChip } from './windowDates'

defineProps<{ chips: WindowDateChip[]; selected: string }>()

const emit = defineEmits<{ select: [date: string] }>()
</script>

<style scoped>
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: 9px;
  color: var(--text);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  font: inherit;
  gap: 1px;
  min-width: 82px;
  padding: 8px 10px;
  text-align: left;
}
.chip:hover:not(:disabled) {
  border-color: var(--brand);
}
.chip[aria-pressed='true'] {
  background: var(--brand-soft);
  border-color: var(--brand);
  box-shadow: inset 0 0 0 1px var(--brand);
}
.chip:disabled {
  background: var(--inset);
  cursor: not-allowed;
  opacity: 0.45;
}
.d {
  font-size: 12.5px;
}
.w {
  color: var(--muted);
  font-size: 11px;
}
@media (max-width: 520px) {
  .chip {
    flex: 1 1 86px;
    min-width: 0;
  }
}
</style>
