<template>
  <div v-if="rows.length" class="blockers" data-testid="publish-blockers">
    <el-alert
      v-for="row in rows"
      :key="row.blocker.code"
      data-testid="publish-blocker"
      :data-code="row.blocker.code"
      type="warning"
      show-icon
      :closable="false"
      :title="row.blocker.message"
    >
      <div v-if="row.kind !== 'none'" class="actions">
        <el-button
          link
          type="primary"
          size="small"
          :data-testid="`blocker-action-${row.blocker.code}`"
          @click="activate(row)"
        >
          {{ row.label }}
        </el-button>
      </div>
    </el-alert>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { blockerActionFor, type BlockerAction } from './publishBlockers'
import type { DraftPublishBlocker } from './types'

const props = withDefaults(
  defineProps<{
    blockers: DraftPublishBlocker[]
    conflictTotal?: number
    activeConflictIndex?: number
  }>(),
  {
    conflictTotal: 0,
    activeConflictIndex: -1,
  },
)
const emit = defineEmits<{
  (e: 'edit-lessons'): void
  (e: 'locate-conflict'): void
}>()

const router = useRouter()

interface Row {
  blocker: DraftPublishBlocker
  kind: BlockerAction['kind']
  label: string
  to: string
}

/**
 * One row per blocker. The message already carries the affected count, so the row only
 * adds the way out, resolved here from the server's action identifier or conflict locator.
 */
const rows = computed<Row[]>(() =>
  props.blockers.map((blocker) => {
    const target = blockerActionFor(blocker)
    let label = target.kind === 'none' ? '' : target.label
    if (target.kind === 'locate-conflict' && props.activeConflictIndex >= 0 && props.conflictTotal > 0) {
      label = `${target.label}（${props.activeConflictIndex + 1}/${props.conflictTotal}）`
    }
    return {
      blocker,
      kind: target.kind,
      label,
      to: target.kind === 'link' ? target.to : '',
    }
  }),
)

function activate(row: Row) {
  if (row.kind === 'link') {
    void router.push(row.to)
    return
  }
  if (row.kind === 'locate-conflict') {
    emit('locate-conflict')
    return
  }
  emit('edit-lessons')
}
</script>

<style scoped>
.blockers {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.actions {
  margin-top: 4px;
}
</style>
