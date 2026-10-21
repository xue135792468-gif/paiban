/**
 * Slugs of the bundled help documents (`src/help/docs/<slug>.md`).
 * Every feature that links into the help centre goes through `helpLink` so the
 * route shape lives in one place.
 */
export const HELP_SLUGS = [
  'foundation',
  'scheduling',
  'manual-adjust',
  'substitution',
  'timetable',
  'settings',
  'flow-term-start',
  'flow-substitution',
  'flow-term-end',
] as const

export type HelpSlug = (typeof HELP_SLUGS)[number]

export const HELP_INDEX = '/help'

/** Route to a help document, optionally at an anchor declared in the doc. */
export function helpLink(slug: HelpSlug, anchor?: string): string {
  return `${HELP_INDEX}/${slug}${anchor ? `#${anchor}` : ''}`
}
