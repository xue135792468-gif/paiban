<template>
  <el-card shadow="never">
    <template #header><span class="card-title">调代与通知</span></template>
    <el-form label-width="110px" class="form">
      <el-form-item label="调课搜索窗口">
        <el-select v-model="form['substitution.search-window-days']" style="width: 160px">
          <el-option v-for="days in 30" :key="days" :label="`${days} 天`" :value="String(days)" />
        </el-select>
        <div class="hint">默认 7 天，仅用于发布后调课。发布后调课先找同一天；草稿换位始终在本周内搜索。</div>
      </el-form-item>
      <el-form-item label="最多变动几节课">
        <el-select v-model="form['substitution.max-plan-teachers']" style="width: 160px">
          <el-option v-for="count in 9" :key="count + 1" :label="`${count + 1} 节`" :value="String(count + 1)" />
        </el-select>
        <div class="hint">默认 6 节。一次换位方案最多移动这么多节课；去重教师人数另计。草稿换位与发布后调课的新方案都按此上限。</div>
      </el-form-item>
      <el-form-item label="方案排序">
        <el-select v-model="form['substitution.ranking-preset']" style="width: 220px">
          <el-option label="最小影响（默认）" value="min-impact" />
          <el-option label="最少教师" value="min-teachers" />
          <el-option label="最稳课表" value="stable-timetable" />
        </el-select>
        <div class="hint">草稿换位与发布后调课的新方案都按此排序；已有方案保持不变。</div>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup lang="ts">

const form = defineModel<Record<string, string>>({ required: true })
</script>

<style scoped>
.card-title {
  font-weight: 600;
}
.form {
  max-width: 760px;
}
.hint {
  color: var(--paiban-muted);
  font-size: 12px;
  margin-left: 10px;
}
</style>
