<template>
  <div class="stepper" data-testid="wizard-stepper">
    <template v-for="(item, index) in steps" :key="item.no">
      <div
        class="step"
        :class="{ cur: step === item.no, done: step > item.no }"
        :data-testid="`wizard-step-${item.no}`"
        :aria-current="step === item.no ? 'step' : undefined"
      >
        <span class="dot" aria-hidden="true">{{ step > item.no ? '✓' : item.no }}</span>
        <span class="st">{{ item.label }}</span>
      </div>
      <span v-if="index < steps.length - 1" class="cline" aria-hidden="true" />
    </template>
    <span class="sr" data-testid="wizard-live" aria-live="polite">{{ liveText }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ step: 1 | 2 | 3 }>()

const steps: Array<{ no: 1 | 2 | 3; label: string }> = [
  { no: 1, label: '选哪节课' },
  { no: 2, label: '怎么调' },
  { no: 3, label: '核对并提交' },
]

const liveText = computed(
  () => `第 ${props.step} 步，共 3 步：${steps.find((item) => item.no === props.step)?.label ?? ''}`,
)
</script>

<style scoped>
.stepper {
  align-items: center;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow);
  display: flex;
  gap: 10px;
  padding: 12px 18px;
}
.step {
  align-items: center;
  color: var(--faint);
  display: flex;
  font-size: 13px;
  gap: 8px;
  white-space: nowrap;
}
.dot {
  align-items: center;
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: 50%;
  display: grid;
  flex: none;
  font-size: 12px;
  height: 22px;
  place-items: center;
  width: 22px;
}
.step.cur {
  color: var(--text);
  font-weight: 500;
}
.step.cur .dot {
  background: var(--brand);
  border-color: var(--brand);
  color: var(--on-brand);
}
.step.done {
  color: var(--ok);
}
.step.done .dot {
  background: var(--ok-soft);
  border-color: var(--ok);
  color: var(--ok);
}
.cline {
  background: var(--line);
  flex: 1;
  height: 1px;
  min-width: 8px;
}
.sr {
  border: 0;
  clip: rect(0 0 0 0);
  height: 1px;
  overflow: hidden;
  position: absolute;
  white-space: nowrap;
  width: 1px;
}
@media (max-width: 560px) {
  .stepper {
    gap: 5px;
    padding: 8px 10px;
  }
  .step {
    font-size: 11px;
    gap: 5px;
  }
  .dot {
    font-size: 11px;
    height: 20px;
    width: 20px;
  }
}
</style>
