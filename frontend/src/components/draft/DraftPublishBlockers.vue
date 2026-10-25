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
import { blockerTarget } from './publishBlockers'
import type { DraftPublishBlocker } from './types'

// A Boolean prop defaults to false in Vue, but here the action is available unless the
// page says otherwise, so the default must be explicit.
const props = withDefaults(
  defineProps<{ blockers: DraftPublishBlocker[]; roomFill?: boolean }>(),
  { roomFill: true },
)
const emit = defineEmits<{ (e: 'edit-lessons'): void; (e: 'fill-rooms'): void }>()

const router = useRouter()

interface Row {
  blocker: DraftPublishBlocker
  kind: 'link' | 'drawer' | 'dialog' | 'none'
  label: string
  to: string
}

/**
 * One row per blocker. The message already carries the affected count, so the row only
 * adds the way out — resolved here from the server's action identifier.
 */
const rows = computed<Row[]>(() =>
  props.blockers.map((blocker) => {
    const target = blockerTarget(blocker.action, { roomFill: props.roomFill !== false })
    return {
      blocker,
      kind: target.kind,
      label: target.kind === 'none' ? '' : target.label,
      to: target.kind === 'link' ? target.to : '',
    }
  }),
)

function activate(row: Row) {
  if (row.kind === 'link') {
    void router.push(row.to)
    return
  }
  if (row.kind === 'dialog') {
    emit('fill-rooms')
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
