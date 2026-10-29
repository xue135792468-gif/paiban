<template>
  <details class="fold" data-testid="confirm-details">
    <summary>文字明细：原来 → 改后、受影响的人</summary>
    <div class="fold-in">
      <div class="sec">
        <div class="lab">原来 → 改后</div>
        <div class="diff" data-testid="change-diff">
          <div class="drow hd">
            <span>项目</span><span>原来</span><span>改后</span>
          </div>
          <div v-for="row in rows" :key="`${row.item}-${row.before}`" class="drow">
            <span>{{ row.item }}</span>
            <span class="old">{{ row.before }}</span>
            <span class="new">{{ row.after }}</span>
          </div>
          <div class="rest">{{ rest }}</div>
        </div>
      </div>
      <div class="sec">
        <div class="lab">谁会受影响</div>
        <div class="people" data-testid="change-people">
          <div v-for="person in people" :key="`${person.name}-${person.text}`" class="person">
            <b>{{ person.name }}</b>
            <span>{{ person.text }}</span>
          </div>
        </div>
      </div>
    </div>
  </details>
</template>

<script setup lang="ts">
import type { DiffRow, PersonRow } from './useConfirmFlow'

/**
 * 票 02：折叠文字明细。
 * 三列 diff（项目/原来/改后，只列变化行，末行「其余不变」）+ 按人句子清单；
 * 数据全部来自 useConfirmFlow 的 diffRows / people / restText（源自预览接口）。
 */

defineProps<{
  rows: DiffRow[]
  rest: string
  people: PersonRow[]
}>()
</script>

<style scoped>
.fold {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
}
.fold > summary {
  align-items: center;
  color: var(--muted);
  cursor: pointer;
  display: flex;
  font-size: 13px;
  gap: 8px;
  list-style: none;
  padding: 11px 14px;
}
.fold > summary::-webkit-details-marker {
  display: none;
}
.fold > summary::before {
  color: var(--faint);
  content: '▸';
}
.fold[open] > summary::before {
  content: '▾';
}
.fold-in {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 0 14px 14px;
}
.sec {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.lab {
  align-items: center;
  color: var(--muted);
  display: flex;
  font-size: 12px;
  gap: 10px;
}
.lab::after {
  background: var(--line);
  content: '';
  flex: 1;
  height: 1px;
}
.diff {
  border: 1px solid var(--line);
  border-radius: 9px;
  overflow: hidden;
}
.drow {
  align-items: center;
  display: grid;
  font-size: 13px;
  gap: 12px;
  grid-template-columns: 120px minmax(0, 1fr) minmax(0, 1fr);
  padding: 9px 12px;
}
.drow + .drow {
  border-top: 1px solid var(--line);
}
.drow.hd {
  background: var(--inset);
  color: var(--muted);
  font-size: 12px;
}
.old {
  color: var(--muted);
  text-decoration: line-through;
}
.new {
  color: var(--ok);
  font-weight: 500;
}
.rest {
  background: var(--inset);
  border-top: 1px solid var(--line);
  color: var(--muted);
  font-size: 12px;
  padding: 9px 12px;
}
.people {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.person {
  align-items: baseline;
  border: 1px solid var(--line);
  border-radius: 8px;
  display: flex;
  font-size: 13px;
  gap: 10px;
  min-width: 0;
  padding: 9px 12px;
}
.person b {
  flex: none;
  font-weight: 500;
  min-width: 62px;
}
.person span {
  color: var(--muted);
  overflow-wrap: anywhere;
}
@media (max-width: 520px) {
  .drow {
    gap: 2px;
    grid-template-columns: 1fr;
  }
  .drow.hd {
    display: none;
  }
  .person {
    flex-direction: column;
    gap: 2px;
  }
}
</style>
