import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import DeleteImpactDialog from './DeleteImpactDialog.vue'
import type { DeleteImpact } from '@/api/lifecycle'

const mocks = vi.hoisted(() => ({
  getDeleteImpact: vi.fn(),
  deleteEntity: vi.fn(),
  disableEntity: vi.fn(),
  enableEntity: vi.fn(),
}))

vi.mock('@/api/lifecycle', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/lifecycle')>()
  return {
    ...actual,
    getDeleteImpact: mocks.getDeleteImpact,
    deleteEntity: mocks.deleteEntity,
    disableEntity: mocks.disableEntity,
    enableEntity: mocks.enableEntity,
  }
})

function impact(overrides: Partial<DeleteImpact> = {}): DeleteImpact {
  return {
    entityType: 'COURSE',
    entityId: 5,
    label: '语文',
    publishedRefs: 0,
    substitutionRefs: 0,
    draftRefs: 0,
    assignmentRefs: 0,
    ruleRefs: 0,
    canDelete: true,
    canDisable: true,
    cascadeAssignments: 0,
    cascadeClasses: 0,
    ...overrides,
  }
}

async function mountDialog(entityType: 'teacher' | 'course' = 'course') {
  const wrapper = mount(DeleteImpactDialog, {
    props: {
      modelValue: true,
      entityType,
      entityId: 5,
      entityLabel: '语文',
    },
    global: { plugins: [ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

describe('DeleteImpactDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('offers only disable when published or substitution records reference the entity', async () => {
    mocks.getDeleteImpact.mockResolvedValue(
      impact({ canDelete: false, publishedRefs: 3, assignmentRefs: 1 }),
    )
    const wrapper = await mountDialog()

    expect(wrapper.find('[data-testid="delete-impact-disable"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="delete-impact-delete"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="delete-impact-cascade"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('只能停用')
  })

  it('calls CASCADE with the entity when assignments would be left behind', async () => {
    mocks.getDeleteImpact.mockResolvedValue(impact({ cascadeAssignments: 2 }))
    mocks.deleteEntity.mockResolvedValue({ batchId: 'b1', deletedCount: 3 })
    const wrapper = await mountDialog()

    const cascade = wrapper.get('[data-testid="delete-impact-cascade"]')
    expect(cascade.text()).toContain('2 条授课任务')
    await cascade.trigger('click')
    await flushPromises()

    expect(mocks.deleteEntity).toHaveBeenCalledWith('course', 5, 'CASCADE')
    expect(wrapper.emitted('updated')).toHaveLength(1)
  })

  it('deletes without cascade when nothing depends on the entity', async () => {
    mocks.getDeleteImpact.mockResolvedValue(impact())
    mocks.deleteEntity.mockResolvedValue({ batchId: 'b2', deletedCount: 1 })
    const wrapper = await mountDialog()

    await wrapper.get('[data-testid="delete-impact-delete"]').trigger('click')
    await flushPromises()

    expect(mocks.deleteEntity).toHaveBeenCalledWith('course', 5, 'DELETE')
  })
})
