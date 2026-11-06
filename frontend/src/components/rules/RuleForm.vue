<template>
  <el-card shadow="never" class="rule-form" data-testid="rule-form">
    <h3 class="form-title">{{ initial ? '编辑规则卡' : '新建规则卡' }}</h3>
    <p class="form-hint">一张卡最多 3 句，共用一个启用开关和一个删除。</p>

    <RuleSentenceEditor
      v-for="(sentence, index) in sentences"
      :key="`${editorVersion}-${index}`"
      :sentence="sentence"
      :index="index"
      :removable="sentences.length > 1"
      :teachers="teachers"
      :classes="classes"
      :courses="courses"
      :days="days"
      :periods="periods"
      :teacher-groups="teacherGroups"
      :create-teacher-group="createTeacherGroup"
      :course-groups="courseGroups"
      :create-course-group="createCourseGroup"
      @update:sentence="updateSentence(index, $event)"
      @remove="removeSentence(index)"
    />

    <div class="add-row">
      <el-button
        :disabled="sentences.length >= 3"
        data-testid="rule-add-sentence"
        @click="addSentence"
      >
        加一句
      </el-button>
      <span class="hint">还可以加 {{ Math.max(0, 3 - sentences.length) }} 句</span>
    </div>

    <div class="preview" data-testid="rule-preview">预览：{{ preview }}</div>
    <p v-if="problems.length > 0" class="missing" data-testid="rule-missing">
      还差：{{ problems.join('；') }}
    </p>

    <div class="actions">
      <el-button data-testid="rule-cancel" @click="emit('cancel')">取消</el-button>
      <el-button
        type="primary"
        :disabled="problems.length > 0"
        data-testid="rule-submit"
        @click="submit"
      >
        完成
      </el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { OptionItem } from '@/components/constraints/types'
import RuleSentenceEditor from '@/components/rules/RuleSentenceEditor.vue'
import {
  describeRule,
  sentenceProblem,
  type CardSentenceInput,
  type PeriodOption,
  type RuleCard,
  type RuleNames,
} from '@/rules/compiler'
import {
  blankFormSentence,
  formSentenceFrom,
  formSentenceInput,
  type FormSentence,
} from '@/rules/form'

const props = defineProps<{
  initial: RuleCard | null
  teachers: OptionItem[]
  classes: OptionItem[]
  courses: OptionItem[]
  days: number[]
  periods: PeriodOption[]
  teacherGroups?: { id: number; name: string }[]
  createTeacherGroup?: (name: string) => Promise<{ id: number; name: string } | null>
  courseGroups?: { id: number; name: string }[]
  createCourseGroup?: (name: string) => Promise<{ id: number; name: string } | null>
}>()

const emit = defineEmits<{
  submit: [payload: CardSentenceInput[]]
  cancel: []
}>()

const sentences = ref<FormSentence[]>(
  props.initial && props.initial.sentences.length > 0
    ? props.initial.sentences.map(formSentenceFrom)
    : [blankFormSentence()],
)
/** Remounts the sentence editors after add/remove, so their local copies cannot
 *  point at a neighbour's sentence when the indexes shift. */
const editorVersion = ref(0)

const names = computed<RuleNames>(() => ({
  teacher: (id: number) => props.teachers.find((item) => item.id === id)?.name ?? `教师${id}`,
  course: (id: number) => props.courses.find((item) => item.id === id)?.name ?? `课程${id}`,
  klass: (id: number) => props.classes.find((item) => item.id === id)?.name ?? `班级${id}`,
  teacherGroup: (id: number) => props.teacherGroups?.find((item) => item.id === id)?.name ?? '教师组',
  courseGroup: (id: number) => props.courseGroups?.find((item) => item.id === id)?.name ?? '课程组',
}))

const classIds = computed(() => props.classes.map((item) => item.id))

const preview = computed(() =>
  sentences.value
    .map((sentence) => describeRule(sentence, names.value, props.periods))
    .join('；'),
)

const problems = computed(() =>
  sentences.value
    .map((sentence, index) => {
      const problem = sentenceProblem(sentence, classIds.value)
      if (problem === '') {
        return ''
      }
      return sentences.value.length > 1 ? `第 ${index + 1} 句${problem}` : problem
    })
    .filter((problem) => problem !== ''),
)

function addSentence() {
  if (sentences.value.length < 3) {
    sentences.value.push(blankFormSentence())
    editorVersion.value += 1
  }
}

function updateSentence(index: number, next: FormSentence) {
  sentences.value[index] = next
}

function removeSentence(index: number) {
  if (sentences.value.length > 1) {
    sentences.value.splice(index, 1)
    editorVersion.value += 1
  }
}

function submit() {
  if (problems.value.length > 0) {
    return
  }
  const payload: CardSentenceInput[] = []
  for (const sentence of sentences.value) {
    const input = formSentenceInput(sentence)
    if (!input) {
      return
    }
    payload.push({ key: sentence.key, input })
  }
  emit('submit', payload)
}
</script>

<style scoped>
.rule-form {
  border-radius: var(--paiban-radius, 8px);
  margin-bottom: 14px;
}

.form-title {
  margin: 0 0 4px;
  font-size: 16px;
}

.form-hint {
  margin: 0 0 10px;
  color: var(--paiban-muted, #6b7280);
  font-size: 12px;
}

.add-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}

.hint,
.missing {
  color: var(--el-color-warning, #e6a23c);
  font-size: 12px;
}

.preview {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light, #f5f7fa);
  color: var(--paiban-ink, #1f2937);
  font-size: 14px;
}

.missing {
  margin: 8px 0 0;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
}
</style>
