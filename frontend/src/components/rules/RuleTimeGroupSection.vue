<template>
  <el-card shadow="never" class="rule-time-groups" data-testid="rule-time-group-section">
    <div class="head">
      <div>
        <h3>时间分组</h3>
        <p class="hint">时间组属于当前学期；上午/下午由作息时间自动预建，可自行修改。</p>
      </div>
      <el-button type="primary" data-testid="rule-time-group-new" @click="openCreate">
        新建时间组
      </el-button>
    </div>

    <el-empty
      v-if="groups.length === 0"
      description="还没有时间分组"
      data-testid="rule-time-group-empty"
    />
    <div v-else v-loading="loading" class="list">
      <div
        v-for="group in groups"
        :key="group.id"
        class="row"
        :data-testid="`rule-time-group-row-${group.id}`"
      >
        <div class="info">
          <span class="name" :data-testid="`rule-time-group-name-${group.id}`">
            {{ group.name }}
            <el-tag v-if="group.autoFilled" size="small" type="info" class="auto-tag">预建</el-tag>
          </span>
          <span class="members" :data-testid="`rule-time-group-members-${group.id}`">
            {{ summaryOf(group) }}
          </span>
        </div>
        <div class="actions">
          <el-button
            link
            type="primary"
            :data-testid="`rule-time-group-edit-${group.id}`"
            @click="openEdit(group)"
          >
            编辑
          </el-button>
          <el-button
            link
            type="danger"
            :data-testid="`rule-time-group-delete-${group.id}`"
            @click="askDelete(group)"
          >
            删除
          </el-button>
        </div>
      </div>
    </div>

    <RuleTimeGroupDialog
      v-model="dialogVisible"
      :group="editing"
      :periods="periods"
      @save="saveGroup"
    />
  </el-card>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import RuleTimeGroupDialog from '@/components/rules/RuleTimeGroupDialog.vue'
import { weekdayLabel, type PeriodOption } from '@/rules/compiler'
import type { TimeGroupSlot } from '@/rules/useTimeGroups'
import type {
  RuleGroup,
  RuleGroupDeleteResult,
  RuleGroupImpact,
} from '@/api/ruleGroups'

const props = defineProps<{
  groups: RuleGroup[]
  periods: PeriodOption[]
  loading?: boolean
  semesterId: number | null
  createGroup?: (name: string, slots: TimeGroupSlot[]) => Promise<boolean>
  updateGroup?: (id: number, name: string, slots: TimeGroupSlot[]) => Promise<boolean>
  deleteGroup?: (id: number, withRules: boolean) => Promise<RuleGroupDeleteResult | null>
  loadImpact?: (id: number) => Promise<RuleGroupImpact | null>
}>()

const emit = defineEmits<{
  changed: [payload: { deletedRules: number }]
}>()

const dialogVisible = ref(false)
const editing = ref<RuleGroup | null>(null)

/** 「周一第1节、周一第2节」, capped so one long group cannot flood the row. */
function summaryOf(group: RuleGroup): string {
  const labels = group.members
    .filter((member) => member.kind === 'TIME'
      && member.dayOfWeek !== null && member.periodNo !== null)
    .map((member) => `${weekdayLabel(member.dayOfWeek as number)}第${member.periodNo}节`)
  if (labels.length === 0) {
    return '还没有成员'
  }
  if (labels.length <= 6) {
    return labels.join('、')
  }
  return `${labels.slice(0, 6).join('、')} 等 ${labels.length} 个`
}

function openCreate() {
  editing.value = null
  dialogVisible.value = true
}

function openEdit(group: RuleGroup) {
  editing.value = group
  dialogVisible.value = true
}

async function saveGroup(payload: { name: string; slots: TimeGroupSlot[] }) {
  const saved = editing.value
    ? await props.updateGroup?.(editing.value.id, payload.name, payload.slots)
    : await props.createGroup?.(payload.name, payload.slots)
  if (saved) {
    dialogVisible.value = false
    ElMessage.success(editing.value ? '时间分组已更新' : '时间分组已创建')
    emit('changed', { deletedRules: 0 })
  }
}

/**
 * Deleting a referenced group first names the rules that would break. The user can
 * back out to edit those rules, or explicitly delete the group together with them.
 */
async function askDelete(group: RuleGroup) {
  const impact = await props.loadImpact?.(group.id)
  if (!impact) {
    return
  }
  if (impact.rules.length === 0) {
    try {
      await ElMessageBox.confirm(
        `删除时间分组「${group.name}」？7 天内可在「最近删除」恢复。`,
        '删除时间分组',
        {
          type: 'warning',
          confirmButtonText: '删除',
          cancelButtonText: '取消',
        },
      )
    } catch {
      return
    }
    await remove(group, false)
    return
  }

  const listed = impact.rules
    .slice(0, 5)
    .map((rule) => `${rule.semesterName} · ${rule.typeName}`)
  const rest = impact.rules.length > 5 ? `\n等 ${impact.rules.length} 条` : ''
  try {
    await ElMessageBox.confirm(
      `删除时间分组「${group.name}」。删除后这些规则会失效：\n${listed.join('\n')}${rest}`,
      '删除时间分组',
      {
        type: 'warning',
        confirmButtonText: '连同规则一起删除',
        cancelButtonText: '先改规则',
        distinguishCancelAndClose: true,
      },
    )
  } catch {
    // 「先改规则」和关闭弹窗都不删除任何东西。
    return
  }
  await remove(group, true)
}

async function remove(group: RuleGroup, withRules: boolean) {
  const result = await props.deleteGroup?.(group.id, withRules)
  if (!result) {
    return
  }
  ElMessage.success(withRules
    ? `已删除时间分组和 ${result.deletedRules} 条规则，7 天内可在「最近删除」分别恢复`
    : '已删除时间分组，7 天内可在「最近删除」恢复')
  emit('changed', { deletedRules: result.deletedRules })
}
</script>

<style scoped>
.rule-time-groups {
  margin-bottom: 14px;
  border-radius: var(--paiban-radius, 8px);
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

h3 {
  margin: 0 0 4px;
  font-size: 16px;
}

.hint {
  margin: 0;
  color: var(--paiban-muted, #6b7280);
  font-size: 12px;
}

.list {
  margin-top: 12px;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  border-top: 1px dashed var(--el-border-color-lighter, #ebeef5);
}

.info {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}

.name {
  font-weight: 600;
}

.auto-tag {
  margin-left: 6px;
}

.members {
  color: var(--paiban-muted, #6b7280);
  font-size: 13px;
}
</style>
