<template>
  <el-card shadow="never">
    <template #header><span class="card-title">学校信息</span></template>
    <el-form label-width="110px" class="form">
      <el-form-item label="学校名称">
        <el-input v-model="form['school.name']" placeholder="用于课表导出与 PDF 页眉" />
      </el-form-item>
      <el-form-item label="学校 Logo">
        <div class="logo-row">
          <el-upload
            :auto-upload="false"
            :show-file-list="false"
            accept="image/png,image/jpeg"
            :on-change="uploadLogo"
          >
            <el-button :loading="uploading">上传图片</el-button>
          </el-upload>
          <span class="logo-path">{{ logoName || '未上传' }}</span>
        </div>
      </el-form-item>
      <el-collapse class="advanced">
        <el-collapse-item title="高级设置（外部访问地址）" name="advanced">
          <el-form-item label="外部访问地址">
            <el-input
              v-model="form['public.base-url']"
              placeholder="如 https://paiban.school.cn，用于通知里的跳转链接"
            />
          </el-form-item>
        </el-collapse-item>
      </el-collapse>
      <el-button type="primary" :loading="saving" data-testid="school-save" @click="save('学校信息已保存')">
        保存学校信息
      </el-button>
    </el-form>
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, type UploadFile } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import { useSettingsSection } from './useSettingsSection'

const form = defineModel<Record<string, string>>({ required: true })
const uploading = ref(false)
const { saving, save } = useSettingsSection(form, ['school.name', 'public.base-url'])
const logoName = computed(() => form.value['school.logo-path']?.split(/[\\/]/).pop() ?? '')

async function uploadLogo(upload: UploadFile) {
  if (!upload.raw) return
  uploading.value = true
  try {
    const body = new FormData()
    body.append('file', upload.raw)
    const { data } = await http.post<Record<string, string>>('/settings/logo', body)
    Object.assign(form.value, data)
    ElMessage.success('Logo 已上传')
  } catch (error) {
    ElMessage.error(serverMessage(error, '上传失败'))
  } finally {
    uploading.value = false
  }
}
</script>

<style scoped>
.card-title {
  font-weight: 600;
}
.form {
  max-width: 720px;
}
.logo-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.logo-path {
  color: var(--paiban-muted);
  font-size: 12px;
}
.advanced {
  margin-bottom: 14px;
  border: 0;
}
.advanced :deep(.el-collapse-item__header),
.advanced :deep(.el-collapse-item__wrap) {
  border-bottom: 0;
}
</style>
