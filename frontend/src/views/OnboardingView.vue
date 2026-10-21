<template>
  <div class="onboarding-page">
    <div class="header">
      <div>
        <h1>欢迎使用排课系统</h1>
        <p class="subtitle">按顺序完成下面几步，就能开始第一次自动排课。所有步骤都可以稍后回来继续。</p>
        <p v-if="!finished" class="progress" data-testid="wizard-progress">{{ progressLabel }}</p>
      </div>
      <el-button v-if="!finished" link :disabled="!loaded" @click="skip">跳过向导</el-button>
    </div>

    <el-card v-if="finished" shadow="never" class="finish-card" data-testid="onboarding-finished">
      <div class="finish-icon"><el-icon><CircleCheckFilled /></el-icon></div>
      <h2>初始化完成！</h2>
      <p class="desc">开始使用排课系统吧。需要复习时，可以在「设置中心 → 初始化向导」重新进入。</p>
      <el-button type="primary" size="large" @click="startUsing">开始使用</el-button>
    </el-card>

    <template v-else>
      <el-card shadow="never" class="steps-card">
        <el-steps :active="currentStep" finish-status="success" simple>
          <el-step v-for="step in steps" :key="step.key" :title="step.title" />
        </el-steps>
      </el-card>

      <el-card shadow="never" class="step-card">
        <div class="step-head">
          <div>
            <h2>{{ current.title }}</h2>
            <p class="desc">{{ current.description }}</p>
          </div>
          <div class="step-side">
            <el-tag v-if="isDone(current.key)" type="success" effect="light">已完成</el-tag>
            <el-tag v-else type="info" effect="light">待完成</el-tag>
            <router-link class="help-link" :to="helpLink(WIZARD_HELP_SLUG, current.helpAnchor)">
              查看帮助
            </router-link>
          </div>
        </div>

        <el-alert
          v-if="hintFor(current.key)"
          :title="hintFor(current.key)!.message"
          :type="isDone(current.key) ? 'success' : 'info'"
          :closable="false"
          show-icon
          class="hint"
        />

        <div class="actions">
          <el-button @click="router.push(current.route)">前往设置</el-button>
          <el-button v-if="currentStep > 0" @click="goTo(currentStep - 1)">上一步</el-button>
          <el-button type="primary" :loading="!loaded" @click="completeStep">
            {{ currentStep >= steps.length - 1 ? '完成向导' : '标记完成并继续' }}
          </el-button>
        </div>
      </el-card>

      <el-card shadow="never">
        <h3>初始化进度</h3>
        <div class="progress-list">
          <div v-for="(step, index) in steps" :key="step.key" class="progress-item" @click="goTo(index)">
            <el-icon v-if="isDone(step.key)" class="done"><CircleCheck /></el-icon>
            <el-icon v-else class="pending"><Clock /></el-icon>
            <span :class="{ done: isDone(step.key) }">{{ index + 1 }}. {{ step.title }}</span>
            <el-tag v-if="index === currentStep" size="small" type="primary" effect="plain">进行中</el-tag>
          </div>
        </div>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { CircleCheck, CircleCheckFilled, Clock } from '@element-plus/icons-vue'
import { http } from '@/api/http'
import { helpLink } from '@/help/links'
import { WIZARD_HELP_SLUG, WIZARD_STEPS, wizardProgressLabel } from '@/onboarding/wizardSteps'

interface Hint {
  key: string
  label: string
  message: string
  route: string
  done: boolean
}

interface Status {
  showWizard: boolean
  skipped: boolean
  hasProgress: boolean
  hints: Hint[]
}

interface Progress {
  currentStep: number
  completedSteps: number[]
  data: Record<string, unknown>
  skipped: boolean
}

const router = useRouter()
const steps = WIZARD_STEPS

const currentStep = ref(0)
const completedSteps = ref<number[]>([])
const hints = ref<Hint[]>([])
const data = ref<Record<string, unknown>>({})
/** Completion stays on this page; the celebration card replaces the steps until 开始使用. */
const finished = ref(false)
/**
 * Actions stay disabled until the saved progress has landed: otherwise a click
 * that races the initial GET would be overwritten by the stale response.
 */
const loaded = ref(false)

const current = computed(() => steps[Math.min(currentStep.value, steps.length - 1)])
const progressLabel = computed(() => wizardProgressLabel(currentStep.value))

function hintFor(key: string) {
  return hints.value.find((hint) => hint.key === key)
}

function isDone(key: string) {
  if (key === 'school') return Boolean(data.value.schoolName) || completedSteps.value.includes(0)
  if (key === 'constraints') return true
  const hint = hintFor(key)
  return hint ? hint.done : completedSteps.value.includes(steps.findIndex((step) => step.key === key))
}

async function load() {
  const [progressRes, statusRes] = await Promise.all([
    http.get('/onboarding'),
    http.get('/onboarding/status'),
  ])
  const progress: Progress = progressRes.data
  const status: Status = statusRes.data
  currentStep.value = Math.min(progress.currentStep ?? 0, steps.length - 1)
  completedSteps.value = progress.completedSteps ?? []
  data.value = progress.data ?? {}
  hints.value = status.hints ?? []
  loaded.value = true
}

async function persist(nextStep: number, completed: number[], skipped = false) {
  await http.put('/onboarding', {
    currentStep: nextStep,
    completedSteps: completed,
    data: data.value,
    skipped,
  })
}

async function goTo(index: number) {
  currentStep.value = Math.max(0, Math.min(index, steps.length - 1))
  await persist(currentStep.value, completedSteps.value)
}

async function completeStep() {
  if (!completedSteps.value.includes(currentStep.value)) {
    completedSteps.value = [...completedSteps.value, currentStep.value]
  }
  if (currentStep.value >= steps.length - 1) {
    // skipped=true is the persisted "don't show the wizard again" marker.
    await persist(steps.length, completedSteps.value, true)
    finished.value = true
    return
  }
  currentStep.value += 1
  await persist(currentStep.value, completedSteps.value)
}

function startUsing() {
  router.push('/')
}

async function skip() {
  try {
    await ElMessageBox.confirm(
      '确定跳过初始化向导吗？您可以随时在设置中重新进入',
      '跳过初始化向导',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  await persist(currentStep.value, completedSteps.value, true)
  router.push('/')
}

onMounted(load)
</script>

<style scoped>
.onboarding-page {
  max-width: 900px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}
h1 {
  margin: 0 0 6px;
  font-size: 26px;
}
.subtitle {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 14px;
}
.progress {
  margin: 10px 0 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-color-primary);
}
.steps-card,
.step-card {
  border: none;
}
.step-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.step-side {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.help-link {
  font-size: 13px;
  color: var(--el-color-primary);
  text-decoration: none;
}
.help-link:hover {
  text-decoration: underline;
}
h2 {
  margin: 0 0 6px;
  font-size: 20px;
}
.desc {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 14px;
  line-height: 1.7;
}
.hint {
  margin-top: 16px;
}
.actions {
  display: flex;
  gap: 8px;
  margin-top: 20px;
}
h3 {
  margin: 0 0 12px;
  font-size: 15px;
}
.progress-list {
  display: grid;
  gap: 8px;
}
.progress-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 4px;
  cursor: pointer;
  border-radius: 6px;
  font-size: 14px;
}
.progress-item:hover {
  background: #f6f7fb;
}
.done {
  color: #52c41a;
}
.pending {
  color: #b0b7c3;
}
.progress-item span.done {
  color: #6b7280;
  text-decoration: line-through;
}
.finish-card {
  border: none;
  text-align: center;
  padding: 24px 0;
}
.finish-icon {
  font-size: 64px;
  color: #52c41a;
  animation: finish-pop 0.5s ease-out both;
}
.finish-card h2 {
  margin: 12px 0 8px;
  font-size: 24px;
}
.finish-card .desc {
  margin-bottom: 24px;
}
@keyframes finish-pop {
  0% {
    transform: scale(0.6);
    opacity: 0;
  }
  60% {
    transform: scale(1.08);
    opacity: 1;
  }
  100% {
    transform: scale(1);
  }
}
</style>
