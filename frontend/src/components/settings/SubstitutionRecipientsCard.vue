<template>
  <el-card shadow="never">
    <template #header><span class="card-title">调代通知收件人</span></template>
    <p class="hint">
      当事人、班主任与教务按上方「通知范围」发送；这里可以再固定指定若干账号（例如校长、年级负责人），
      调课与代课生效时都会通知他们。
    </p>
    <el-select
      v-model="selected"
      multiple
      filterable
      clearable
      placeholder="选择额外收件人"
      style="width: 100%"
    >
      <el-option
        v-for="user in options"
        :key="user.id"
        :label="`${user.displayName || user.username}（${user.username}）`"
        :value="user.id"
      />
    </el-select>
    <div class="actions">
      <el-button type="primary" :loading="saving" @click="save">保存收件人</el-button>
      <span class="hint">不选任何人表示只按「通知范围」发送。</span>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'

interface RecipientOption {
  id: number
  username: string
  displayName?: string
}

const selected = ref<number[]>([])
const options = ref<RecipientOption[]>([])
const saving = ref(false)

async function load() {
  try {
    const { data } = await http.get<{ userIds: number[]; options: RecipientOption[] }>(
      '/settings/substitution-recipients',
    )
    selected.value = data.userIds ?? []
    options.value = data.options ?? []
  } catch {
    selected.value = []
    options.value = []
  }
}

async function save() {
  saving.value = true
  try {
    const { data } = await http.put<{ userIds: number[] }>(
      '/settings/substitution-recipients',
      { userIds: selected.value },
    )
    selected.value = data.userIds ?? []
    ElMessage.success('收件人已保存')
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.card-title {
  font-weight: 600;
}
.hint {
  color: var(--paiban-muted);
  font-size: 12px;
  line-height: 1.6;
  margin: 0 0 10px;
}
.actions {
  margin-top: 12px;
  display: flex;
  align-items: center;
}
</style>
