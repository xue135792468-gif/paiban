import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TOUR_ADVANCED_KEY, TOUR_SEEN_KEY, hasSeenTour, markTourSeen, resetTour, startTour } from './tour'

const driverMock = vi.hoisted(() => {
  const drive = vi.fn()
  const destroy = vi.fn()
  const isActive = vi.fn(() => false)
  const factory = vi.fn(() => ({ drive, destroy, isActive }))
  return { drive, destroy, isActive, factory }
})

vi.mock('driver.js', () => ({ driver: driverMock.factory }))

function addTourTargets(paths: string[]) {
  for (const path of paths) {
    const element = document.createElement('div')
    element.setAttribute('data-tour', path)
    document.body.appendChild(element)
  }
}

function lastConfig() {
  const calls = driverMock.factory.mock.calls as unknown as Array<[Record<string, any>]>
  return calls[calls.length - 1][0]
}

describe('tour progress', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('uses stable storage keys', () => {
    expect(TOUR_SEEN_KEY).toBe('paiban.tour.seen')
    expect(TOUR_ADVANCED_KEY).toBe('paiban.tour.advanced')
  })

  it('starts unseen and records each stage independently', () => {
    expect(hasSeenTour('basic')).toBe(false)
    expect(hasSeenTour('advanced')).toBe(false)

    markTourSeen('basic')

    expect(hasSeenTour('basic')).toBe(true)
    expect(hasSeenTour('advanced')).toBe(false)

    markTourSeen('advanced')

    expect(hasSeenTour('advanced')).toBe(true)
  })

  it('forgets both stages on reset', () => {
    markTourSeen('basic')
    markTourSeen('advanced')

    resetTour()

    expect(hasSeenTour('basic')).toBe(false)
    expect(hasSeenTour('advanced')).toBe(false)
  })
})

describe('startTour', () => {
  beforeEach(() => {
    localStorage.clear()
    document.body.innerHTML = ''
    driverMock.factory.mockClear()
    driverMock.drive.mockClear()
  })

  it('walks the six basic steps through the sidebar entries', () => {
    addTourTargets(['/admin/classes', '/admin/scheduling', '/substitutions', '/timetable'])

    startTour('basic')

    const config = lastConfig()
    const steps = config.steps as Array<{ element?: string; popover?: { title?: string } }>
    expect(steps).toHaveLength(6)
    expect(steps[0].element).toBeUndefined()
    expect(steps[0].popover?.title).toBe('欢迎使用排课系统')
    expect(steps[1].element).toBe('[data-tour="/admin/classes"]')
    expect(steps[2].element).toBe('[data-tour="/admin/scheduling"]')
    expect(steps[3].element).toBe('[data-tour="/substitutions"]')
    expect(steps[4].element).toBe('[data-tour="/timetable"]')
    expect(steps[5].element).toBeUndefined()
    expect(driverMock.drive).toHaveBeenCalledTimes(1)
  })

  it('drops steps whose target is not rendered instead of letting driver.js fail', () => {
    addTourTargets(['/admin/classes', '/timetable'])

    startTour('basic')

    const steps = lastConfig().steps as Array<{ element?: string }>
    expect(steps.map((step) => step.element)).toEqual([
      undefined,
      '[data-tour="/admin/classes"]',
      '[data-tour="/timetable"]',
      undefined,
    ])
  })

  it('marks the basic tour as seen once it is destroyed', () => {
    startTour('basic')

    const onDestroyed = lastConfig().onDestroyed as () => void
    onDestroyed()

    expect(hasSeenTour('basic')).toBe(true)
  })

  it('marks the tour as seen when the skip button is clicked', () => {
    startTour('basic')

    const onCloseClick = lastConfig().onCloseClick as (
      element: unknown,
      step: unknown,
      opts: { driver: { destroy: () => void } },
    ) => void
    onCloseClick(undefined, {}, { driver: { destroy: driverMock.destroy } })

    expect(hasSeenTour('basic')).toBe(true)
    expect(driverMock.destroy).toHaveBeenCalled()
  })

  it('marks the tour as seen when the done button is clicked', () => {
    startTour('basic')

    const onDoneClick = lastConfig().onDoneClick as (
      element: unknown,
      step: unknown,
      opts: { driver: { destroy: () => void } },
    ) => void
    onDoneClick(undefined, {}, { driver: { destroy: driverMock.destroy } })

    expect(hasSeenTour('basic')).toBe(true)
    expect(driverMock.destroy).toHaveBeenCalled()
  })

  it('marks the advanced tour as seen once it is destroyed', () => {
    addTourTargets(['/admin/constraints', '/admin/scheduling', '/timetable'])

    startTour('advanced')

    const onDestroyed = lastConfig().onDestroyed as () => void
    onDestroyed()

    expect(hasSeenTour('advanced')).toBe(true)
  })

  it('builds the three advanced steps for constraints, scheduling and export', () => {
    addTourTargets(['/admin/constraints', '/admin/scheduling', '/timetable'])

    startTour('advanced')

    const steps = lastConfig().steps as Array<{ element?: string }>
    expect(steps.map((step) => step.element)).toEqual([
      '[data-tour="/admin/constraints"]',
      '[data-tour="/admin/scheduling"]',
      '[data-tour="/timetable"]',
    ])
  })

  it('replaces a running tour instead of stacking overlays', () => {
    startTour('basic')
    driverMock.isActive.mockReturnValue(true)

    startTour('basic')

    expect(driverMock.destroy).toHaveBeenCalledTimes(1)
  })
})
