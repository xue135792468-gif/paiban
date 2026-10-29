<template>
  <section class="panel cur" data-testid="wizard-panel-3">
    <div class="phead">
      <span class="idx">3</span>
      <h2>核对并提交</h2>
      <span class="hint">只列这次会变化的内容</span>
    </div>
    <div class="pbody">
      <div v-if="flow.loading" class="loading" data-testid="confirm-loading">正在生成方案…</div>

      <!-- 生成/切换/提交失败：大白话原文 + 重新生成，不静默跳步。 -->
      <div v-else-if="flow.error" class="callout" data-testid="confirm-error" role="alert">
        <div class="cw"><span class="ic">!</span><span>{{ flow.error }}</span></div>
        <button type="button" class="bt" data-testid="regenerate" :disabled="flow.loading" @click="flow.generate()">重新生成</button>
      </div>

      <!-- 调课无解：错误条 + 后端逐条原因；testid 统一挂 confirm-error（消息行保留旧名）。 -->
      <div v-else-if="flow.noSolution" class="callout" data-testid="confirm-error" role="alert">
        <div class="cw" data-testid="confirm-no-solution">
          <span class="ic">!</span><span>没有可行的置换环（原课表未动）</span>
        </div>
        <ul v-if="flow.diagnoses.length > 0" class="list">
          <li v-for="(item, index) in flow.diagnoses" :key="index">{{ item.message }}</li>
        </ul>
        <button type="button" class="bt" data-testid="regenerate" :disabled="flow.loading" @click="flow.generate()">重新生成</button>
      </div>

      <template v-else-if="flow.previewLoaded && flow.visual">
        <!-- 方案页签（仅调课）：单方案也显示单页签，方便统一。 -->
        <div v-if="flow.plans.length > 0" class="tabs" role="tablist" data-testid="swap-plan-tabs">
          <button
            v-for="(plan, index) in flow.plans"
            :key="plan.planId"
            type="button"
            role="tab"
            class="tab"
            :data-testid="`swap-plan-tab-${index}`"
            :aria-selected="flow.activePlanIndex === index ? 'true' : 'false'"
            @click="flow.selectPlan(index)"
          >
            <b>方案 {{ planLetter(index) }}{{ index === 0 ? '（推荐）' : '' }}</b>
            <span>{{ plan.moves.length }} 节课对调 · 影响 {{ plan.impactDays }} 天</span>
          </button>
        </div>

        <div class="conclusion">
          <!-- eslint-disable-next-line vue/no-v-html —— 结论含 <b> 强调，动态值已在 useConfirmFlow 转义 -->
          <div class="big" data-testid="confirm-conclusion" v-html="flow.conclusion" />
          <div class="scope" data-testid="confirm-scope">{{ flow.scope }}</div>
        </div>

        <!-- 修订（票 04 语义）：再次调整已生效申请时出现。 -->
        <div v-if="flow.revisionText" class="callout" data-testid="revision-banner">
          <div class="cw"><span class="ic">!</span><span>{{ flow.revisionText }}</span></div>
        </div>

        <!-- 跨科门：仅代课；未勾选时两个提交按钮 disabled（dock 侧读同一状态）。 -->
        <div v-if="flow.crossSubject" class="callout" data-testid="cross-subject-callout">
          <div class="cw"><span class="ic">!</span><span>{{ flow.crossSubjectText }}</span></div>
          <label class="ack">
            <input type="checkbox" data-testid="cross-subject-ack" :checked="flow.crossSubjectAck" @change="onAckChange">
            我已知晓，可以跨科
          </label>
        </div>

        <!-- issues 只读警示：每条一行，无勾选门。 -->
        <div v-if="flow.issues.length > 0" class="callout" data-testid="preview-issues">
          <div class="cw"><span class="ic">!</span><span>校验提示（硬冲突以确认时服务端裁决为准）</span></div>
          <ul class="list">
            <li v-for="(issue, index) in flow.issues" :key="index">{{ issue }}</li>
          </ul>
        </div>

        <ChangeVisualizer :visual="flow.visual" />

        <ChangeDetails :rows="flow.diffRows" :rest="flow.restText" :people="flow.people" />

        <div class="cap" data-testid="confirm-deadline">
          这个方案保留到 {{ flow.reservedText }}；过期后回来点一下就能重新生成。
        </div>
      </template>
    </div>
  </section>
</template>

<script setup lang="ts">
import { watch } from 'vue'
import ChangeVisualizer from './ChangeVisualizer.vue'
import ChangeDetails from './ChangeDetails.vue'
import type { ConfirmFlow } from './useConfirmFlow'

/**
 * 票 02：第③步面板体（顺序照 v3a 原型 step3）。
 * 方案页签（仅调课）→ 一句话结论卡 → 修订横幅 → 跨科勾选门 → issues 警示
 * → 课表变化可视化 → 折叠文字明细 → 保留时间；提交按钮在 View 的 dock 里。
 * 数据全部来自 useConfirmFlow（真实接口），这里只渲染。
 */

const props = defineProps<{ flow: ConfirmFlow }>()

const emit = defineEmits<{
  /** 提交审批 / 亲自确认成功后通知 View 切成功态（标题与正文在 flow 上）。 */
  success: []
}>()

watch(
  () => props.flow.result,
  (value) => {
    if (value) emit('success')
  },
)

function planLetter(index: number): string {
  return String.fromCharCode(65 + index)
}

function onAckChange(event: Event) {
  props.flow.setCrossSubjectAck((event.target as HTMLInputElement).checked)
}
</script>

<style scoped>
.panel {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  min-width: 0;
  overflow: hidden;
}
.panel.cur {
  border-left: 3px solid var(--brand);
}
.phead {
  align-items: center;
  border-bottom: 1px solid var(--line);
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  min-width: 0;
  padding: 16px 24px;
}
.idx {
  align-items: center;
  background: var(--brand-soft);
  border-radius: 50%;
  color: var(--brand);
  display: grid;
  flex: none;
  font-size: 12px;
  height: 22px;
  place-items: center;
  width: 22px;
}
.phead h2 {
  font-size: 15px;
  font-weight: 500;
  margin: 0;
}
.hint {
  color: var(--muted);
  font-size: 12px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.pbody {
  display: flex;
  flex-direction: column;
  gap: 24px;
  min-width: 0;
  padding: 24px;
}
.tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.tab {
  align-items: flex-start;
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: 9px;
  color: var(--text);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  font: inherit;
  gap: 1px;
  padding: 8px 12px;
  text-align: left;
}
.tab b {
  font-size: 13px;
  font-weight: 500;
}
.tab span {
  color: var(--muted);
  font-size: 11.5px;
}
.tab[aria-selected='true'] {
  background: var(--brand-soft);
  border-color: var(--brand);
  box-shadow: inset 0 0 0 1px var(--brand);
}
.tab[aria-selected='true'] span {
  color: var(--brand);
}
.conclusion {
  background: var(--surface);
  border: 1px solid var(--line);
  border-left: 3px solid var(--brand);
  border-radius: var(--radius-sm);
  padding: 14px 18px;
}
.conclusion .big {
  font-size: 15px;
  line-height: 1.65;
}
/* 原型 v3a-airy：结论句里的新教师品牌色加粗。 */
.conclusion .big b {
  color: var(--brand);
  font-weight: 500;
}
.conclusion .scope {
  color: var(--muted);
  font-size: 12px;
  margin-top: 2px;
}
.callout {
  background: var(--warn-soft);
  border: 1px solid color-mix(in srgb, var(--warn) 38%, transparent);
  border-radius: var(--radius-sm);
  color: var(--warn);
  display: flex;
  flex-direction: column;
  font-size: 13px;
  gap: 8px;
  padding: 12px 14px;
}
.callout .cw {
  align-items: flex-start;
  display: flex;
  gap: 8px;
}
.callout .ic {
  align-items: center;
  background: var(--warn);
  border-radius: 50%;
  color: var(--on-brand);
  display: grid;
  flex: none;
  font-size: 12px;
  font-weight: 500;
  height: 18px;
  margin-top: 2px;
  place-items: center;
  width: 18px;
}
.ack {
  cursor: pointer;
  display: inline-flex;
  font-weight: 500;
  gap: 8px;
}
.ack input {
  accent-color: var(--warn);
}
.list {
  margin: 0;
  padding-left: 18px;
}
.loading {
  color: var(--muted);
  font-size: 13px;
}
.cap {
  color: var(--muted);
  font-size: 12px;
}
.bt {
  align-items: center;
  align-self: flex-start;
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: 8px;
  color: var(--text);
  cursor: pointer;
  display: inline-flex;
  font: inherit;
  font-size: 13.5px;
  justify-content: center;
  min-height: var(--btn-h);
  padding: 0 16px;
}
.bt:hover:not(:disabled) {
  border-color: var(--brand);
  color: var(--brand);
}
@media (max-width: 900px) {
  .phead {
    padding: 12px 16px;
  }
  .pbody {
    padding: 16px;
  }
}
@media (max-width: 520px) {
  .phead {
    padding: 12px 14px;
  }
  .pbody {
    padding: 14px;
  }
}
</style>
