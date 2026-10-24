<template>
  <el-card shadow="never">
    <template #header><span class="card-title">调代与通知</span></template>
    <el-form label-width="110px" class="form">
      <el-form-item>
        <template #label>
          审批模式
          <InfoTooltip field="substitution.approvalMode" />
        </template>
        <el-radio-group v-model="form['substitution.approval-mode']">
          <el-radio-button value="approval">审批模式</el-radio-button>
          <el-radio-button value="direct">直接生效</el-radio-button>
        </el-radio-group>
        <div class="hint">只影响代课；调课始终需要教务整单批准。</div>
      </el-form-item>
      <el-form-item label="调课搜索窗口">
        <el-select v-model="form['substitution.search-window-days']" style="width: 160px">
          <el-option v-for="days in 30" :key="days" :label="`${days} 天`" :value="String(days)" />
        </el-select>
        <div class="hint">默认 7 天。先找同一天，无解再向后最多搜这么多天。</div>
      </el-form-item>
      <el-form-item label="方案教师上限">
        <el-select v-model="form['substitution.max-plan-teachers']" style="width: 160px">
          <el-option v-for="count in 9" :key="count + 1" :label="`${count + 1} 人`" :value="String(count + 1)" />
        </el-select>
        <div class="hint">默认 6 人。环长超过上限的方案不会生成。</div>
      </el-form-item>
      <el-form-item label="方案排序">
        <el-select v-model="form['substitution.ranking-preset']" style="width: 220px">
          <el-option label="最小影响（默认）" value="min-impact" />
          <el-option label="最少教师" value="min-teachers" />
          <el-option label="最稳课表" value="stable-timetable" />
        </el-select>
        <div class="hint">切换后新生成的方案按新排序，已有方案保持不变。</div>
      </el-form-item>
      <el-form-item label="通知范围">
        <el-select v-model="form['notification.scope']" style="width: 260px">
          <el-option label="当事人（原教师 + 代课教师 + 班主任）" value="minimal" />
          <el-option label="含年级组长（一期未建模，等同当事人）" value="with-grade-leader" />
          <el-option label="含教务（增加教务管理员）" value="with-admin" />
        </el-select>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup lang="ts">
import InfoTooltip from '@/components/InfoTooltip.vue'

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
