<template>
  <div v-if="blockers.length" class="blockers">
    <el-alert
      v-for="issue in blockers"
      :key="issue.field + '-' + issue.message"
      data-testid="blocking-issue"
      type="warning"
      show-icon
      :closable="false"
      :title="issue.message || '当前数据无法入库，请先处理预览中的问题'"
    >
      <div v-if="issue.field === 'semester'" class="actions">
        <el-button data-testid="go-semesters" link type="primary" @click="goSemesters">
          去学期管理设置当前学期
        </el-button>
      </div>
      <div v-else-if="issue.field === 'periods'" class="actions">
        <el-button
          data-testid="generate-periods"
          type="primary"
          size="small"
          :loading="generating"
          @click="generatePeriods"
        >
          一键生成默认作息（上午 4 + 下午 4，周一至周五）
        </el-button>
      </div>
    </el-alert>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import type { PreviewIssue } from './timetablePreview'

const props = defineProps<{ issues: PreviewIssue[] }>()
const emit = defineEmits<{ (e: 'reload'): void }>()

const router = useRouter()
const generating = ref(false)

const blockers = computed(() => props.issues.filter((issue) => issue.row === 0))

function goSemesters() {
  router.push('/admin/semesters')
}

/** The same endpoint the schedule editor uses; the page then re-reads the preview. */
async function generatePeriods() {
  generating.value = true
  try {
    const { data } = await http.get<{ id: number }>('/semesters/current')
    await http.post(`/semesters/${data.id}/periods/default`, {
      morningCount: 4,
      afternoonCount: 4,
      eveningCount: 0,
      days: [1, 2, 3, 4, 5],
    })
    ElMessage.success('已生成默认作息')
    emit('reload')
  } catch (error) {
    ElMessage.error(serverMessage(error, '生成默认作息失败，请到作息设置里手工配置'))
  } finally {
    generating.value = false
  }
}
</script>

<style scoped>
.blockers {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.actions {
  margin-top: 6px;
}
</style>
