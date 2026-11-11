<template>
  <div>
    <div class="toolbar">
      <div>
        <h2>调代课</h2>
        <p class="subtitle">代课由老师提交、教务审批后生效；调课请在调课工作台由教务发起。</p>
      </div>
      <el-button @click="reloadAll">刷新</el-button>
    </div>

    <el-alert
      v-if="loadError"
      class="load-error"
      :title="loadError"
      type="warning"
      :closable="false"
      show-icon
    />

    <el-tabs v-model="activeTab">
      <el-tab-pane label="我的调代" name="mine">
        <SubstitutionRequestForm :my-teacher-id="myTeacherId" :teachers="teachers" @submitted="loadMine" />
        <SubstitutionMineTable ref="mineRef" :requests="myRequests" @changed="reloadAfterChange" />
      </el-tab-pane>

      <el-tab-pane v-if="canApprove" label="待审批" name="pending">
        <SubstitutionApprovalPanel
          :requests="pendingRequests"
          :teachers="teachers"
          :can-approve="canApprove"
          @changed="reloadAfterChange"
        />
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
import { serverMessage } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'
import SubstitutionRequestForm from '@/components/substitution/SubstitutionRequestForm.vue'
import SubstitutionApprovalPanel from '@/components/substitution/SubstitutionApprovalPanel.vue'
import SubstitutionMineTable from '@/components/substitution/SubstitutionMineTable.vue'
import SubstitutionRecordsPanel from '@/components/substitution/SubstitutionRecordsPanel.vue'
import type { RequestRow, Teacher } from '@/components/substitution/types'

const auth = useAuthStore()
const route = useRoute()
const canViewRecords = computed(() =>
  auth.hasAnyPermission(['substitution:view', 'substitution:view:class']),
)
const canViewAllRecords = computed(() => auth.hasPermission('substitution:view'))
const canApprove = computed(() => auth.hasPermission('substitution:approve'))
const myTeacherId = computed(() => auth.user?.teacherId ?? null)

/** The draft page's "re-process substitutions" prompt links straight to the records tab. */
const TABS = ['mine', 'pending', 'records']
const requestedTab = typeof route.query.tab === 'string' ? route.query.tab : ''
const permittedTab = (tab: string) =>
  tab === 'pending'
    ? canApprove.value
    : tab === 'records'
      ? canViewRecords.value
      : true
const activeTab = ref(
  TABS.includes(requestedTab) && permittedTab(requestedTab) ? requestedTab : 'mine',
)
const teachers = ref<Teacher[]>([])
const classes = ref<Array<{ id: number; name: string; headTeacherId?: number | null }>>([])
const classOptions = computed(() =>
  canViewAllRecords.value
    ? classes.value
    : classes.value.filter((item) => item.headTeacherId === auth.user?.teacherId),
)
const myRequests = ref<RequestRow[]>([])
const pendingRequests = ref<RequestRow[]>([])
/** 没有学期等前置条件缺失时，后端会直接拒绝；页面要说清楚而不是静默空表。 */
const loadError = ref('')
const mineRef = ref<InstanceType<typeof SubstitutionMineTable> | null>(null)
const recordsRef = ref<InstanceType<typeof SubstitutionRecordsPanel> | null>(null)

async function loadMine() {
  try {
    const { data } = await http.get('/substitution-requests', { params: { mine: true } })
    myRequests.value = data
    loadError.value = ''
  } catch (error) {
    myRequests.value = []
    loadError.value = serverMessage(error, '调代数据加载失败，请稍后重试')
  }
}

/** 管理待批复用显式 status=SUBMITTED；共享查询的默认值不动。 */
async function loadPending() {
  if (!canApprove.value) return
  try {
    const { data } = await http.get('/substitution-requests', { params: { status: 'SUBMITTED' } })
    pendingRequests.value = data
  } catch (error) {
    pendingRequests.value = []
    loadError.value = serverMessage(error, '待审批列表加载失败，请稍后重试')
  }
}

async function reloadAfterChange() {
  await Promise.all([loadMine(), loadPending()])
}

async function reloadAll() {
  await Promise.all([loadMine(), loadPending(), recordsRef.value?.reload()])
}

onMounted(async () => {
  const [teacherRes, classRes] = await Promise.all([
    http.get('/teachers/options'),
    http.get('/classes'),
  ])
  teachers.value = teacherRes.data
  classes.value = classRes.data.filter((item: { active?: boolean }) => item.active !== false)
  await reloadAfterChange()
  const requestId = Number(route.query.requestId)
  if (requestId) {
    await nextTick()
    await mineRef.value?.openHistory(requestId)
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
