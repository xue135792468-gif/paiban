import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { ElTooltip } from 'element-plus'
import InfoTooltip from './InfoTooltip.vue'
import { TOOLTIP_KEYS, TOOLTIPS, type TooltipKey } from '@/help/tooltips'

/** Keys the phase-4 contract (§3) requires every ticket to expose. */
const CONTRACT_KEYS: TooltipKey[] = [
  'constraint.weight',
  'constraint.hard',
  'constraint.param',
  'course.consecutive',
  'assignment.weeklyLessons',
  'substitution.approvalMode',
  'substitution.effectiveDate',
  'timetable.view',
  'timetable.substitutionMark',
  'settings.schoolType',
  'settings.aiProvider',
  'settings.platformSwitch',
]

function mountTooltip(field: TooltipKey) {
  return mount(InfoTooltip, { props: { field } })
}

describe('InfoTooltip', () => {
  it('hands the configured copy to el-tooltip', () => {
    const wrapper = mountTooltip('constraint.weight')
    const tooltip = wrapper.findComponent(ElTooltip)

    expect(tooltip.exists()).toBe(true)
    expect(tooltip.props('content')).toBe(TOOLTIPS['constraint.weight'])
  })

  it('picks the copy per field instead of a fixed string', () => {
    const weight = mountTooltip('constraint.weight')
    const hard = mountTooltip('constraint.hard')

    const weightContent = weight.findComponent(ElTooltip).props('content')
    const hardContent = hard.findComponent(ElTooltip).props('content')

    expect(weightContent).toBe(TOOLTIPS['constraint.weight'])
    expect(hardContent).toBe(TOOLTIPS['constraint.hard'])
    expect(weightContent).not.toBe(hardContent)
  })

  it('is reachable by keyboard and screen readers', () => {
    const wrapper = mountTooltip('settings.schoolType')
    const trigger = wrapper.get('[aria-label]')

    expect(trigger.attributes('aria-label')).toContain('字段说明')
    expect(trigger.attributes('tabindex')).toBe('0')
  })
})

describe('TOOLTIPS', () => {
  it('has non-empty, reasonably sized copy for every declared field', () => {
    expect(TOOLTIP_KEYS.length).toBeGreaterThan(0)

    for (const key of TOOLTIP_KEYS) {
      const copy = TOOLTIPS[key]
      expect(typeof copy, key).toBe('string')
      expect(copy.trim().length, key).toBeGreaterThanOrEqual(6)
      expect(copy.length, key).toBeLessThanOrEqual(120)
    }
  })

  it('covers every key the contract lists', () => {
    for (const key of CONTRACT_KEYS) {
      expect(TOOLTIP_KEYS).toContain(key)
      expect(TOOLTIPS[key].trim().length).toBeGreaterThan(0)
    }
  })

  it('explains the constraint weight in the wording the spec asks for', () => {
    expect(TOOLTIPS['constraint.weight']).toContain('0-100')
    expect(TOOLTIPS['constraint.weight']).toContain('越高越优先')
  })
})
