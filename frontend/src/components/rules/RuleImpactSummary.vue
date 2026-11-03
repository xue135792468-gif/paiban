<template>
  <el-card shadow="never" class="impact" data-testid="rule-impact-summary">
    <div class="impact-head">
      <strong>{{ title }}</strong>
      <span class="spacer" />
      <el-button link data-testid="rule-impact-close" @click="emit('close')">关闭</el-button>
    </div>

    <div v-if="loading" class="impact-body" data-testid="rule-impact-loading">
      正在计算影响……
    </div>
    <el-alert
      v-else-if="error"
      type="warning"
      :closable="false"
      show-icon
      data-testid="rule-impact-error"
    >
      <template #title>影响预览失败</template>
      <p class="copy">{{ error }}</p>
    </el-alert>
    <template v-else-if="impact">
      <p class="counts" data-testid="rule-impact-counts">
        影响 {{ impact.classes.length }} 个班 / {{ impact.teachers.length }} 位教师 /
        {{ impact.lessonCount }} 个课节；{{ impact.conflicts.length }} 条冲突
      </p>
      <p v-if="impact.classes.length > 0" class="names" data-testid="rule-impact-classes">
        班级：{{ summarize(impact.classes) }}
      </p>
      <p v-if="impact.teachers.length > 0" class="names" data-testid="rule-impact-teachers">
        教师：{{ summarize(impact.teachers) }}
      </p>
      <p v-if="impact.slots.length > 0" class="names" data-testid="rule-impact-slots">
        课节：{{ slotText }}
      </p>
      <el-alert
        v-if="impact.errors.length > 0"
        type="error"
        :closable="false"
        show-icon
        data-testid="rule-impact-errors"
      >
        <template #title>规则还有错误</template>
        <p class="copy">{{ impact.errors.join('；') }}</p>
      </el-alert>
      <ul v-if="impact.conflicts.length > 0" class="conflicts" data-testid="rule-impact-conflicts">
        <li v-for="(conflict, index) in impact.conflicts.slice(0, 8)" :key="index">
          {{ conflict.message }}
        </li>
        <li v-if="impact.conflicts.length > 8" class="more">
          还有 {{ impact.conflicts.length - 8 }} 条冲突未列出
        </li>
      </ul>
      <p v-if="impact.warnings.length > 0" class="warnings" data-testid="rule-impact-warnings">
        {{ impact.warnings.join('；') }}
      </p>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { RuleImpact, RuleImpactObject } from '@/api/rules'
import { weekdayLabel } from '@/rules/compiler'

const props = defineProps<{
  title: string
  impact: RuleImpact | null
  loading: boolean
  error: string | null
}>()

const emit = defineEmits<{ close: [] }>()

const slotText = computed(() => {
  const impact = props.impact
  if (!impact) {
    return ''
  }
  const byDay = new Map<number, number[]>()
  impact.slots.forEach((slot) => {
    const list = byDay.get(slot.dayOfWeek) ?? []
    list.push(slot.periodNo)
    byDay.set(slot.dayOfWeek, list)
  })
  return Array.from(byDay.entries())
    .sort((left, right) => left[0] - right[0])
    .map(([day, periods]) => `${weekdayLabel(day)}第 ${periods.sort((a, b) => a - b).join('、')} 节`)
    .join('，')
})

function summarize(items: RuleImpactObject[]): string {
  const names = items.slice(0, 6).map((item) => item.name)
  return items.length > 6 ? `${names.join('、')} 等 ${items.length} 个` : names.join('、')
}
</script>

<style scoped>
.impact {
  border-radius: var(--paiban-radius, 8px);
  margin-top: 12px;
}

.impact-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.spacer {
  flex: 1 1 auto;
}

.impact-body,
.counts,
.names {
  margin: 0 0 6px;
  font-size: 13px;
}

.counts {
  font-size: 14px;
  font-weight: 600;
}

.copy {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-word;
}

.conflicts {
  margin: 6px 0;
  padding-left: 18px;
  color: var(--el-color-danger, #f56c6c);
  font-size: 12px;
  line-height: 1.7;
}

.more,
.warnings {
  color: var(--paiban-muted, #6b7280);
  font-size: 12px;
}
</style>
