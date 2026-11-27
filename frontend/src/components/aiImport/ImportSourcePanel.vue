<template>
  <el-alert
    v-if="inputMode === 'excel'"
    data-testid="workbook-mode-hint"
    type="info"
    show-icon
    :closable="false"
    title="默认「整本工作簿」：Excel / CSV 里的多张表会分别识别（课表、教师名单、课程清单、使用说明）。只想导入某一类时，再点选下面的目标。"
  />

  <el-card shadow="never">
    <template #header><span class="card-title">选择导入目标</span></template>
    <ImportTargetPicker
      :targets="visibleTargets"
      :selected="selectedTarget"
      @select="(code: string) => emit('select-target', code)"
    />
  </el-card>

  <el-card shadow="never">
    <el-tabs v-model="inputMode">
      <el-tab-pane label="Excel / CSV" name="excel">
        <ImportFileUpload
          :file-error="fileError"
          :validate="spreadsheetFileError"
          hint="一个文件里有多少张表都可以：课表、班级课表、教师名单、使用说明会分别识别。"
          @change="
            (file: File | null, error: string) =>
              emit('excel-change', { raw: file ?? undefined, name: file?.name ?? '' } as UploadFile, error)
          "
        />
      </el-tab-pane>

      <el-tab-pane label="粘贴文本" name="text">
        <el-input v-model="text" type="textarea" :rows="9" :placeholder="currentTarget.textPlaceholder" />
        <div class="hint">可以直接从微信聊天记录、通知里复制粘贴。</div>
      </el-tab-pane>

      <el-tab-pane label="上传图片" name="image" :disabled="!visionCapable || selectedTarget !== 'teacher'">
        <template #label>
          <span data-testid="image-tab">上传图片</span>
        </template>
        <ImportFileUpload
          accept=".png,.jpg,.jpeg,.webp"
          :file-error="imageError"
          hint="需要所选模型支持视觉（vision_capable）。"
          dragging-text="把照片拖到这里"
          @change="
            (file: File | null, error: string) =>
              emit('image-change', { raw: file ?? undefined, name: file?.name ?? '' } as UploadFile, error)
          "
        />
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
      <el-button
        type="primary"
        :loading="parsing"
        :disabled="!configured || !canParse"
        @click="emit('parse')"
      >
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
import ImportTargetPicker from './ImportTargetPicker.vue'
import ImportFileUpload from '@/components/imports/ImportFileUpload.vue'
import { spreadsheetFileError } from '@/components/imports/fileValidation'
import { IMPORT_TARGETS, targetCard } from './importTargets'

const props = defineProps<{
  configured: boolean
  visionCapable: boolean
  selectedTarget: string
  canParse: boolean
  parsing: boolean
  selectedFileName: string
  imageFileName: string
  fileError?: string
  imageError?: string
}>()

const inputMode = defineModel<'excel' | 'text' | 'image'>('inputMode', { required: true })
const text = defineModel<string>('text', { required: true })

const emit = defineEmits<{
  (e: 'select-target', code: string): void
  (e: 'excel-change', file: UploadFile, error: string): void
  (e: 'image-change', file: UploadFile, error: string): void
  (e: 'parse'): void
}>()

/** The workbook target only reads Excel/CSV; text and images keep a single target. */
const visibleTargets = computed(() =>
  IMPORT_TARGETS.filter((target) => target.available).filter(
    (target) => inputMode.value === 'excel' || target.code !== 'workbook',
  ),
)
const currentTarget = computed(() => targetCard(props.selectedTarget) ?? targetCard('teacher')!)
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
