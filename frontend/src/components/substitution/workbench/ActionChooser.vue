<template>
  <section class="panel cur" data-testid="wizard-panel-2">
    <div class="phead">
      <span class="idx">2</span>
      <h2>怎么调</h2>
      <span class="hint">{{ hint || '选择一种调整方式' }}</span>
    </div>
    <div class="pbody">
      <div class="acts" :class="{ single: action !== null }">
        <template v-if="action === null">
          <button type="button" class="act" data-testid="choose-cover" aria-pressed="false" @click="emit('chooseAction', 'cover')">
            <span class="t">换老师上（代课）</span>
            <span class="d">时间不变，把这节课改由另一位老师上。适合老师临时请假。</span>
          </button>
          <button type="button" class="act" data-testid="choose-swap" aria-pressed="false" @click="emit('chooseAction', 'swap')">
            <span class="t">换时间上（调课）</span>
            <span class="d">和同班另一节课对调时间，两位老师都保持上课。适合老师同一时段开会或外出。</span>
          </button>
        </template>

        <template v-else-if="action === 'cover'">
          <button type="button" class="act sel" data-testid="choose-cover" aria-pressed="true" @click="emit('chooseAction', 'cover')">
            <span class="t">换老师上（代课）<span class="bg sel">已选</span></span>
            <span class="d">时间不变，把这节课改由另一位老师上。适合老师临时请假。</span>
          </button>
          <button type="button" class="act cmp" data-testid="switch-swap" @click="emit('chooseAction', 'swap')">
            <span class="t">改为『换时间上（调课）』</span>
            <span class="ar" aria-hidden="true">→</span>
          </button>

          <div class="pane" data-testid="substitute-picker">
            <h3>选择代课老师</h3>

            <div v-if="candidateList?.legacyLocked" class="callout" data-testid="legacy-locked">
              <div class="cw"><span class="ic">!</span><span>该课节当前生效的是旧格式调代记录（历史数据），需先完成历史转换，管理端暂不能修改。</span></div>
            </div>
            <div v-else-if="candidateList?.activeRequestId" class="callout" data-testid="active-request-alert">
              <div class="cw"><span class="ic">!</span><span>该课节已有待处理的申请（#{{ candidateList.activeRequestId }}），请先在待审批中处理该单。</span></div>
            </div>

            <template v-else>
              <p class="hint2">同科教师优先排列；跨科可选但会提示；忙碌的教师会显示原因，最终以服务端校验为准。</p>

              <div v-if="candidatesLoading" class="loading">候选教师加载中…</div>
              <div v-else-if="candidatesError" class="callout" data-testid="candidates-error">
                <div class="cw"><span class="ic">!</span><span>{{ candidatesError }}</span></div>
                <button type="button" class="bt" data-testid="retry-candidates" @click="emit('retryCandidates')">
                  重试
                </button>
              </div>
              <div v-else-if="candidates.length === 0" class="loading">没有可用的代课老师。</div>
              <div v-else class="opts">
                <label
                  v-for="item in candidates"
                  :key="item.teacherId"
                  class="opt"
                  :class="{ dis: item.busyAtPeriod }"
                  :data-testid="`substitute-option-${item.teacherId}`"
                >
                  <input
                    type="radio"
                    name="workbench-substitute"
                    :value="item.teacherId"
                    :checked="candidate?.teacherId === item.teacherId"
                    :disabled="item.busyAtPeriod"
                    @change="emit('chooseCandidate', item)"
                  >
                  <span>
                    <span class="nm">{{ item.name }}</span>
                    <span class="bg" :class="badgeClass(item)">{{ badgeText(item) }}</span>
                    <br>
                    <span class="why">{{ explain(item) }}</span>
                  </span>
                </label>
              </div>

              <button
                v-if="restorable && baselineTeacherId"
                type="button"
                class="bt"
                data-testid="restore-baseline"
                @click="emit('restoreBaseline', baselineTeacherId)"
              >
                恢复基线教师{{ candidateList?.baselineTeacherName ? `（${candidateList.baselineTeacherName}）` : '' }}
              </button>

              <label class="field">
                <span>代课原因</span>
                <input
                  type="text"
                  class="text-input"
                  data-testid="preview-reason"
                  placeholder="例如：临时请病假"
                  :value="coverReason"
                  @input="onCoverReason"
                >
              </label>
            </template>
          </div>
        </template>

        <template v-else>
          <button type="button" class="act sel" data-testid="choose-swap" aria-pressed="true" @click="emit('chooseAction', 'swap')">
            <span class="t">换时间上（调课）<span class="bg sel">已选</span></span>
            <span class="d">和同班另一节课对调时间，两位老师都保持上课。适合老师同一时段开会或外出。</span>
          </button>
          <button type="button" class="act cmp" data-testid="switch-cover" @click="emit('chooseAction', 'cover')">
            <span class="t">改为『换老师上（代课）』</span>
            <span class="ar" aria-hidden="true">→</span>
          </button>

          <div class="pane">
            <h3>选择目标时间</h3>
            <div>
              <div class="cap mb">{{ caption }}</div>
              <WindowDates :chips="chips" :selected="targetDate" @select="emit('selectTargetDate', $event)" />
            </div>

            <div v-if="targetDate" class="period-box">
              <div class="cap mb">选这个班当天已有的一节课对调（空格子不参与）</div>
              <div v-if="targetsLoading" class="loading">目标节次加载中…</div>
              <div v-else-if="targetsError" class="callout" data-testid="swap-targets-error">
                <div class="cw"><span class="ic">!</span><span>{{ targetsError }}</span></div>
              </div>
              <div v-else-if="targetSlots.length === 0" class="callout" data-testid="swap-empty-day">
                <div class="cw"><span class="ic">!</span><span>空格只挪不补，请换一个目标日期。</span></div>
              </div>
              <div v-else class="opts" data-testid="swap-target-period">
                <label
                  v-for="slot in targetSlots"
                  :key="slot.periodId"
                  class="opt"
                  :data-testid="`swap-target-period-${slot.periodNo}`"
                >
                  <input
                    type="radio"
                    name="workbench-swap-period"
                    :value="slot.periodId"
                    :checked="targetPeriodId === slot.periodId"
                    @change="emit('selectTargetSlot', slot.periodId)"
                  >
                  <span>
                    <span class="nm">第{{ slot.periodNo }}节 · {{ slot.courseName }} · {{ slot.teacherName }}</span>
                  </span>
                </label>
              </div>
            </div>

            <label class="field">
              <span>调课原因</span>
              <input
                type="text"
                class="text-input"
                data-testid="swap-reason"
                placeholder="例如：老师要去区里开会"
                :value="swapReason"
                @input="onSwapReason"
              >
            </label>
          </div>
        </template>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import WindowDates from './WindowDates.vue'
import { windowCaption, type WindowDateChip } from './windowDates'
import type { WorkbenchCandidate, WorkbenchCandidateList, SwapTargetSlot } from './types'

const props = defineProps<{
  action: 'cover' | 'swap' | null
  hint: string
  lessonDate: string
  candidatesLoading: boolean
  candidatesError: string
  candidateList: WorkbenchCandidateList | null
  candidate: WorkbenchCandidate | null
  coverReason: string
  swapReason: string
  chips: WindowDateChip[]
  targetDate: string
  targetSlots: SwapTargetSlot[]
  targetPeriodId: number | null
  targetsLoading: boolean
  targetsError: string
  restorable: boolean
}>()

const emit = defineEmits<{
  chooseAction: [kind: 'cover' | 'swap']
  chooseCandidate: [candidate: WorkbenchCandidate]
  retryCandidates: []
  'update:coverReason': [value: string]
  'update:swapReason': [value: string]
  selectTargetDate: [date: string]
  selectTargetSlot: [periodId: number]
  restoreBaseline: [teacherId: number]
}>()

const candidates = computed(() => props.candidateList?.candidates ?? [])
const baselineTeacherId = computed(() => props.candidateList?.baselineTeacherId ?? null)
const caption = computed(() => windowCaption(props.lessonDate))

function badgeClass(item: WorkbenchCandidate) {
  if (item.busyAtPeriod) return ''
  return item.sameSubject ? 'ok' : 'warn'
}

function badgeText(item: WorkbenchCandidate) {
  if (item.busyAtPeriod) return '该时段有课'
  return item.sameSubject ? '同科 · 空闲' : '跨科'
}

function explain(item: WorkbenchCandidate) {
  const subject = item.subject ? `${item.subject}老师` : '未标注学科'
  if (item.busyAtPeriod) return item.busyReason || `${subject}，这一时段有冲突`
  return `${subject}，这一节没有课`
}

function onCoverReason(event: Event) {
  emit('update:coverReason', (event.target as HTMLInputElement).value)
}

function onSwapReason(event: Event) {
  emit('update:swapReason', (event.target as HTMLInputElement).value)
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
.acts {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  min-width: 0;
}
.acts.single {
  grid-template-columns: 1fr;
}
.act {
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: var(--radius-sm);
  color: var(--text);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  font: inherit;
  gap: 4px;
  min-width: 0;
  padding: 16px 18px;
  text-align: left;
}
.act:hover {
  border-color: var(--brand);
}
.act.sel {
  background: var(--brand-soft);
  border-color: var(--brand);
  box-shadow: inset 0 0 0 1px var(--brand);
}
.act .t {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  font-size: 14px;
  font-weight: 500;
  gap: 8px;
}
.act .d {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}
.act.cmp {
  align-items: center;
  background: transparent;
  border-style: dashed;
  color: var(--brand);
  flex-direction: row;
  justify-content: space-between;
  justify-self: end;
  padding: 10px 14px;
}
.act.cmp .t {
  color: var(--brand);
  font-size: 13px;
  font-weight: 400;
}
.act.cmp .ar {
  color: var(--brand);
}
.acts.single > .act.cmp {
  height: 36px;
  justify-self: end;
  padding: 0 12px;
}
.bg {
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  color: var(--muted);
  font-size: 11px;
  font-weight: 400;
  line-height: 18px;
  padding: 0 7px;
}
.bg.ok {
  background: var(--ok-soft);
  border-color: color-mix(in srgb, var(--ok) 40%, transparent);
  color: var(--ok);
}
.bg.warn {
  background: var(--warn-soft);
  border-color: color-mix(in srgb, var(--warn) 40%, transparent);
  color: var(--warn);
}
.bg.sel {
  background: var(--surface);
  border-color: color-mix(in srgb, var(--brand) 40%, transparent);
  color: var(--brand);
}
.pane {
  background: var(--inset);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
  padding: 24px;
}
.pane h3 {
  font-size: 13px;
  font-weight: 500;
  margin: 0;
}
.hint2 {
  color: var(--muted);
  font-size: 12px;
  margin: 0;
}
.loading {
  color: var(--muted);
  font-size: 13px;
}
.opts {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.opt {
  align-items: flex-start;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 9px;
  cursor: pointer;
  display: flex;
  gap: 10px;
  padding: 10px 12px;
}
.opt:hover {
  border-color: var(--line-2);
}
.opt input {
  accent-color: var(--brand);
  flex: none;
  margin: 4px 0 0;
}
.opt .nm {
  font-size: 13px;
  font-weight: 500;
}
.opt .why {
  color: var(--muted);
  font-size: 12px;
}
.opt.dis {
  background: var(--inset);
  cursor: not-allowed;
  opacity: 0.55;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}
.field > span {
  color: var(--muted);
  font-size: 12px;
}
.text-input {
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: 8px;
  color: var(--text);
  font: inherit;
  height: 38px;
  max-height: 38px;
  min-height: 38px;
  padding: 0 10px;
  width: 100%;
}
.text-input:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 1px;
}
.cap {
  color: var(--muted);
  font-size: 12px;
}
.mb {
  margin-bottom: 8px;
}
.period-box {
  display: flex;
  flex-direction: column;
  gap: 4px;
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
.bt {
  align-items: center;
  align-self: flex-start;
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: 8px;
  color: var(--text);
  cursor: pointer;
  display: inline-flex;
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
  .acts {
    grid-template-columns: 1fr;
  }
  .phead {
    padding: 12px 14px;
  }
  .pbody {
    padding: 14px;
  }
  .pane {
    padding: 16px;
  }
}
</style>
