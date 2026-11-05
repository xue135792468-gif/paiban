<template>
  <span class="lifecycle-cell">
    <el-button link type="warning" :loading="toggling" :disabled="disabled" @click="toggle">
      {{ row.active === false ? '启用' : '停用' }}
    </el-button>
    <el-button link type="danger" :disabled="disabled" @click="deleteVisible = true">删除</el-button>
    <DeleteImpactDialog
      v-model="deleteVisible"
      :entity-type="entityType"
      :entity-id="row.id"
      :entity-label="resolvedLabel"
      @updated="onUpdated"
    />
  </span>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { serverMessage } from '@/api/errors'
import { disableEntity, enableEntity, type LifecycleEntityType } from '@/api/lifecycle'
import DeleteImpactDialog from './DeleteImpactDialog.vue'

interface RowLike {
  id: number
  name?: string | null
  active?: boolean
}

const props = defineProps<{
  entityType: LifecycleEntityType
  row: RowLike
  label?: string
  disabled?: boolean
}>()

const emit = defineEmits<{ (event: 'updated'): void }>()

const deleteVisible = ref(false)
const toggling = ref(false)

const resolvedLabel = computed(() => props.label ?? props.row.name ?? String(props.row.id))

async function toggle() {
  toggling.value = true
  try {
    if (props.row.active === false) {
      await enableEntity(props.entityType, props.row.id)
      ElMessage.success(`已启用「${resolvedLabel.value}」`)
    } else {
      await disableEntity(props.entityType, props.row.id)
      ElMessage.success(`已停用「${resolvedLabel.value}」`)
    }
    emit('updated')
  } catch (e: unknown) {
    ElMessage.error(serverMessage(e, '操作失败'))
  } finally {
    toggling.value = false
  }
}

function onUpdated() {
  emit('updated')
}
</script>

<style scoped>
.lifecycle-cell {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
</style>
