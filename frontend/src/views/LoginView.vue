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
          <el-alert v-if="feishuNotice" :title="feishuNotice" type="warning" :closable="false" show-icon class="error" />
          <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="error" />
          <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
            登录
          </el-button>
        </el-form>
        <p class="footer-hint">首次部署请使用初始化管理员账号，并尽快修改密码</p>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { http } from '@/api/http'
import { isFeishuEnv, requestFeishuAuthCode } from '@/api/feishu'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}
const loading = ref(false)
const error = ref('')
const feishuNotice = ref('')

function loadDingTalkSdk(): Promise<any> {
  return new Promise((resolve, reject) => {
    const existing = (window as any).dd
    if (existing) {
      resolve(existing)
      return
    }
    const script = document.createElement('script')
    script.src = 'https://g.alicdn.com/dingding/dingtalk-jsapi/3.0.25/dingtalk.open.js'
    script.onload = () => resolve((window as any).dd)
    script.onerror = () => reject(new Error('钉钉 JSAPI 加载失败'))
    document.head.appendChild(script)
  })
}

function requestAuthCode(dd: any, corpId: string): Promise<string> {
  return new Promise((resolve, reject) => {
    if (typeof dd.getAuthCode === 'function') {
      dd.getAuthCode({ corpId })
        .then((result: any) => resolve(result.authCode ?? result.code))
        .catch(reject)
      return
    }
    dd.runtime.permission.requestAuthCode({
      corpId,
      onSuccess: (result: any) => resolve(result.code),
      onFail: reject,
    })
  })
}

/** Inside the DingTalk workspace the same SPA logs in through the H5免登 flow. */
async function tryDingTalkLogin() {
  if (!/DingTalk/i.test(navigator.userAgent)) return
  try {
    const { data: config } = await http.get('/auth/dingtalk/config')
    if (!config?.enabled || !config.corpId) return
    const dd = await loadDingTalkSdk()
    await new Promise<void>((resolve, reject) => {
      dd.ready(async () => {
        try {
          const authCode = await requestAuthCode(dd, config.corpId)
          const { data } = await http.post('/auth/dingtalk', { authCode })
          auth.setSession(data.token, data.user)
          ElMessage.success('钉钉免登成功')
          router.replace('/')
          resolve()
        } catch (ex) {
          reject(ex)
        }
      })
    })
  } catch {
    // stay on the password form
  }
}

/** Same landing rules as password login: first-run onboarding, then the redirect target. */
async function landAfterLogin() {
  const redirect = (route.query.redirect as string) || '/'
  if (redirect === '/') {
    try {
      const { data } = await http.get('/onboarding/status')
      if (data.showWizard && auth.hasPermission('onboarding:manage')) {
        router.replace('/onboarding')
        return
      }
    } catch {
      // fall through to the normal landing page
    }
  }
  router.replace(redirect)
}

/** Feishu injects `window.tt` inside its H5 container; any failure keeps the password form. */
async function tryFeishuLogin() {
  try {
    const { data: config } = await http.get('/auth/feishu/config')
    if (!config?.enabled) return
    const code = await requestFeishuAuthCode(config.appId ?? '')
    const result = await auth.feishuLogin(code)
    if (result.status === 'ok') {
      ElMessage.success('飞书免登成功')
      await landAfterLogin()
      return
    }
    if (result.status === 'binding_required') {
      if (result.bindToken) {
        router.replace({ path: '/bind', query: { token: result.bindToken } })
        return
      }
      feishuNotice.value = '账号未绑定，请联系教务管理员'
    }
  } catch {
    // stay on the password form
  }
}

onMounted(async () => {
  if (isFeishuEnv()) {
    await tryFeishuLogin()
    return
  }
  await tryDingTalkLogin()
})

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  error.value = ''
  try {
    await auth.login(form.username, form.password)
    await landAfterLogin()
  } catch (e: any) {
    error.value = e?.response?.data?.message ?? '登录失败，请稍后重试'
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
