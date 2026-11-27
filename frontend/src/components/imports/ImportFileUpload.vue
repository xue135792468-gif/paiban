<template>
  <div>
    <el-upload
      drag
      :auto-upload="false"
      :show-file-list="false"
      :accept="accept"
      :on-change="selectFile"
      data-testid="manual-file-upload"
    >
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">{{ draggingText }}，或<em>点击选择</em></div>
      <template #tip>
        <div class="el-upload__tip">{{ hint }}</div>
      </template>
    </el-upload>
    <p v-if="selected" class="selected-file" data-testid="selected-import-file">已选择：{{ selected.name }}</p>
    <p v-if="error" class="file-error" role="alert" data-testid="import-file-error">{{ error }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { UploadFilled } from '@element-plus/icons-vue'
import type { UploadFile } from 'element-plus'

const props = withDefaults(
  defineProps<{
    accept?: string
    file?: File | null
    fileError?: string
    validate?: (file: File) => string | null
    draggingText?: string
    hint?: string
  }>(),
  {
    accept: '.xlsx,.xls,.csv',
    file: null,
    fileError: '',
    validate: undefined,
    draggingText: '把文件拖到这里',
    hint: '支持 .xlsx / .xls / .csv 格式的文件。',
  },
)

const emit = defineEmits<{ change: [file: File | null, error: string] }>()
const selected = ref<File | null>(props.file)
const localError = ref('')
const error = computed(() => props.fileError || localError.value)
watch(
  () => props.file,
  (value) => {
    selected.value = value
  },
)
function selectFile(upload: UploadFile) {
  const next = upload.raw ?? null
  selected.value = next
  localError.value = next ? (props.validate?.(next) ?? '') : ''
  emit('change', next, localError.value)
}
</script>

<style scoped>
.selected-file {
  margin: 8px 0 0;
  color: var(--paiban-muted);
  font-size: 13px;
}
.file-error {
  margin: 8px 0 0;
  color: var(--el-color-danger);
  font-size: 13px;
  line-height: 1.6;
}
</style>
