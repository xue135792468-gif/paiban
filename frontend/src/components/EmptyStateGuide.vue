<template>
  <div class="empty-state-guide" data-testid="empty-state-guide">
    <el-icon class="guide-icon" :size="48">
      <component :is="icon" />
    </el-icon>
    <h3 class="guide-title">{{ title }}</h3>
    <p v-if="description" class="guide-description">{{ description }}</p>
    <div v-if="buttonText" class="guide-actions">
      <el-button type="primary" @click="handleClick">{{ buttonText }}</el-button>
    </div>
    <router-link
      v-if="helpSlug"
      class="guide-help"
      :to="helpLink(helpSlug, helpAnchor)"
    >
      {{ helpText || '查看帮助文档' }}
    </router-link>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { Box } from '@element-plus/icons-vue'
import { helpLink, type HelpSlug } from '@/help/links'

const props = withDefaults(
  defineProps<{
    title: string
    description?: string
    buttonText?: string
    /** In-app route; when present the button pushes it instead of emitting `action`. */
    buttonTo?: string
    helpSlug?: HelpSlug
    helpAnchor?: string
    helpText?: string
  }>(),
  {
    description: undefined,
    buttonText: undefined,
    buttonTo: undefined,
    helpSlug: undefined,
    helpAnchor: undefined,
    helpText: undefined,
  },
)

const emit = defineEmits<{ action: [] }>()

const router = useRouter()
const icon = Box

function handleClick() {
  if (props.buttonTo) {
    void router.push(props.buttonTo)
    return
  }
  emit('action')
}
</script>

<style scoped>
.empty-state-guide {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 24px;
  text-align: center;
}

.guide-icon {
  color: var(--el-color-primary-light-5, #a0cfff);
}

.guide-title {
  margin: 4px 0 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--paiban-ink, #1f2937);
}

.guide-description {
  margin: 0;
  max-width: 520px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--paiban-muted, #6b7280);
}

.guide-actions {
  margin-top: 8px;
}

.guide-help {
  margin-top: 4px;
  font-size: 13px;
  color: var(--el-color-primary);
  text-decoration: none;
}

.guide-help:hover {
  text-decoration: underline;
}
</style>
