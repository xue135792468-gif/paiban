<template>
  <el-dialog
    v-model="visible"
    :title="group ? '编辑时间分组' : '新建时间分组'"
    width="560px"
    destroy-on-close
    data-testid="rule-time-group-dialog"
  >
    <el-form label-width="72px">
      <el-form-item label="组名">
        <el-input
          v-model="formName"
          maxlength="64"
          show-word-limit
          placeholder="如：上午、下午、晚自习"
          data-testid="rule-time-group-name-input"
        />
      </el-form-item>
      <el-form-item label="成员">
        <div class="slots">
          <p v-if="dayRows.length === 0" class="hint">当前学期还没有作息节次</p>
          <el-checkbox-group v-else v-model="formSlots" class="slot-grid">
            <div v-for="day in dayRows" :key="day.day" class="day-block">
              <span class="day-label">{{ day.label }}</span>
              <div class="slot-checks">
                <el-checkbox
                  v-for="periodNo in day.periodNos"
                  :key="periodNo"
                  :value="slotKey(day.day, periodNo)"
                  :data-testid="`rule-time-group-slot-${day.day}-${periodNo}`"
                >
                  第 {{ periodNo }} 节
                </el-checkbox>
              </div>
            </div>
          </el-checkbox-group>
          <p class="hint">改成员后，引用该时间组的规则立即生效。</p>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button data-testid="rule-time-group-cancel" @click="visible = false">取消</el-button>
      <el-button
        type="primary"
        :disabled="!validName"
        data-testid="rule-time-group-save"
        @click="submit"
      >
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { weekdayLabel, type PeriodOption } from '@/rules/compiler'
import type { RuleGroup } from '@/api/ruleGroups'
import type { TimeGroupSlot } from '@/rules/useTimeGroups'

const props = defineProps<{
  modelValue: boolean
  /** Null while creating; the edited group prefills both name and members. */
  group: RuleGroup | null
  periods: PeriodOption[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  save: [payload: { name: string; slots: TimeGroupSlot[] }]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const formName = ref('')
const formSlots = ref<string[]>([])

const validName = computed(() => formName.value.trim().length > 0 && formName.value.trim().length <= 64)

/** One row per weekday that actually has periods, so no empty columns appear. */
const dayRows = computed(() => {
  const byDay = new Map<number, number[]>()
  for (const period of props.periods) {
    const list = byDay.get(period.dayOfWeek) ?? []
    if (!list.includes(period.periodNo)) {
      list.push(period.periodNo)
    }
    byDay.set(period.dayOfWeek, list)
  }
  return Array.from(byDay.entries())
    .sort(([left], [right]) => left - right)
    .map(([day, periodNos]) => ({
      day,
      label: weekdayLabel(day),
      periodNos: [...periodNos].sort((left, right) => left - right),
    }))
})

function slotKey(dayOfWeek: number, periodNo: number): string {
  return `${dayOfWeek}-${periodNo}`
}

watch(
  () => [props.modelValue, props.group] as const,
  ([isOpen]) => {
    if (!isOpen) {
      return
    }
    formName.value = props.group?.name ?? ''
    formSlots.value = (props.group?.members ?? [])
      .filter((member) => member.kind === 'TIME'
        && member.dayOfWeek !== null && member.periodNo !== null)
      .map((member) => slotKey(member.dayOfWeek as number, member.periodNo as number))
  },
  { immediate: true },
)

function submit() {
  if (!validName.value) {
    return
  }
  const slots = formSlots.value
    .map((key) => {
      const [day, periodNo] = key.split('-').map(Number)
      return { dayOfWeek: day, periodNo }
    })
    .filter((slot) => Number.isFinite(slot.dayOfWeek) && Number.isFinite(slot.periodNo))
    .sort((left, right) => left.dayOfWeek - right.dayOfWeek || left.periodNo - right.periodNo)
  emit('save', { name: formName.value.trim(), slots })
}
</script>

<style scoped>
.slots {
  width: 100%;
}

.slot-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.day-block {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.day-label {
  width: 40px;
  flex: 0 0 40px;
  color: var(--paiban-muted, #6b7280);
  font-size: 13px;
  line-height: 32px;
}

.slot-checks {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 12px;
}

.hint {
  margin: 6px 0 0;
  color: var(--paiban-muted, #6b7280);
  font-size: 12px;
}
</style>
