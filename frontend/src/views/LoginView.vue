<template>
  <div class="login-page">
    <div class="brand-panel">
      <div class="brand-mark">排</div>
      <h1>排课系统</h1>
      <p>学期初排课 · 学期内调代 · 一键发布</p>
      <ul>
        <li>自动排课与手工微调</li>
        <li>冲突实时校验</li>
        <li>调代流程留痕</li>
      </ul>
    </div>

    <div class="form-panel">
      <el-card class="login-card" shadow="never">
        <h2>登录</h2>
        <div
          v-if="isDingTalkPending"
          class="dingtalk-notice"
          data-testid="dingtalk-login-pending"
        >
          <el-alert
            title="正在通过钉钉免登"
            description="正在向钉钉确认你的身份，请稍候。若长时间没有反应，可以改用管理员密码登录。"
            type="info"
            :closable="false"
            show-icon
            class="notice-alert"
          />
          <div class="notice-actions">
            <el-button
              link
              type="primary"
              data-testid="switch-password-login"
              @click="switchToPasswordMode"
            >
              我是管理员，用密码登录
            </el-button>
          </div>
        </div>
        <div
          v-else-if="dingtalkNotice && !isPasswordMode"
          class="dingtalk-notice"
          data-testid="dingtalk-login-notice"
          :data-reason-code="dingtalkNotice.code"
        >
          <el-alert
            :title="dingtalkNotice.title"
            :description="dingtalkNotice.description"
            type="warning"
            :closable="false"
            show-icon
            class="notice-alert"
          />
          <div class="notice-actions">
            <el-button
              link
              type="primary"
              data-testid="switch-password-login"
              @click="switchToPasswordMode"
            >
              我是管理员，用密码登录
            </el-button>
          </div>
        </div>
        <template v-else>
          <p class="hint">使用学校分配的账号登录</p>
          <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" placeholder="请输入用户名" size="large" autocomplete="username" />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="form.password"
                type="password"
                placeholder="请输入密码"
                size="large"
                show-password
                autocomplete="current-password"
                @keyup.enter="submit"
              />
            </el-form-item>
            <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="error" />
            <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
              登录
            </el-button>
          </el-form>
          <p class="footer-hint">首次部署请使用初始化管理员账号，并尽快修改密码</p>
        </template>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { http } from '@/api/http'
import { useLoginLanding } from '@/composables/useLoginLanding'
import { serverMessage } from '@/api/errors'
import {
  extractDingTalkErrorCode,
  resolveDingTalkLoginNotice,
  type DingTalkLoginNotice,
} from '@/composables/useDingTalkLoginNotice'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const { landAfterLogin } = useLoginLanding()

const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}
const loading = ref(false)
const error = ref('')
const dingtalkNotice = ref<DingTalkLoginNotice | null>(null)
const isPasswordMode = computed(() => route.query.mode === 'password')

const isDingTalkPending = ref(false)
let autoLoginAttempt = 0

const DINGTALK_SDK_TIMEOUT_MS = 10000
const DINGTALK_READY_TIMEOUT_MS = 5000
const DINGTALK_AUTH_CODE_TIMEOUT_MS = 10000

function loadDingTalkSdk(): Promise<any> {
  return new Promise((resolve, reject) => {
    const existing = (window as any).dd
    if (existing) {
      resolve(existing)
      return
    }
    let settled = false
    const timer = setTimeout(() => {
      if (settled) return
      settled = true
      reject(new Error('钉钉 JSAPI 加载超时'))
    }, DINGTALK_SDK_TIMEOUT_MS)
    const script = document.createElement('script')
    script.src = 'https://g.alicdn.com/dingding/dingtalk-jsapi/3.0.25/dingtalk.open.js'
    script.onload = () => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      const loaded = (window as any).dd
      if (loaded) resolve(loaded)
      else reject(new Error('钉钉 JSAPI 未初始化'))
    }
    script.onerror = () => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      reject(new Error('钉钉 JSAPI 加载失败'))
    }
    document.head.appendChild(script)
  })
}

function requestAuthCode(dd: any, corpId: string): Promise<string> {
  return new Promise((resolve, reject) => {
    let settled = false
    const timer = setTimeout(() => {
      if (settled) return
      settled = true
      reject(new Error('获取钉钉免登授权码超时'))
    }, DINGTALK_AUTH_CODE_TIMEOUT_MS)
    const finishResolve = (code: unknown) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      if (typeof code === 'string' && code) resolve(code)
      else reject(new Error('未获取到钉钉免登授权码'))
    }
    const finishReject = (err: unknown) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      reject(err ?? new Error('获取钉钉免登授权码失败'))
    }
    if (typeof dd.getAuthCode === 'function') {
      dd.getAuthCode({ corpId })
        .then((result: any) => {
          finishResolve(result?.authCode ?? result?.code)
        })
        .catch(finishReject)
      return
    }
    if (!dd?.runtime?.permission?.requestAuthCode) {
      finishReject(new Error('钉钉免登接口不可用'))
      return
    }
    dd.runtime.permission.requestAuthCode({
      corpId,
      onSuccess: (result: any) => {
        finishResolve(result?.code)
      },
      onFail: finishReject,
    })
  })
}

function waitForDingTalkReady(dd: any): Promise<void> {
  return new Promise((resolve, reject) => {
    if (!dd || typeof dd.ready !== 'function') {
      reject(new Error('钉钉容器未就绪'))
      return
    }
    let settled = false
    const timer = setTimeout(() => {
      if (settled) return
      settled = true
      reject(new Error('钉钉免登准备超时'))
    }, DINGTALK_READY_TIMEOUT_MS)
    if (typeof dd.error === 'function') {
      dd.error((err: unknown) => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        reject(err ?? new Error('钉钉容器初始化失败'))
      })
    }
    dd.ready(() => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolve()
    })
  })
}

function invalidateAutoLogin() {
  // 每次失效都推进令牌：所有进行中的自动登录阶段在此之后一律视为过期，
  // 尤其不能在 auth.setSession / landAfterLogin 之前放行迟到的钉钉响应。
  autoLoginAttempt += 1
  isDingTalkPending.value = false
}

async function switchToPasswordMode() {
  invalidateAutoLogin()
  dingtalkNotice.value = null
  error.value = ''
  await router.replace({
    path: route.path || '/login',
    query: { ...route.query, mode: 'password' },
  })
}

/** Inside the DingTalk workspace the same SPA logs in through the H5免登 flow. */
async function tryDingTalkLogin() {
  if (isPasswordMode.value) return
  if (!/DingTalk/i.test(navigator.userAgent)) return
  autoLoginAttempt += 1
  const attempt = autoLoginAttempt
  const isActive = () => attempt === autoLoginAttempt
  isDingTalkPending.value = true
  try {
    const { data: config } = await http.get('/auth/dingtalk/config')
    if (!isActive()) return
    if (!config?.enabled || !config.corpId) {
      dingtalkNotice.value = resolveDingTalkLoginNotice('DINGTALK_DISABLED')
      return
    }
    const dd = await loadDingTalkSdk()
    if (!isActive()) return
    await waitForDingTalkReady(dd)
    if (!isActive()) return
    const authCode = await requestAuthCode(dd, config.corpId)
    if (!isActive()) return
    const { data } = await http.post('/auth/dingtalk', { authCode })
    // 每个阶段之后复核失效令牌：切密码入口、开始密码提交、组件卸载都会让这次尝试过期，
    // 迟到的钉钉响应不能在这里 setSession，更不能把已登录的密码会话再覆盖掉。
    if (!isActive()) return
    auth.setSession(data.token, data.user)
    ElMessage.success('钉钉免登成功')
    // 落地还会 await /onboarding/status：把失效判定传进去，切密码/卸载后旧落地不得再跳转
    await landAfterLogin('/', isActive)
  } catch (ex: unknown) {
    if (!isActive()) return
    dingtalkNotice.value = resolveDingTalkLoginNotice(extractDingTalkErrorCode(ex))
  } finally {
    if (isActive()) isDingTalkPending.value = false
  }
}

onMounted(async () => {
  await tryDingTalkLogin()
})

onBeforeUnmount(() => {
  invalidateAutoLogin()
})

async function submit() {
  // 开始密码提交即作废一切进行中的自动登录，避免晚到的钉钉响应覆盖密码会话
  invalidateAutoLogin()
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  error.value = ''
  try {
    await auth.login(form.username, form.password)
    await landAfterLogin()
  } catch (e: any) {
    error.value = serverMessage(e, '登录失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  min-height: 100vh;
}
.brand-panel {
  background: linear-gradient(135deg, #4f6ef7 0%, #7c8ff9 55%, #9fb0fb 100%);
  color: #fff;
  padding: 12vh 8vw;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.brand-mark {
  width: 56px;
  height: 56px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.18);
  display: grid;
  place-items: center;
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 28px;
}
.brand-panel h1 {
  font-size: 40px;
  margin: 0 0 12px;
}
.brand-panel p {
  font-size: 16px;
  opacity: 0.9;
  margin: 0 0 32px;
}
.brand-panel ul {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 14px;
}
.brand-panel li {
  position: relative;
  padding-left: 24px;
  opacity: 0.95;
}
.brand-panel li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 7px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #fff;
}
.form-panel {
  display: grid;
  place-items: center;
  padding: 24px;
  background: #fff;
}
.login-card {
  width: min(400px, 100%);
  border: none;
}
.login-card h2 {
  margin: 0 0 6px;
  font-size: 26px;
}
.hint {
  color: #6b7280;
  margin: 0 0 24px;
  font-size: 14px;
}
.error {
  margin-bottom: 16px;
}
.dingtalk-notice {
  margin-top: 12px;
}
.notice-alert {
  margin-bottom: 16px;
}
.notice-actions {
  display: flex;
  justify-content: center;
  margin-top: 8px;
}
.submit {
  width: 100%;
  margin-top: 8px;
}
.footer-hint {
  margin-top: 20px;
  color: #9ca3af;
  font-size: 12px;
  text-align: center;
}
@media (max-width: 900px) {
  .login-page {
    grid-template-columns: 1fr;
  }
  .brand-panel {
    display: none;
  }
}
</style>
