<template>
  <div class="onboarding-page">
    <div class="header">
      <div>
        <h1>欢迎使用排课系统</h1>
        <p class="subtitle">按顺序完成下面几步，就能开始第一次自动排课。所有步骤都可以稍后回来继续。</p>
      </div>
      <el-button link @click="skip">跳过向导</el-button>
    </div>

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
        <el-tag v-if="isDone(current.key)" type="success" effect="light">已完成</el-tag>
        <el-tag v-else type="info" effect="light">待完成</el-tag>
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
        <el-button type="primary" @click="completeStep">
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
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CircleCheck, Clock } from '@element-plus/icons-vue'
import { http } from '@/api/http'

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
const steps = [
  {
    key: 'school',
    title: '学校信息',
    description: '填写学校名称并上传校徽，课表导出时会使用这些信息。',
    route: '/admin/settings',
  },
  {
    key: 'periods',
    title: '学期与作息表',
    description: '创建本学期并配置每天几节课、上午/下午/晚自习的时间。',
    route: '/admin/semesters',
  },
  {
    key: 'grades',
    title: '年级与班级',
    description: '录入年级和行政班，可下载模板后 Excel 批量导入。',
    route: '/admin/grades',
  },
  {
    key: 'teachers',
    title: '教师',
    description: '录入教师名单，可 Excel 批量导入；后续用于授课任务与登录账号。',
    route: '/admin/teachers',
  },
  {
    key: 'rooms',
    title: '教室',
    description: '录入普通教室、实验室、操场等场地及容量。',
    route: '/admin/rooms',
  },
  {
    key: 'courses',
    title: '课程',
    description: '维护课程目录与元数据（是否允许连堂、是否必须室外）。',
    route: '/admin/courses',
  },
  {
    key: 'assignments',
    title: '授课任务',
    description: '录入「班级 + 课程 + 每周节数 + 教师」，这是排课的输入。',
    route: '/admin/assignments',
  },
  {
    key: 'constraints',
    title: '约束规则',
    description: '勾选启用的排课约束并调整权重，默认已启用教师/班级/教室不冲突。',
    route: '/admin/constraints',
  },
]

const currentStep = ref(0)
const completedSteps = ref<number[]>([])
const hints = ref<Hint[]>([])
const data = ref<Record<string, unknown>>({})

const current = computed(() => steps[Math.min(currentStep.value, steps.length - 1)])

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
    await persist(steps.length, completedSteps.value, true)
    ElMessage.success('初始化完成，开始排课吧')
    router.push('/')
    return
  }
  currentStep.value += 1
  await persist(currentStep.value, completedSteps.value)
}

async function skip() {
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
</style>
