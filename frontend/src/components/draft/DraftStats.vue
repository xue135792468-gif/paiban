<template>
  <div class="stats">
    <div class="stat">
      <span class="stat-label">硬冲突</span>
      <span class="stat-value" :class="{ danger: hardConflicts > 0 }">{{ hardConflicts }}</span>
    </div>
    <div class="stat">
      <span class="stat-label">得分</span>
      <span class="stat-value">{{ scoreLabel }}</span>
    </div>
    <div class="stat">
      <span class="stat-label">课节数</span>
      <span class="stat-value">{{ lessonCount }}</span>
    </div>
    <div class="stat">
      <span class="stat-label">未排课</span>
      <span class="stat-value" :class="{ danger: unassignedCount > 0 }">{{ unassignedCount }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
/** Numbers only; conflict counts stay separate from structure gaps (missing rooms etc.). */
defineProps<{
  hardConflicts: number
  scoreLabel: string
  lessonCount: number
  unassignedCount: number
}>()
</script>

<style scoped>
.stats {
  display: flex;
  gap: 12px;
}
.stat {
  background: #fff;
  border-radius: 10px;
  padding: 12px 18px;
  min-width: 150px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid #eef0f5;
}
.stat-label {
  color: var(--paiban-muted);
  font-size: 12px;
}
.stat-value {
  font-size: 18px;
  font-weight: 600;
}
.danger {
  color: #e45656;
}
</style>
