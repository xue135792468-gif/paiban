import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import ConstraintMigrationCard from './ConstraintMigrationCard.vue'
import type {
  MigrationEvaluation,
  MigrationPreview,
  MigrationReport,
  MigrationRow,
} from '@/api/constraintMigration'

const api = vi.hoisted(() => ({
  fetchMigrationPreview: vi.fn(),
  fetchMigrationReport: vi.fn(),
  runConstraintMigration: vi.fn(),
}))

vi.mock('@/api/constraintMigration', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/constraintMigration')>()
  return { ...actual, ...api }
})

function row(overrides: Partial<MigrationRow> = {}): MigrationRow {
  return {
    oldType: 'TEACHER_CONFLICT',
    oldTypeName: '教师不冲突',
    oldEntryId: 0,
    action: 'RETAIN',
    newType: 'TEACHER_CONFLICT',
    groupKey: null,
    params: {},
    note: '保留；类型与参数不变',
    requiresReview: false,
    ...overrides,
  }
}

const reviewRow = row({
  oldType: 'TEACHER_UNAVAILABLE',
  oldTypeName: '教师禁排时段',
  oldEntryId: 5,
  action: 'REVIEW',
  newType: 'TEACHER_UNAVAILABLE',
  requiresReview: true,
  note: '参数「teacherId」引用的教师#999 不存在或已删除，需人工确认',
})

const disabledRow = row({
  oldType: 'ROOM_CONFLICT',
  oldTypeName: '教室不冲突',
  action: 'DISABLE',
  newType: null,
  note: '产品已删除该类型：停用旧配置，参数保留可查',
})

function preview(overrides: Partial<MigrationPreview> = {}): MigrationPreview {
  return {
    semesterId: 7,
    configCount: 26,
    entryCount: 1,
    settingsFingerprint: 'abcdef0123456789',
    retained: 19,
    converted: 0,
    disabled: 7,
    review: 1,
    rows: [row(), disabledRow, reviewRow],
    ...overrides,
  }
}

function evaluation(overrides: Partial<MigrationEvaluation> = {}): MigrationEvaluation {
  return {
    skipped: false,
    lessons: 2,
    hardConflicts: 1,
    softPenalty: 0,
    settingsFingerprint: 'abcdef0123456789',
    configCount: 26,
    enabledConfigCount: 26,
    entryCount: 1,
    enabledEntryCount: 1,
    retainedRows: 20,
    retainedDigest: 'fedcba9876543210',
    ...overrides,
  }
}

function report(overrides: Partial<MigrationReport> = {}): MigrationReport {
  return {
    runId: 'run-1',
    semesterId: 7,
    status: 'DONE',
    startedAt: '2026-11-04T02:00:00Z',
    finishedAt: '2026-11-04T02:00:01Z',
    runBy: 'admin',
    retained: 19,
    converted: 0,
    disabled: 7,
    review: 1,
    before: evaluation(),
    after: evaluation({ enabledConfigCount: 19 }),
    rows: [row(), disabledRow, reviewRow],
    ...overrides,
  }
}

function mountCard(canManage = true) {
  return mount(ConstraintMigrationCard, {
    props: { semesterId: 7, canManage },
    global: { plugins: [ElementPlus] },
  })
}

describe('ConstraintMigrationCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows the empty state and cannot run when the semester has no old rows', async () => {
    api.fetchMigrationPreview.mockResolvedValue(preview({ rows: [] }))
    api.fetchMigrationReport.mockResolvedValue(null)

    const wrapper = mountCard()
    await flushPromises()

    expect(wrapper.find('[data-testid="constraint-migration-empty"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="constraint-migration-run"]').attributes('disabled'))
      .toBeDefined()
  })

  it('shows the preview counts, asks for confirmation and renders the report', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    api.fetchMigrationPreview.mockResolvedValue(preview())
    api.fetchMigrationReport.mockResolvedValue(null)
    api.runConstraintMigration.mockResolvedValue(report())

    const wrapper = mountCard()
    await flushPromises()

    expect(wrapper.get('[data-testid="constraint-migration-counts"]').text()).toContain('19')
    expect(wrapper.text()).toContain('7 停用')

    await wrapper.get('[data-testid="constraint-migration-run"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining('旧数据不会被删除'),
      '运行旧规则迁移',
      expect.anything(),
    )
    expect(api.runConstraintMigration).toHaveBeenCalledWith(7)
    expect(wrapper.find('[data-testid="constraint-migration-report"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('硬冲突')
    expect(wrapper.text()).toContain('启用配置')
    expect(wrapper.text()).toContain('19/26')
    // The retained side is compared by digest, not only by the fingerprint that
    // ignores paused rows.
    expect(wrapper.text()).toContain('保留摘要')
    expect(wrapper.text()).toContain('fedcba98')
    // The page must learn about the migration; otherwise its version stays stale.
    expect(wrapper.emitted('migrated')).toHaveLength(1)

    const rows = wrapper.findAll('[data-testid="constraint-migration-report-rows"] tbody tr')
    expect(rows).toHaveLength(3)
    expect(rows[0].text()).toContain('待确认')
    expect(rows[0].text()).toContain('参数「teacherId」')
  })

  it('does not claim a fixed reason for review rows', async () => {
    const groupedReview = {
      ...reviewRow,
      note: '条目缺少分组标识（groupKey），无法确认它属于哪条多时段规则，需人工确认',
    }
    api.fetchMigrationPreview.mockResolvedValue(
      preview({ review: 1, rows: [row(), disabledRow, groupedReview] }),
    )
    api.fetchMigrationReport.mockResolvedValue(
      report({ rows: [row(), disabledRow, groupedReview] }),
    )

    const wrapper = mountCard()
    await flushPromises()

    const source = wrapper.get('[data-testid="constraint-migration-source"]').text()
    expect(source).toContain('1 条无法自动映射')
    // The reason comes from the backend note, never from a hardcoded guess.
    expect(source).not.toContain('教师/课程/班级已不存在')
    expect(wrapper.get('[data-testid="constraint-migration-report-rows"]').text())
      .toContain('缺少分组标识')
  })

  it('renders the stored report and marks a missing draft in the comparison', async () => {
    api.fetchMigrationPreview.mockResolvedValue(preview())
    api.fetchMigrationReport.mockResolvedValue(
      report({ before: evaluation({ skipped: true }), after: evaluation({ skipped: true }) }),
    )

    const wrapper = mountCard()
    await flushPromises()

    expect(wrapper.find('[data-testid="constraint-migration-comparison-skipped"]').exists())
      .toBe(true)
    expect(wrapper.find('[data-testid="constraint-migration-comparison"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('逐条处理清单')
  })

  it('keeps the run button disabled for a user who cannot manage constraints', async () => {
    api.fetchMigrationPreview.mockResolvedValue(preview())
    api.fetchMigrationReport.mockResolvedValue(null)

    const wrapper = mountCard(false)
    await flushPromises()

    expect(wrapper.get('[data-testid="constraint-migration-run"]').attributes('disabled'))
      .toBeDefined()
  })
})
