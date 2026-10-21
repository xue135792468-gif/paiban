import { describe, expect, it } from 'vitest'
import { HELP_SLUGS, helpLink } from './links'

describe('helpLink', () => {
  it('points at the help index for a slug', () => {
    expect(helpLink('foundation')).toBe('/help/foundation')
  })

  it('appends an anchor when given one', () => {
    expect(helpLink('foundation', 'classes')).toBe('/help/foundation#classes')
  })

  it('keeps every declared slug routable', () => {
    for (const slug of HELP_SLUGS) {
      expect(helpLink(slug)).toBe(`/help/${slug}`)
    }
  })
})
