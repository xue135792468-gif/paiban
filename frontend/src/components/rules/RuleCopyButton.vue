<template>
  <el-button :disabled="targetSemesterId === null" data-testid="rule-copy-open" @click="open">
    复制往期规则
  </el-button>

  <el-dialog
    v-model="visible"
    title="从以前的学期复制规则"
    width="560px"
    data-testid="rule-copy-dialog"
    @closed="reset"
  >
    <template v-if="result === null">
      <el-select
        v-model="sourceId"
        placeholder="选择来源学期"
        class="source-select"
        data-testid="rule-copy-source"
      >
        <el-option
          v-for="semester in sources"
          :key="semester.id"
          :label="semester.current ? `${semester.name}（当前）` : semester.name"
          :value="semester.id"
        />
      </el-select>
      <p class="warning" data-testid="rule-copy-warning">
        将用「来源学期」的规则整套替换当前学期规则。当前学期的非系统规则会被删除；两条系统底线保留。
      </p>
    </template>

    <div v-else class="result" data-testid="rule-copy-result">
      <p class="summary">
        已复制 {{ result.copiedCards }} 张规则卡，其中 {{ result.disabledCards }} 张本学期用不上。
      </p>
      <ul v-if="visibleIssues.length > 0" class="issues">
        <li v-for="issue in visibleIssues" :key="issue.groupKey" data-testid="rule-copy-issue">
          {{ issue.typeName }}：{{ issue.reason }}
        </li>
      </ul>
      <p v-if="hiddenIssueCount > 0" class="more" data-testid="rule-copy-issue-more">
        等 {{ hiddenIssueCount }} 条
      </p>
    </div>

    <template #footer>
      <template v-if="result === null">
        <el-button data-testid="rule-copy-cancel" @click="visible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="sourceId === null"
          :loading="loading"
          data-testid="rule-copy-confirm"
          @click="confirm"
        >
          确认替换
        </el-button>
      </template>
      <el-button v-else type="primary" data-testid="rule-copy-close" @click="visible = false">
        关闭
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { serverMessage } from '@/api/errors'
import type { CopyRulesResult } from '@/api/rules'

interface Semester {
  id: number
  name: string
  current?: boolean
}

/** Ticket 08: the whole copy flow lives here so the rule page stays small. */
const props = defineProps<{
  semesters: Semester[]
  targetSemesterId: number | null
  baseVersion: number
  copy: (sourceSemesterId: number) => Promise<CopyRulesResult | null>
}>()

const emit = defineEmits<{
  done: [result: CopyRulesResult]
}>()

const MAX_ISSUES = 8
const visible = ref(false)
const sourceId = ref<number | null>(null)
const loading = ref(false)
const result = ref<CopyRulesResult | null>(null)

const sources = computed(() =>
  props.semesters.filter((semester) => semester.id !== props.targetSemesterId))
const visibleIssues = computed(() => result.value?.issues.slice(0, MAX_ISSUES) ?? [])
const hiddenIssueCount = computed(() =>
  Math.max(0, (result.value?.issues.length ?? 0) - MAX_ISSUES))

function open() {
  reset()
  visible.value = true
}

function reset() {
  sourceId.value = null
  result.value = null
  loading.value = false
}

async function confirm() {
  if (sourceId.value === null) {
    return
  }
  loading.value = true
  try {
    const copied = await props.copy(sourceId.value)
    if (copied) {
      result.value = copied
      emit('done', copied)
    }
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '复制规则失败'))
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.source-select {
  width: 100%;
}

.warning {
  margin: 12px 0 0;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--el-color-warning-light-9, #fdf6ec);
  color: var(--el-color-warning-dark-2, #b88230);
  font-size: 13px;
  line-height: 1.6;
}

.summary {
  margin: 0 0 8px;
  font-size: 14px;
  line-height: 1.6;
}

.issues {
  margin: 0;
  padding-left: 18px;
  color: var(--el-color-danger, #f56c6c);
  font-size: 12px;
  line-height: 1.7;
}

.more {
  margin: 4px 0 0;
  color: var(--paiban-muted, #6b7280);
  font-size: 12px;
}
</style>
