<template>
  <span class="picked" data-testid="picked-summary">
    已选：<b>{{ label }}</b>
    <button
      v-if="editable"
      type="button"
      class="edit"
      data-testid="picked-edit"
      @click="emit('edit')"
    >
      修改
    </button>
  </span>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ label: string; editable?: boolean }>(), { editable: true })

const emit = defineEmits<{ edit: [] }>()
</script>

<style scoped>
.picked {
  color: var(--muted);
  font-size: 13px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.picked b {
  color: var(--text);
  font-weight: 500;
}
.edit {
  background: none;
  border: 0;
  color: var(--brand);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  margin-left: 6px;
  padding: 0;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.edit:hover {
  color: var(--brand);
  filter: brightness(0.9);
}
</style>
