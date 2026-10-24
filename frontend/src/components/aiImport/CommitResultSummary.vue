<template>
  <el-card v-if="categories.length" shadow="never" data-testid="commit-result">
    <template #header>
      <div class="card-head">
        <span class="card-title">入库结果</span>
        <el-button v-if="draftId" link type="primary" @click="openDraft">查看草稿</el-button>
      </div>
    </template>

    <el-table :data="categories" size="small" border data-testid="commit-categories">
      <el-table-column label="类别" width="130">
        <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
      </el-table-column>
      <el-table-column prop="created" label="新建" width="80" />
      <el-table-column prop="updated" label="补齐" width="80" />
      <el-table-column prop="skipped" label="跳过" width="80" />
      <el-table-column label="错误" min-width="240">
        <template #default="{ row }">
          <div v-for="error in row.errors ?? []" :key="error" class="error-line">{{ error }}</div>
          <span v-if="!row.errors?.length">—</span>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { categoryLabel, type CategoryResult } from './timetablePreview'

const props = defineProps<{
  categories: CategoryResult[]
  draftId?: number | null
}>()

const router = useRouter()

function openDraft() {
  if (props.draftId) {
    router.push(`/admin/drafts/${props.draftId}`)
  }
}
</script>

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-weight: 600;
}

.error-line {
  font-size: 12px;
  color: #b91c1c;
}
</style>
