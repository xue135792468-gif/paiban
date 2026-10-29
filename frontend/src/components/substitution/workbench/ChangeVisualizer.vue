<template>
  <div class="sec" data-testid="change-visual">
    <div class="lab">课表怎么变</div>
    <div v-if="visual.legend.length > 0" class="legend" data-testid="change-legend">
      <span v-for="item in visual.legend" :key="item" class="lg">
        <b v-if="circled(item)">{{ item.slice(0, 1) }}</b>{{ item.slice(circled(item) ? 1 : 0) }}
      </span>
    </div>
    <div class="weeks">
      <div v-for="(week, index) in visual.weeks" :key="week.label" class="wbox" :data-testid="`change-week-${index}`">
        <div class="whead">
          <b>{{ week.label }}</b>
          <span>{{ week.range }}</span>
        </div>

        <!-- 桌面：迷你周表；≤520px：按天列表。二选一渲染（v-if），不靠 CSS 藏。 -->
        <div v-if="!narrow" class="wtab">
          <div class="wgh" aria-hidden="true" />
          <div v-for="day in DAYS" :key="`head-${day}`" class="wgh">{{ day }}</div>
          <template v-for="row in week.periodRows" :key="`row-${row}`">
            <div class="wrow">第{{ row }}节</div>
            <div
              v-for="(_, dayIdx) in DAYS"
              :key="`cell-${row}-${dayIdx}`"
              class="wc"
              :class="{ chg: lookup(week, row, dayIdx) }"
              :data-testid="lookup(week, row, dayIdx) ? `change-cell-${seq(week, row, dayIdx)}` : undefined"
            >
              <template v-if="lookup(week, row, dayIdx)">
                <span class="no" aria-hidden="true">{{ lookup(week, row, dayIdx)!.no }}</span>
                <span v-if="lookup(week, row, dayIdx)!.old" class="old">{{ lookup(week, row, dayIdx)!.old }}</span>
                <span v-if="lookup(week, row, dayIdx)!.new" class="new">{{ lookup(week, row, dayIdx)!.new }}</span>
              </template>
            </div>
          </template>
        </div>

        <div v-else class="wlist" :data-testid="index === 0 ? 'change-list' : `change-list-${index}`">
          <div v-for="item in listItems(week)" :key="item.no" class="wi">
            <span class="slot"><b aria-hidden="true">{{ item.no }}</b> {{ item.slot }}</span>
            <span class="vv">
              <span v-if="item.old" class="old">{{ item.old }}</span>
              <span v-if="item.old && item.new"> → </span>
              <span v-if="item.new" class="new">{{ item.new }}</span>
            </span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { ChangeCell, VisualModel, WeekTable } from './changeVisual'

/**
 * 票 02：课表变化可视化（视觉主角）。
 * 每周一张迷你周表（行 = 该周变化涉及的节次升序），变化格淡蓝底 + 编号 +
 * 旧值删除线 → 新值绿色；图例一行多列；≤520px 切按天列表（沿用票 01 的 matchMedia）。
 */

const props = defineProps<{ visual: VisualModel }>()

const DAYS = ['周一', '周二', '周三', '周四', '周五']

/** 编号（周、日期、节次）分配顺序 = cells 的插入顺序，序号即渲染序号。 */
const seqByKey = computed(() => {
  const map = new Map<string, number>()
  let seq = 0
  for (const week of props.visual.weeks) {
    for (const key of Object.keys(week.cells)) {
      seq += 1
      map.set(`${week.label}|${key}`, seq)
    }
  }
  return map
})

function lookup(week: WeekTable, periodNo: number, dayIdx: number): ChangeCell | null {
  return week.cells[`${periodNo}-${dayIdx}`] ?? null
}

function seq(week: WeekTable, periodNo: number, dayIdx: number): number {
  return seqByKey.value.get(`${week.label}|${periodNo}-${dayIdx}`) ?? 0
}

/** 图例首字符若是带圈编号则挑出来上品牌色。 */
function circled(item: string): boolean {
  const code = item.charCodeAt(0)
  return code >= 0x2460 && code <= 0x2473
}

function listItems(week: WeekTable): Array<{ no: string; slot: string; old: string | null; new: string | null }> {
  return Object.entries(week.cells).map(([key, cell]) => {
    const [periodNo, dayIdx] = key.split('-').map(Number)
    return {
      no: cell.no,
      // 本周不写「本周」前缀（避免「本周周三」），下周必须带前缀区分。
      slot: `${week.label === '下周' ? '下周' : ''}${DAYS[dayIdx] ?? ''}第${periodNo}节`,
      old: cell.old,
      new: cell.new,
    }
  })
}

// ------------------------------------------------------------- 响应式（票 01 模式）
const narrow = ref(false)
let media: MediaQueryList | null = null

function syncNarrow() {
  narrow.value = media?.matches ?? false
}

onMounted(() => {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
  media = window.matchMedia('(max-width: 520px)')
  syncNarrow()
  media.addEventListener('change', syncNarrow)
})

onBeforeUnmount(() => {
  media?.removeEventListener('change', syncNarrow)
})
</script>

<style scoped>
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
  min-width: 0;
}
.lab::after {
  background: var(--line);
  content: '';
  flex: 1;
  height: 1px;
}
.legend {
  color: var(--muted);
  display: flex;
  flex-wrap: wrap;
  font-size: 12px;
  gap: 4px 20px;
}
.legend .lg {
  align-items: baseline;
  display: inline-flex;
  min-width: 0;
  overflow-wrap: anywhere;
}
.legend b {
  color: var(--brand);
  font-weight: 600;
  margin-right: 4px;
}
.weeks {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.wbox {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.whead {
  align-items: center;
  background: var(--inset);
  border-bottom: 1px solid var(--line);
  color: var(--muted);
  display: flex;
  flex-wrap: wrap;
  font-size: 12px;
  gap: 4px 10px;
  justify-content: space-between;
  min-width: 0;
  padding: 8px 12px;
}
.whead b {
  color: var(--text);
  font-weight: 500;
}
/* 周范围「10/26–10/30」不可断行，极窄容器下允许换行避免撑出横向溢出。 */
.whead span {
  min-width: 0;
  overflow-wrap: anywhere;
}
.wtab {
  display: grid;
  gap: 8px;
  grid-template-columns: 44px repeat(5, minmax(0, 1fr));
  padding: 10px;
}
.wgh {
  align-self: end;
  color: var(--muted);
  font-size: 11px;
  padding-bottom: 2px;
  text-align: center;
}
.wrow {
  align-items: center;
  color: var(--muted);
  display: flex;
  font-size: 11px;
  justify-content: center;
}
.wc {
  border: 1px dashed var(--line);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  font-size: 10.5px;
  gap: 1px;
  justify-content: center;
  line-height: 1.3;
  min-height: 44px;
  min-width: 0;
  padding: 3px 6px;
  position: relative;
}
.wc.chg {
  background: var(--brand-soft);
  border-color: var(--brand);
  border-style: solid;
  justify-content: flex-start;
  padding-top: 14px;
}
.wc .no {
  color: var(--brand);
  font-size: 9.5px;
  font-weight: 600;
  left: 5px;
  letter-spacing: 0.5px;
  line-height: 1;
  position: absolute;
  top: 1px;
}
.wc .old {
  color: var(--muted);
  overflow-wrap: anywhere;
  text-decoration: line-through;
}
.wc .new {
  color: var(--ok);
  font-weight: 500;
  overflow-wrap: anywhere;
}
.wlist {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 10px 10px;
}
.wi {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  gap: 1px;
  min-width: 0;
}
.wi .slot {
  color: var(--muted);
  font-size: 11.5px;
  overflow-wrap: anywhere;
}
.wi .slot b {
  color: var(--brand);
}
.wi .vv {
  overflow-wrap: anywhere;
}
</style>
