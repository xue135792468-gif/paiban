<template>
  <div class="bind-page">
    <div class="brand-panel">
      <div class="brand-mark">排</div>
      <h1>排课系统</h1>
      <p>飞书账号绑定</p>
      <ul>
        <li>核对姓名与工号完成身份验证</li>
        <li>绑定后从飞书工作台即可免登</li>
        <li>历史数据与账号权限保持不变</li>
      </ul>
    </div>

    <div class="form-panel">
      <el-card class="bind-card" shadow="never">
        <h2>绑定飞书账号</h2>
        <p class="hint">首次使用飞书进入系统，请填写姓名，并提供工号或手机号。</p>

        <el-alert
          v-if="tokenMissing"
          title="绑定链接已失效，请重新从飞书进入"
          type="error"
          :closable="false"
          show-icon
        />

        <el-form v-else ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
          <el-form-item label="姓名" prop="name">
            <el-input v-model="form.name" placeholder="请输入与学校档案一致的姓名" size="large" />
          </el-form-item>
          <el-form-item label="工号" prop="employeeNo">
            <el-input v-model="form.employeeNo" placeholder="请输入工号（与手机号至少填一项）" size="large" />
          </el-form-item>
          <el-form-item label="手机号" prop="phone">
            <el-input v-model="form.phone" placeholder="请输入手机号（与工号至少填一项）" size="large" />
          </el-form-item>
          <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="error" />
          <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
            完成绑定
          </el-button>
        </el-form>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { http } from '@/api/http'
import { useLoginLanding } from '@/composables/useLoginLanding'
import { serverMessage } from '@/api/errors'

const route = useRoute()
const auth = useAuthStore()
const { landAfterLogin } = useLoginLanding()

const token = computed(() => (route.query.token as string) || '')
const tokenMissing = computed(() => !token.value)

const formRef = ref<FormInstance>()
const form = reactive({ name: '', employeeNo: '', phone: '' })
const rules: FormRules = {
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
}
const loading = ref(false)
const error = ref('')

async function submit() {
  if (!token.value) {
    error.value = '绑定链接已失效，请重新从飞书进入'
    return
  }
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  const employeeNo = form.employeeNo.trim()
  const phone = form.phone.trim()
  if (!employeeNo && !phone) {
    error.value = '请至少填写工号或手机号'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const payload: Record<string, string> = { bindToken: token.value, name: form.name.trim() }
    if (employeeNo) payload.employeeNo = employeeNo
    if (phone) payload.phone = phone
    const { data } = await http.post('/auth/feishu/bind', payload)
    auth.setSession(data.token, data.user)
    ElMessage.success('绑定成功')
    await landAfterLogin()
  } catch (e: any) {
    error.value = serverMessage(e, '绑定失败，请联系教务管理员')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.bind-page {
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
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  font-weight: 700;
  margin-bottom: 20px;
}
.brand-panel h1 {
  margin: 0 0 10px;
  font-size: 30px;
}
.brand-panel p {
  margin: 0 0 18px;
  opacity: 0.9;
}
.brand-panel ul {
  margin: 0;
  padding-left: 18px;
  line-height: 1.9;
  opacity: 0.9;
}
.form-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8vh 6vw;
  background: #f7f8fc;
}
.bind-card {
  width: 100%;
  max-width: 400px;
  border-radius: 14px;
}
.bind-card h2 {
  margin: 0 0 6px;
  font-size: 22px;
}
.hint {
  margin: 0 0 20px;
  color: var(--paiban-muted);
  font-size: 13px;
  line-height: 1.6;
}
.error {
  margin-bottom: 14px;
}
.submit {
  width: 100%;
}
@media (max-width: 900px) {
  .bind-page {
    grid-template-columns: 1fr;
  }
  .brand-panel {
    display: none;
  }
}
</style>
