import { describe, expect, it } from 'vitest'
import { findDoc } from '@/help/docs'
import { helpLink } from '@/help/links'
import { WIZARD_HELP_SLUG, WIZARD_STEPS, wizardProgressLabel } from './wizardSteps'

/** Anchors promised for flow-term-start by design-contract.md §1 (教室 retired in product-trim). */
const CONTRACT_ANCHORS = [
  'school',
  'periods',
  'grades',
  'teachers',
  'courses',
  'assignments',
  'constraints',
]

describe('WIZARD_STEPS', () => {
  it('walks the seven wizard steps in the order of the term-start flow', () => {
    expect(WIZARD_STEPS.map((step) => step.helpAnchor)).toEqual(CONTRACT_ANCHORS)
  })

  it('sends every step to a live route of the trimmed IA', () => {
    expect(WIZARD_STEPS.map((step) => step.route)).toEqual([
      '/settings/general',
      '/data/semesters',
      '/data/grades',
      '/data/teachers',
      '/data/courses',
      '/data/assignments',
      '/rules',
    ])
  })

  it('only links to anchors the term-start document actually declares', () => {
    const doc = findDoc('flow-term-start')
    expect(doc).toBeTruthy()

    for (const step of WIZARD_STEPS) {
      expect(doc!.body, `flow-term-start is missing #${step.helpAnchor}`).toContain(
        `<a id="${step.helpAnchor}"></a>`,
      )
      expect(helpLink(WIZARD_HELP_SLUG, step.helpAnchor)).toBe(
        `/help/flow-term-start#${step.helpAnchor}`,
      )
    }
  })

  it('gives every step a title and a description', () => {
    for (const step of WIZARD_STEPS) {
      expect(step.title.trim().length).toBeGreaterThan(0)
      expect(step.description.trim().length).toBeGreaterThan(0)
    }
  })
})

describe('wizardProgressLabel', () => {
  it('numbers the first step against the real step count', () => {
    expect(wizardProgressLabel(0)).toBe('第 1/7 步：学校信息')
  })

  it('follows the current step title', () => {
    expect(wizardProgressLabel(1)).toBe('第 2/7 步：学期与作息表')
  })

  it('numbers the last step', () => {
    expect(wizardProgressLabel(WIZARD_STEPS.length - 1)).toBe('第 7/7 步：排课规则')
  })

  it('clamps out-of-range indexes instead of printing NaN', () => {
    expect(wizardProgressLabel(99)).toBe('第 7/7 步：排课规则')
    expect(wizardProgressLabel(-3)).toBe('第 1/7 步：学校信息')
  })
})
