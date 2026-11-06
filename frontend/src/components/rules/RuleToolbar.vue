<template>
  <div class="toolbar">
    <el-select
      v-model="semesterModel"
      placeholder="请选择学期"
      :disabled="semesters.length === 0"
      class="semester-select"
      data-testid="rule-semester"
      @change="emit('semester-change')"
    >
      <el-option
        v-for="semester in semesters"
        :key="semester.id"
        :label="semester.current ? `${semester.name} (当前)` : semester.name"
        :value="semester.id"
      />
    </el-select>
    <el-input
      v-model="queryModel"
      clearable
      placeholder="搜索规则（名称或句子）"
      class="search-input"
      data-testid="rule-search"
    />
    <el-button type="primary" :disabled="semesterId === null" data-testid="rule-new" @click="emit('new')">
      新建规则
    </el-button>
    <el-button
      data-testid="rule-groups-toggle"
      :class="{ 'is-on': groupsVisible }"
      @click="emit('toggle-groups')"
    >
      分组
    </el-button>
    <el-button :disabled="semesterId === null" data-testid="rule-ai-toggle" @click="emit('toggle-ai')">
      AI 帮填
    </el-button>
    <slot name="actions-extra" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Semester {
  id: number
  name: string
  current: boolean
}

const props = defineProps<{
  semesters: Semester[]
  semesterId: number | null
  query: string
  groupsVisible: boolean
}>()

const emit = defineEmits<{
  'update:semesterId': [value: number | null]
  'update:query': [value: string]
  'semester-change': []
  new: []
  'toggle-groups': []
  'toggle-ai': []
}>()

const semesterModel = computed({
  get: () => props.semesterId,
  set: (value: number | null) => emit('update:semesterId', value),
})

const queryModel = computed({
  get: () => props.query,
  set: (value: string) => emit('update:query', value),
})
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.semester-select {
  width: 240px;
}

.search-input {
  width: 220px;
}
</style>
