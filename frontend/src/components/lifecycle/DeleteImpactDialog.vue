<template>
  <el-dialog
    v-model="visible"
    :title="`删除${typeLabel}`"
    width="520px"
    destroy-on-close
    :append-to-body="false"
    data-testid="delete-impact-dialog"
  >
    <div v-loading="loading" class="impact-body">
      <template v-if="impact">
        <p class="summary">即将处理{{ typeLabel }}「{{ impact.label || entityLabel }}」：</p>
        <el-descriptions :column="1" border size="small" class="impact-grid">
          <el-descriptions-item label="已发布课表引用">
            {{ impact.publishedRefs }}
          </el-descriptions-item>
          <el-descriptions-item label="调代记录引用">
            {{ impact.substitutionRefs }}
          </el-descriptions-item>
          <el-descriptions-item label="课表草稿引用">
            {{ impact.draftRefs }}
          </el-descriptions-item>
          <el-descriptions-item label="授课任务">
            {{ impact.assignmentRefs }}
          </el-descriptions-item>
          <el-descriptions-item label="排课规则引用">
            {{ impact.ruleRefs }}
          </el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="!impact.canDelete"
          type="warning"
          :closable="false"
          show-icon
          title="该数据已被已发布课表或调代记录引用，只能停用。"
          class="impact-alert"
        />
        <el-alert
          v-else-if="needsCascade"
          type="warning"
          :closable="false"
          show-icon
          :title="`还有 ${cascadeText}会一起删除（可在 7 天内整批恢复）。`"
          class="impact-alert"
        />
        <el-alert
          v-else
          type="info"
          :closable="false"
          show-icon
          title="没有发现会阻止删除的引用，可以安全删除。"
          class="impact-alert"
        />

        <p class="hint">
          删除后 7 天内在「设置 → 最近删除」可以整批恢复；停用只影响新建和编辑时的下拉选项，不改变历史数据。
        </p>
      </template>
      <el-alert v-else-if="error" type="error" :closable="false" :title="error" />
    </div>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button
        v-if="impact?.canDisable"
        data-testid="delete-impact-disable"
        :loading="submitting === 'disable'"
        @click="onDisable"
      >
        改为停用
      </el-button>
      <el-button
        v-if="impact?.canDelete && !needsCascade"
        type="danger"
        data-testid="delete-impact-delete"
        :loading="submitting === 'delete'"
        @click="onDelete('DELETE')"
      >
        删除
      </el-button>
      <el-button
        v-if="impact?.canDelete && needsCascade"
        type="danger"
        data-testid="delete-impact-cascade"
        :loading="submitting === 'delete'"
        @click="onDelete('CASCADE')"
      >
        一起删除（{{ cascadeText }}）
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { serverMessage } from '@/api/errors'
import {
  LIFECYCLE_ENTITY_LABEL,
  deleteEntity,
  disableEntity,
  getDeleteImpact,
  type DeleteImpact,
  type DeleteMode,
  type LifecycleEntityType,
} from '@/api/lifecycle'

const props = defineProps<{
  modelValue: boolean
  entityType: LifecycleEntityType
  entityId: number | null
  entityLabel?: string
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'updated'): void
}>()

const impact = ref<DeleteImpact | null>(null)
const loading = ref(false)
const submitting = ref<'' | 'delete' | 'disable'>('')
const error = ref('')

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const typeLabel = computed(() => LIFECYCLE_ENTITY_LABEL[props.entityType])

const needsCascade = computed(() => {
  if (!impact.value) return false
  return impact.value.cascadeClasses > 0 || impact.value.cascadeAssignments > 0
})

const cascadeText = computed(() => {
  if (!impact.value) return ''
  const parts: string[] = []
  if (impact.value.cascadeClasses > 0) {
    parts.push(`${impact.value.cascadeClasses} 个行政班`)
  }
  if (impact.value.cascadeAssignments > 0) {
    parts.push(`${impact.value.cascadeAssignments} 条授课任务`)
  }
  return parts.join('、')
})

watch(
  () => [props.modelValue, props.entityType, props.entityId] as const,
  async ([open]) => {
    if (!open || props.entityId == null) return
    await load()
  },
  { immediate: true },
)

async function load() {
  if (props.entityId == null) return
  loading.value = true
  error.value = ''
  impact.value = null
  try {
    impact.value = await getDeleteImpact(props.entityType, props.entityId)
  } catch (e: unknown) {
    error.value = serverMessage(e, '加载删除影响失败')
  } finally {
    loading.value = false
  }
}

async function onDelete(mode: DeleteMode) {
  if (props.entityId == null) return
  submitting.value = 'delete'
  try {
    await deleteEntity(props.entityType, props.entityId, mode)
    ElMessage.success(`已删除，可在「最近删除」中恢复`)
    finish()
  } catch (e: unknown) {
    ElMessage.error(serverMessage(e, '删除失败'))
  } finally {
    submitting.value = ''
  }
}

async function onDisable() {
  if (props.entityId == null) return
  submitting.value = 'disable'
  try {
    await disableEntity(props.entityType, props.entityId)
    ElMessage.success(`已停用${typeLabel.value}`)
    finish()
  } catch (e: unknown) {
    ElMessage.error(serverMessage(e, '停用失败'))
  } finally {
    submitting.value = ''
  }
}

function finish() {
  emit('updated')
  visible.value = false
}

function close() {
  visible.value = false
}
</script>

<style scoped>
.impact-body {
  min-height: 120px;
}
.summary {
  margin: 0 0 12px;
}
.impact-grid {
  margin-bottom: 12px;
}
.impact-alert {
  margin-bottom: 12px;
}
.hint {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 12px;
  line-height: 1.6;
}
</style>
