<template>
  <el-card shadow="never" class="rule-groups" :data-testid="tid('rule-group-section')">
    <div class="head">
      <div>
        <h3>{{ title }}</h3>
        <p class="hint">{{ hintText }}</p>
      </div>
      <el-button type="primary" :data-testid="tid('rule-group-new')" @click="openCreate">
        新建分组
      </el-button>
    </div>

    <el-empty
      v-if="groups.length === 0"
      :description="`还没有${title}`"
      :data-testid="tid('rule-group-empty')"
    />
    <div v-else v-loading="loading" class="list">
      <div
        v-for="group in groups"
        :key="group.id"
        class="row"
        :data-testid="tid(`rule-group-row-${group.id}`)"
      >
        <div class="info">
          <span class="name" :data-testid="tid(`rule-group-name-${group.id}`)">{{ group.name }}</span>
          <span class="members" :data-testid="tid(`rule-group-members-text-${group.id}`)">
            {{ memberNames(group) }}
          </span>
        </div>
        <div class="actions">
          <el-button link type="primary" :data-testid="tid(`rule-group-edit-${group.id}`)" @click="openEdit(group)">
            编辑
          </el-button>
          <el-button link type="danger" :data-testid="tid(`rule-group-delete-${group.id}`)" @click="askDelete(group)">
            删除
          </el-button>
        </div>
      </div>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? `编辑${title}` : `新建${title}`"
      width="520px"
      destroy-on-close
    >
      <el-form label-width="72px">
        <el-form-item label="组名">
          <el-input
            v-model="formName"
            maxlength="64"
            show-word-limit
            :placeholder="namePlaceholder"
            :data-testid="tid('rule-group-name-input')"
          />
        </el-form-item>
        <el-form-item label="成员">
          <el-select
            v-model="formMemberIds"
            multiple
            filterable
            collapse-tags
            :placeholder="`选择${memberLabel}（可不选，之后再加）`"
            class="member-select"
            :data-testid="tid('rule-group-members')"
          >
            <el-option
              v-for="option in options"
              :key="option.id"
              :label="option.name"
              :value="option.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :data-testid="tid('rule-group-cancel')" @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="!validName"
          :data-testid="tid('rule-group-save')"
          @click="submit"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { OptionItem } from '@/components/constraints/types'
import type { RuleGroupKind } from '@/rules/useRuleGroups'
import type {
  RuleGroup,
  RuleGroupDeleteResult,
  RuleGroupImpact,
} from '@/api/ruleGroups'

const props = defineProps<{
  /** Teacher or course groups; the panel is the same widget for both. */
  groupType: RuleGroupKind
  /** 「教师分组」/「课程分组」 */
  title: string
  /** 「老师」/「课程」 */
  memberLabel: string
  groups: RuleGroup[]
  /** Teachers or courses, matching `groupType`. */
  options: OptionItem[]
  hint?: string
  loading?: boolean
  createGroup?: (name: string, memberIds: number[]) => Promise<boolean>
  updateGroup?: (id: number, name: string, memberIds: number[]) => Promise<boolean>
  deleteGroup?: (id: number, withRules: boolean) => Promise<RuleGroupDeleteResult | null>
  loadImpact?: (id: number) => Promise<RuleGroupImpact | null>
}>()

const emit = defineEmits<{
  changed: [payload: { deletedRules: number }]
}>()

const dialogVisible = ref(false)
const editing = ref<RuleGroup | null>(null)
const formName = ref('')
const formMemberIds = ref<number[]>([])
const saving = ref(false)

const validName = computed(() => formName.value.trim().length > 0 && formName.value.trim().length <= 64)

const hintText = computed(
  () => props.hint
    ?? `一个${props.memberLabel}可以属于多个组；改组成员后引用该组的规则自动生效。`,
)

const namePlaceholder = computed(
  () => (props.groupType === 'TEACHER' ? '如：教导主任' : '如：技能课'),
)

/**
 * Teacher panels keep the ticket-04 test ids; a second panel on the same page
 * appends its type so both sections stay addressable.
 */
function tid(base: string): string {
  return props.groupType === 'TEACHER' ? base : `${base}-${props.groupType.toLowerCase()}`
}

function memberNames(group: RuleGroup): string {
  const names = group.members
    .filter((member) => member.kind === props.groupType)
    .map((member) => member.name)
  return names.length > 0 ? names.join('、') : '还没有成员'
}

function openCreate() {
  editing.value = null
  formName.value = ''
  formMemberIds.value = []
  dialogVisible.value = true
}

function openEdit(group: RuleGroup) {
  editing.value = group
  formName.value = group.name
  formMemberIds.value = group.members
    .filter((member) => member.kind === props.groupType)
    .map((member) => member.objectId)
  dialogVisible.value = true
}

async function submit() {
  if (!validName.value || saving.value) {
    return
  }
  const name = formName.value.trim()
  saving.value = true
  try {
    const saved = editing.value
      ? await props.updateGroup?.(editing.value.id, name, formMemberIds.value)
      : await props.createGroup?.(name, formMemberIds.value)
    if (saved) {
      dialogVisible.value = false
      ElMessage.success(editing.value ? '分组已更新' : '分组已创建')
    }
  } finally {
    saving.value = false
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
        `删除分组「${group.name}」？7 天内可在「最近删除」恢复。`,
        '删除分组',
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
      `删除分组「${group.name}」。删除后这些规则会失效：\n${listed.join('\n')}${rest}`,
      '删除分组',
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
    ? `已删除分组和 ${result.deletedRules} 条规则，7 天内可在「最近删除」分别恢复`
    : '已删除分组，7 天内可在「最近删除」恢复')
  emit('changed', { deletedRules: result.deletedRules })
}
</script>

<style scoped>
.rule-groups {
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

.members {
  color: var(--paiban-muted, #6b7280);
  font-size: 13px;
}

.member-select {
  width: 100%;
}
</style>
