<template>
  <div class="home">
    <el-card shadow="never" class="welcome">
      <div class="welcome-row">
        <div>
          <h1>{{ greeting }}，{{ auth.displayName }}</h1>
          <p class="subtitle">{{ today }} · {{ roleLabel }}</p>
        </div>
        <el-button v-if="auth.hasPermission('scheduling:run')" type="primary" @click="router.push('/admin/scheduling')">
          去排课
        </el-button>
      </div>
    </el-card>

    <el-card v-if="canManageSetup" v-loading="loading" shadow="never" class="hints">
      <template #header>
        <div class="card-head">
          <span>还差什么</span>
          <el-tag v-if="pending.length === 0" type="success" effect="light">初始化已完成</el-tag>
        </div>
      </template>
      <div v-if="pending.length === 0" class="all-done">
        基础数据已经齐全，可以录入授课任务并开始自动排课了。
      </div>
      <div v-for="hint in pending" :key="hint.key" class="hint-row">
        <div>
          <div class="hint-label">{{ hint.label }}</div>
          <div class="hint-message">{{ hint.message }}</div>
        </div>
        <el-button link type="primary" @click="router.push(hint.route)">前往</el-button>
      </div>
    </el-card>

    <div class="quick-grid">
      <el-card
        v-for="card in quickLinks"
        :key="card.path"
        shadow="hover"
        class="quick-card"
        @click="router.push(card.path)"
      >
        <el-icon class="quick-icon"><component :is="card.icon" /></el-icon>
        <div class="quick-title">{{ card.title }}</div>
        <div class="quick-desc">{{ card.desc }}</div>
      </el-card>
    </div>

    <div class="health">
      <el-tag :type="health === 'UP' ? 'success' : 'danger'" size="small">
        后端服务 {{ health || '检测中' }}
      </el-tag>
      <span class="health-note">排课系统 · 数据不出校</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Calendar, ChatDotRound, List, Timer } from '@element-plus/icons-vue'
import { http } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

interface Hint {
  key: string
  label: string
  message: string
  route: string
  done: boolean
}

const auth = useAuthStore()
const router = useRouter()
const hints = ref<Hint[]>([])
const loading = ref(false)
const health = ref('')
const canManageSetup = computed(() => auth.hasPermission('foundation:manage'))

const pending = computed(() => hints.value.filter((hint) => !hint.done))

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '早上好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const today = computed(() =>
  new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }),
)

const roleLabel = computed(() => {
  if (auth.hasRole('admin')) return '教务管理员'
  if (auth.hasRole('head_teacher')) return '班主任'
  if (auth.hasRole('teacher')) return '教师'
  return ''
})

const quickLinks = computed(() => {
  const links = []
  if (auth.hasPermission('scheduling:run')) {
    links.push({ title: '自动排课', desc: '提交求解并微调草稿', path: '/admin/scheduling', icon: Timer })
  }
  links.push({ title: '课表查看', desc: '班级 / 教师 / 教室 / 全校', path: '/timetable', icon: Calendar })
  if (auth.hasPermission('substitution:submit')) {
    links.push({ title: '调代课', desc: '提交申请与审批', path: '/substitutions', icon: ChatDotRound })
  }
  if (auth.hasAnyPermission(['assignment:manage', 'assignment:view:own'])) {
    links.push({ title: '授课任务', desc: '录入班级课程与教师', path: '/admin/assignments', icon: List })
  }
  return links
})

onMounted(async () => {
  http
    .get('/health')
    .then(({ data }) => {
      health.value = data.status
    })
    .catch(() => {
      health.value = 'DOWN'
    })
  if (!canManageSetup.value) {
    return
  }
  loading.value = true
  try {
    const { data } = await http.get('/onboarding/status')
    hints.value = data.hints ?? []
  } catch {
    hints.value = []
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.welcome {
  border: none;
}
.welcome-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
h1 {
  margin: 0 0 6px;
  font-size: 24px;
}
.subtitle {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 13px;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
.all-done {
  color: #2f855a;
  font-size: 14px;
  padding: 8px 0;
}
.hint-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px dashed #eef0f5;
}
.hint-row:last-child {
  border-bottom: none;
}
.hint-label {
  font-weight: 600;
  font-size: 14px;
}
.hint-message {
  color: var(--paiban-muted);
  font-size: 12px;
  margin-top: 2px;
}
.quick-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 14px;
}
.quick-card {
  cursor: pointer;
  border: none;
}
.quick-icon {
  font-size: 26px;
  color: #4f6ef7;
}
.quick-title {
  font-weight: 600;
  margin-top: 10px;
}
.quick-desc {
  color: var(--paiban-muted);
  font-size: 12px;
  margin-top: 4px;
}
.health {
  display: flex;
  align-items: center;
  gap: 10px;
  justify-content: flex-end;
}
.health-note {
  color: var(--paiban-muted);
  font-size: 12px;
}
</style>
