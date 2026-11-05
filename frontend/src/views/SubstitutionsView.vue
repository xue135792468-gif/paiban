<template>
  <div>
    <div class="toolbar">
      <div>
        <h2>调代课</h2>
        <p class="subtitle">调课是同一行政班内的置换环，代课是单向补位；两者都由教务确认后生效。</p>
      </div>
      <el-button @click="reloadAll">刷新</el-button>
    </div>

    <el-tabs v-model="activeTab">
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
const mineRef = ref<InstanceType<typeof SubstitutionMineTable> | null>(null)
const recordsRef = ref<InstanceType<typeof SubstitutionRecordsPanel> | null>(null)

async function loadMine() {
  const { data } = await http.get('/substitution-requests', { params: { mine: true } })
  myRequests.value = data
}



async function reloadAll() {
  await Promise.all([loadMine(), recordsRef.value?.reload()])
}

onMounted(async () => {
  const [teacherRes, classRes] = await Promise.all([
    http.get('/teachers/options'),
    http.get('/classes'),
  ])
  teachers.value = teacherRes.data
  classes.value = classRes.data.filter((item: { active?: boolean }) => item.active !== false)
  await loadMine()
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
