<template>
  <div v-loading="loading">
    <div class="toolbar">
      <div>
        <h2>调代课</h2>
        <p class="subtitle">调课是同一行政班内的置换环，代课是单向补位；两者都由教务确认后生效。</p>
      </div>
      <el-button v-if="hasSemester" @click="reloadAll">刷新</el-button>
    </div>

    <EmptyStateGuide
      v-if="!loading && !hasSemester"
      title="还没有学期"
      description="请先创建并设置当前学期，再提交或查看调代课。"
      button-text="去创建学期"
      button-to="/data/semesters"
      help-slug="substitution"
      help-anchor="apply"
    />

    <el-tabs v-else-if="hasSemester" v-model="activeTab">
      <el-tab-pane label="我的调代" name="mine">
        <SubstitutionRequestForm :my-teacher-id="myTeacherId" :teachers="teachers" @submitted="loadMine" />
        <SubstitutionMineTable ref="mineRef" :requests="myRequests" />
      </el-tab-pane>


      <el-tab-pane v-if="canViewRecords" label="调代记录" name="records">
        <SubstitutionRecordsPanel
          ref="recordsRef"
          :class-options="classOptions"
          :teachers="teachers"
          :can-view-all="canViewAllRecords"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { http } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import SubstitutionRequestForm from '@/components/substitution/SubstitutionRequestForm.vue'
import SubstitutionMineTable from '@/components/substitution/SubstitutionMineTable.vue'
import SubstitutionRecordsPanel from '@/components/substitution/SubstitutionRecordsPanel.vue'
import type { RequestRow, Teacher } from '@/components/substitution/types'

const auth = useAuthStore()
const route = useRoute()
const canViewRecords = computed(() =>
  auth.hasAnyPermission(['substitution:view', 'substitution:view:class']),
)
const canViewAllRecords = computed(() => auth.hasPermission('substitution:view'))
const myTeacherId = computed(() => auth.user?.teacherId ?? null)

/** The draft page's "re-process substitutions" prompt links straight to the records tab. */
const TABS = ['mine', 'records']
const requestedTab = typeof route.query.tab === 'string' ? route.query.tab : ''
const activeTab = ref(TABS.includes(requestedTab) ? requestedTab : 'mine')
const teachers = ref<Teacher[]>([])
const classes = ref<Array<{ id: number; name: string; headTeacherId?: number | null }>>([])
const classOptions = computed(() =>
  canViewAllRecords.value
    ? classes.value
    : classes.value.filter((item) => item.headTeacherId === auth.user?.teacherId),
)
const myRequests = ref<RequestRow[]>([])
const loading = ref(true)
const hasSemester = ref(false)
const mineRef = ref<InstanceType<typeof SubstitutionMineTable> | null>(null)
const recordsRef = ref<InstanceType<typeof SubstitutionRecordsPanel> | null>(null)

async function loadMine() {
  if (!hasSemester.value) return
  const { data } = await http.get('/substitution-requests', { params: { mine: true } })
  myRequests.value = data
}



async function reloadAll() {
  if (!hasSemester.value) return
  await Promise.all([loadMine(), recordsRef.value?.reload()])
}

async function hasCurrentSemester(): Promise<boolean> {
  try {
    const res = await http.get('/semesters/current', {
      validateStatus: (status) => status < 500,
    })
    return res.status !== 204 && Boolean(res.data?.id)
  } catch {
    return false
  }
}

onMounted(async () => {
  try {
    const semesterReady = await hasCurrentSemester()
    if (!semesterReady) {
      hasSemester.value = false
      teachers.value = []
      classes.value = []
      myRequests.value = []
      return
    }
    const [teacherRes, classRes, mineRes] = await Promise.all([
      http.get('/teachers/options'),
      http.get('/classes'),
      http.get('/substitution-requests', { params: { mine: true } }),
    ])
    teachers.value = teacherRes.data ?? []
    classes.value = (classRes.data ?? []).filter((item: { active?: boolean }) => item.active !== false)
    myRequests.value = mineRes.data ?? []
    hasSemester.value = true
    const requestId = Number(route.query.requestId)
    if (requestId) {
      await nextTick()
      await mineRef.value?.openHistory(requestId)
    }
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 8px;
}
h2 {
  margin: 0 0 4px;
  font-size: 20px;
}
.subtitle {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 13px;
}
</style>
