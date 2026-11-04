<template>
  <div class="controls">
    <el-radio-group
      :model-value="viewMode"
      @update:model-value="onViewChange"
    >
      <el-radio-button value="class">按行政班</el-radio-button>
      <el-radio-button value="teacher">按教师</el-radio-button>
    </el-radio-group>
    <el-select
      :model-value="targetId"
      filterable
      style="width: 220px"
      placeholder="选择对象"
      @update:model-value="emit('update:targetId', $event)"
    >
      <el-option
        v-for="option in targetOptions"
        :key="option.id"
        :label="option.name"
        :value="option.id"
      />
    </el-select>
    <div class="spacer" />
    <template v-if="selectedLesson && !selectionMode">
      <el-tag type="primary" effect="plain" closable @close="emit('clear-selection')">
        已选：{{ selectedLesson.courseName }} · {{ selectedLesson.teacherName }} ·
        {{ selectedLesson.periodLabel }}
      </el-tag>
      <el-button
        type="primary"
        :disabled="!draftEditable || selectedLesson.periodId == null"
        title="跨班换人：只交换任课教师，节次和课程不变"
        @click="emit('swap')"
      >
        跨班换人
      </el-button>
      <el-button
        :disabled="!draftEditable || selectedLesson.periodId == null"
        title="不拖拽，点选目标节次（键盘也可用）"
        @click="emit('adjust-position')"
      >
        选择位置
      </el-button>
      <el-button
        :data-testid="selectedLessonLocked ? 'unlock-single' : 'lock-single'"
        :disabled="!draftEditable || selectedLesson.periodId == null"
        @click="
          selectedLessonLocked
            ? emit('unlock-single', selectedLesson)
            : emit('lock-single', selectedLesson)
        "
      >
        {{ selectedLessonLocked ? '解锁' : '锁定' }}
      </el-button>
    </template>
    <el-button
      :type="selectionMode ? 'primary' : undefined"
      data-testid="toggle-selection"
      @click="emit('toggle-selection')"
    >
      多选
    </el-button>
    <template v-if="selectionMode">
      <el-tag type="info" effect="plain" data-testid="selected-count">
        已选 {{ selectedCount }} 节
      </el-tag>
      <el-button
        type="primary"
        :disabled="!draftEditable || selectedCount === 0"
        @click="emit('batch-lock', { scope: 'selected', locked: true })"
      >
        锁定所选（{{ selectedCount }}）
      </el-button>
      <el-button
        :disabled="!draftEditable || selectedCount === 0"
        @click="emit('batch-lock', { scope: 'selected', locked: false })"
      >
        解锁所选（{{ selectedCount }}）
      </el-button>
      <el-dropdown trigger="click" @command="onBatchCommand">
        <el-button data-testid="scope-menu">
          按范围
          <el-icon class="el-icon--right"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <template v-if="canUseClassScope">
              <template v-for="day in dayOptions" :key="day.day">
                <el-dropdown-item :command="{ scope: 'day', day: day.day, locked: true }">
                  锁定{{ day.label }}（{{ day.count }} 节）
                </el-dropdown-item>
                <el-dropdown-item :command="{ scope: 'day', day: day.day, locked: false }">
                  解锁{{ day.label }}（{{ day.count }} 节）
                </el-dropdown-item>
              </template>
              <el-dropdown-item divided :command="{ scope: 'class', locked: true }">
                锁定全班（{{ classScopeCount }} 节）
              </el-dropdown-item>
              <el-dropdown-item :command="{ scope: 'class', locked: false }">
                解锁全班（{{ classScopeCount }} 节）
              </el-dropdown-item>
            </template>
            <el-dropdown-item v-else disabled>
              教师视角只能按所选锁定，请切换到「按行政班」
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-button link data-testid="clear-multi-selection" @click="emit('clear-multi-selection')">
        取消选择
      </el-button>
      <span v-if="!canUseClassScope" class="scope-hint">按天/全班需切到「按行政班」</span>
    </template>
    <el-button @click="emit('toggle-drawer')">
      <el-icon><Tickets /></el-icon>
      <span>未排课节（{{ unassignedCount }}）</span>
    </el-button>
  </div>
  <p class="lock-hint">
    锁定保护课节的时间与任课教师；可单节锁定，也可多选后按所选、当前行政班的某天或全班批量锁定。
    批量范围只含已排课节；多选模式下不会触发拖拽。锁定不改变冲突判断，违规课节仍显示冲突，锁定不等于合规。
  </p>
  <el-alert
    v-if="swapMode"
    class="swap-hint"
    type="success"
    :closable="false"
    show-icon
    title="换课模式：绿色可换（同班），灰色不可换（悬停看原因）；点绿格预览，Esc 退出"
  />
</template>

<script setup lang="ts">
import { ArrowDown, Tickets } from '@element-plus/icons-vue'
import type { DraftLesson } from './types'
import type { LockBatchPayload } from '@/composables/useDraftLocks'

withDefaults(
  defineProps<{
    viewMode: 'class' | 'teacher'
    targetId: number | null
    targetOptions: Array<{ id: number; name: string }>
    selectedLesson: DraftLesson | null
    draftEditable: boolean
    unassignedCount: number
    selectionMode?: boolean
    selectedCount?: number
    selectedLessonLocked?: boolean
    canUseClassScope?: boolean
    dayOptions?: Array<{ day: number; label: string; count: number }>
    classScopeCount?: number
    /** Teacher-view swap mode is active; shows the green/grey legend. */
    swapMode?: boolean
  }>(),
  {
    selectionMode: false,
    selectedCount: 0,
    selectedLessonLocked: false,
    canUseClassScope: true,
    dayOptions: () => [],
    classScopeCount: 0,
    swapMode: false,
  },
)

const emit = defineEmits<{
  (e: 'update:viewMode', value: 'class' | 'teacher'): void
  (e: 'update:targetId', value: number | null): void
  (e: 'view-change'): void
  (e: 'swap'): void
  (e: 'adjust-position'): void
  (e: 'toggle-drawer'): void
  (e: 'clear-selection'): void
  (e: 'toggle-selection'): void
  (e: 'lock-single', lesson: DraftLesson): void
  (e: 'unlock-single', lesson: DraftLesson): void
  (e: 'batch-lock', payload: LockBatchPayload): void
  (e: 'clear-multi-selection'): void
}>()

function onViewChange(value: string | number | boolean | undefined) {
  const next = value as 'class' | 'teacher'
  emit('update:viewMode', next)
  emit('view-change')
}

function onBatchCommand(command: unknown) {
  emit('batch-lock', command as LockBatchPayload)
}
</script>

<style scoped>
.controls {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.spacer {
  flex: 1;
}
.lock-hint {
  margin: 8px 0 0;
  color: var(--paiban-muted);
  font-size: 12px;
  line-height: 1.7;
}
.scope-hint {
  font-size: 12px;
  color: var(--paiban-muted);
}
.swap-hint {
  margin-top: 8px;
}
</style>
