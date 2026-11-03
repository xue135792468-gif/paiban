<template>
  <el-dialog
    :model-value="modelValue"
    title="基于当前草稿继续优化"
    width="520px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div data-testid="optimization-launch-dialog" class="launch-body">
      <p class="source-line">
        来源草稿：<strong>{{ sourceDraftName }}</strong>
      </p>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="会生成一份新草稿，原稿保留不变"
      />
      <p class="hint">
        锁定 {{ lockedCount }} 节会继承到新草稿；未锁定的当前安排也作为初始解，不会改用已发布基线。
      </p>
      <el-alert
        v-if="allLocked"
        data-testid="optimization-all-locked"
        type="warning"
        :closable="false"
        show-icon
        title="全部课节已锁定，没有可优化的课程"
      />

      <el-form label-width="110px" class="launch-form" @submit.prevent>
        <el-form-item label="草稿名称">
          <el-input v-model="draftName" data-testid="optimization-draft-name" />
        </el-form-item>
        <el-form-item label="最长求解秒数">
          <el-input-number
            v-model="maxSeconds"
            data-testid="optimization-max-seconds"
            :min="1"
            :max="600"
            :step="1"
            controls-position="right"
          />
        </el-form-item>
      </el-form>

      <p class="hint">任务完成不等于可发布；发布以新草稿的整稿校验为准。</p>
      <el-alert
        v-if="submitError"
        data-testid="optimization-submit-error"
        type="error"
        :closable="false"
        show-icon
        :title="submitError"
      />
    </div>
    <template #footer>
      <el-button :disabled="starting" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        data-testid="optimization-launch-confirm"
        :loading="starting"
        :disabled="!canSubmit"
        @click="submit"
      >
        开始优化
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    sourceDraftName: string
    sourceDraftId: number
    lockedCount: number
    lessonCount: number
    maxSecondsDefault?: number
    starting?: boolean
    submitError?: string | null
  }>(),
  {
    maxSecondsDefault: 60,
    starting: false,
    submitError: null,
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'submit', payload: { draftName: string; maxSeconds: number }): void
}>()

const draftName = ref('')
const maxSeconds = ref(props.maxSecondsDefault)

const defaultName = computed(() => `基于「${props.sourceDraftName}」优化`)
const allLocked = computed(() => props.lessonCount > 0 && props.lockedCount >= props.lessonCount)
const canSubmit = computed(
  () =>
    !allLocked.value &&
    !props.starting &&
    draftName.value.trim().length > 0 &&
    maxSeconds.value >= 1 &&
    maxSeconds.value <= 600,
)

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    draftName.value = defaultName.value
    maxSeconds.value = props.maxSecondsDefault
  },
  { immediate: true },
)

function submit() {
  if (!canSubmit.value) return
  emit('submit', { draftName: draftName.value.trim(), maxSeconds: maxSeconds.value })
}
</script>

<style scoped>
.launch-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.source-line {
  margin: 0;
  font-size: 13px;
}
.hint {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 12px;
}
.launch-form {
  margin-top: 4px;
}
</style>
