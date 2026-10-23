<template>
  <el-card shadow="never">
    <template #header><span class="card-title">选择导入目标</span></template>
    <ImportTargetPicker :targets="IMPORT_TARGETS" :selected="selectedTarget" @select="emitSelect" />
  </el-card>

  <el-card shadow="never">
    <el-tabs v-model="inputMode">
      <el-tab-pane label="Excel / CSV" name="excel">
        <el-upload
          drag
          :auto-upload="false"
          :show-file-list="false"
          accept=".xlsx,.xls,.csv"
          :on-change="(file: UploadFile) => emit('excel-change', file)"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">把文件拖到这里，或<em>点击选择</em></div>
          <template #tip>
            <div class="el-upload__tip">{{ currentTarget.excelHint }}</div>
          </template>
        </el-upload>
        <div v-if="selectedFileName" class="file-hint">已选择：{{ selectedFileName }}</div>
      </el-tab-pane>

      <el-tab-pane label="粘贴文本" name="text">
        <el-input
          v-model="text"
          type="textarea"
          :rows="9"
          :placeholder="currentTarget.textPlaceholder"
        />
        <div class="hint">可以直接从微信聊天记录、通知里复制粘贴。</div>
      </el-tab-pane>

      <el-tab-pane
        label="上传图片"
        name="image"
        :disabled="!visionCapable || selectedTarget !== 'teacher'"
      >
        <template #label>
          <span data-testid="image-tab">上传图片</span>
        </template>
        <el-upload
          drag
          :auto-upload="false"
          :show-file-list="false"
          accept="image/png,image/jpeg,image/webp"
          :on-change="(file: UploadFile) => emit('image-change', file)"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">上传课表或名单照片，或<em>点击选择</em></div>
          <template #tip>
            <div class="el-upload__tip">需要所选模型支持视觉（vision_capable）。</div>
          </template>
        </el-upload>
        <div v-if="imageFileName" class="file-hint">已选择：{{ imageFileName }}</div>
      </el-tab-pane>
    </el-tabs>

    <el-alert
      v-if="configured && !visionCapable"
      data-testid="vision-warning"
      title="当前模型不支持图片，请在设置中换用视觉模型"
      type="warning"
      show-icon
      :closable="false"
      class="vision-warning"
    />

    <div class="parse-actions">
      <el-button type="primary" :loading="parsing" :disabled="!configured || !canParse" @click="emit('parse')">
        开始解析
      </el-button>
      <span class="hint">AI 只生成预览，点击「入库」才会写入数据。</span>
    </div>

    <el-alert
      v-if="parsing"
      data-testid="parse-progress"
      title="AI 正在解析，大表会按班分批，可能需要几分钟"
      type="info"
      show-icon
      :closable="false"
      class="parse-progress"
    />
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { UploadFile } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import ImportTargetPicker from './ImportTargetPicker.vue'
import { IMPORT_TARGETS, targetCard } from './importTargets'

const props = defineProps<{
  configured: boolean
  visionCapable: boolean
  selectedTarget: string
  canParse: boolean
  parsing: boolean
  selectedFileName: string
  imageFileName: string
}>()

const inputMode = defineModel<'excel' | 'text' | 'image'>('inputMode', { required: true })
const text = defineModel<string>('text', { required: true })

const emit = defineEmits<{
  (e: 'select-target', code: string): void
  (e: 'excel-change', file: UploadFile): void
  (e: 'image-change', file: UploadFile): void
  (e: 'parse'): void
}>()

const currentTarget = computed(() => targetCard(props.selectedTarget) ?? IMPORT_TARGETS[0])

function emitSelect(code: string) {
  emit('select-target', code)
}
</script>

<style scoped>
.card-title {
  font-weight: 600;
}

.file-hint {
  margin-top: 8px;
  color: #4b5563;
  font-size: 13px;
}

.hint {
  color: #6b7280;
  font-size: 12px;
}

.parse-actions {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 16px;
}

.parse-progress {
  margin-top: 12px;
}

.vision-warning {
  margin-top: 8px;
}
</style>
