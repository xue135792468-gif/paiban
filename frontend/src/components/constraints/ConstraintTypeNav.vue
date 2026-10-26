<template>
  <nav class="constraint-nav" data-testid="constraint-nav">
    <div
      v-for="group in groups"
      :key="group.category"
      class="nav-group"
      data-testid="constraint-nav-group"
    >
      <div class="nav-group-head">
        <span>{{ group.categoryLabel }}</span>
        <span class="nav-group-counts" data-testid="constraint-nav-group-counts">{{ group.counts.enabledTypes }}/{{ group.counts.totalTypes }} 类型 · {{ group.counts.entries }} 条</span>
      </div>
      <button
        v-for="row in group.rows"
        :key="row.code"
        type="button"
        class="nav-item"
        :class="{ 'is-active': row.code === selected, 'is-off': !row.enabled }"
        :data-type="row.code"
        data-testid="constraint-nav-item"
        @click="emit('select', row.code)"
      >
        <span
          class="nav-dot"
          data-testid="constraint-nav-dot"
          :data-state="dotState(row)"
          :title="DOT_TITLES[dotState(row)]"
        />
        <span class="nav-name">{{ row.name }}</span>
        <span class="nav-count" data-testid="constraint-nav-count">{{ countLabel(row) }}</span>
      </button>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { entryCountOf } from './draftRules'
import { isSwitchType } from './entryModel'
import type { NavGroup, TypeDraft } from './types'

const props = defineProps<{
  groups: NavGroup[]
  selected: string | null
  errorTypes: string[]
}>()

const emit = defineEmits<{ select: [code: string] }>()

const DOT_TITLES: Record<string, string> = {
  on: '已启用',
  off: '未启用',
  error: '配置有错误',
}

function dotState(row: TypeDraft): 'on' | 'off' | 'error' {
  if (props.errorTypes.includes(row.code)) {
    return 'error'
  }
  return row.enabled ? 'on' : 'off'
}

/** Same single counting rule as the summary bar (`draftRules.entryCountOf`). */
function countLabel(row: TypeDraft): string {
  return isSwitchType(row) ? '开关' : `${entryCountOf(row)} 条`
}
</script>

<style scoped>
.constraint-nav {
  position: sticky;
  top: 64px;
  align-self: start;
  max-height: calc(100vh - 96px);
  overflow-y: auto;
  padding: 8px;
  border-radius: var(--paiban-radius, 8px);
  background: #fff;
  border: 1px solid #ebeef5;
}

.nav-group + .nav-group {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #f0f2f5;
}

.nav-group-head {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 4px 8px 6px;
  font-size: 12px;
  font-weight: 600;
  color: #374151;
}

.nav-group-counts {
  font-weight: normal;
  font-size: 11px;
  color: var(--paiban-muted);
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 8px;
  border: none;
  border-radius: 6px;
  background: transparent;
  font-size: 13px;
  color: var(--paiban-ink);
  text-align: left;
  cursor: pointer;
}

.nav-item:hover {
  background: #f5f7fb;
}

.nav-item.is-active {
  background: #eef2ff;
  color: var(--el-color-primary, #4f6ef7);
  font-weight: 600;
}

.nav-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #22c55e;
  flex-shrink: 0;
}

.nav-dot[data-state='off'] {
  background: #d1d5db;
}

.nav-dot[data-state='error'] {
  background: var(--el-color-danger, #f56c6c);
  box-shadow: 0 0 0 3px rgba(245, 108, 108, 0.16);
}

.nav-item.is-off .nav-name {
  color: #9ca3af;
}

.nav-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-count {
  font-size: 11px;
  color: var(--paiban-muted);
  font-variant-numeric: tabular-nums;
}
</style>
