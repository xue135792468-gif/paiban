<template>
  <el-dialog
    :model-value="modelValue"
    title="冲突原因"
    width="520px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      v-if="conflicts.length === 0"
      title="该位置没有冲突"
      type="success"
      :closable="false"
      show-icon
    />
    <ul v-else class="conflict-list">
      <li v-for="(conflict, index) in conflicts" :key="index">{{ conflict.message }}</li>
    </ul>
    <template #footer>
      <el-button
        v-if="canRelocate && conflicts.length > 0"
        type="primary"
        data-testid="conflict-relocate"
        @click="onRelocate"
      >
        换到别的位置
      </el-button>
      <el-button data-testid="conflict-ack" @click="close">知道了</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { DraftConflict } from './types'

withDefaults(
  defineProps<{
    modelValue: boolean
    conflicts: DraftConflict[]
    canRelocate?: boolean
  }>(),
  {
    canRelocate: false,
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'relocate'): void
}>()

function close() {
  emit('update:modelValue', false)
}

function onRelocate() {
  emit('update:modelValue', false)
  emit('relocate')
}
</script>

<style scoped>
.conflict-list {
  margin: 0;
  padding-left: 20px;
  line-height: 1.9;
  color: #b43b3b;
}
</style>
