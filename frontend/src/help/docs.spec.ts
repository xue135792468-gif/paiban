import { describe, expect, it } from 'vitest'
import { HELP_DOCS, HELP_GROUPS, findDoc, searchDocs } from './docs'
import { HELP_SLUGS } from './links'

/** Anchors promised to other tickets by design-contract.md §1. */
const REQUIRED_ANCHORS: Record<string, string[]> = {
  foundation: ['grades', 'classes', 'teachers', 'rooms', 'courses', 'import'],
  scheduling: ['constraints', 'assignments', 'run', 'draft', 'publish'],
  substitution: ['apply', 'approve', 'notify'],
  timetable: ['views', 'export', 'substitution-mark'],
  settings: ['school-type', 'ai', 'platform'],
  'flow-term-start': [
    'school',
    'periods',
    'grades',
    'teachers',
    'rooms',
    'courses',
    'assignments',
    'constraints',
    'finish',
  ],
  'flow-substitution': ['apply', 'approve'],
  'flow-term-end': ['stats'],
}

describe('HELP_DOCS', () => {
  it('bundles exactly one document per declared slug, in contract order', () => {
    expect(HELP_DOCS.map((doc) => doc.slug)).toEqual([...HELP_SLUGS])
  })

  it('gives every document a title, a group and a non-empty body', () => {
    for (const doc of HELP_DOCS) {
      expect(doc.title.length).toBeGreaterThan(0)
      expect(['功能模块', '场景流程']).toContain(doc.group)
      expect(doc.body.trim().length).toBeGreaterThan(200)
    }
  })

  it('declares every anchor other tickets link to', () => {
    for (const doc of HELP_DOCS) {
      for (const anchor of REQUIRED_ANCHORS[doc.slug] ?? []) {
        expect(doc.body, `${doc.slug} is missing #${anchor}`).toContain(`<a id="${anchor}"></a>`)
      }
    }
  })

  it('groups documents by feature module and by scenario flow', () => {
    expect(HELP_GROUPS.map((group) => group.title)).toEqual(['功能模块', '场景流程'])
    expect(HELP_GROUPS[0].docs.map((doc) => doc.slug)).toEqual(HELP_SLUGS.slice(0, 5))
    expect(HELP_GROUPS[1].docs.map((doc) => doc.slug)).toEqual(HELP_SLUGS.slice(5))
  })

  it('links to routes that exist in the router', () => {
    const known = [
      '/admin/semesters',
      '/admin/grades',
      '/admin/classes',
      '/admin/teachers',
      '/admin/rooms',
      '/admin/courses',
      '/admin/assignments',
      '/admin/constraints',
      '/admin/ai-import',
      '/admin/scheduling',
      '/timetable',
      '/substitutions',
      '/notifications',
      '/admin/settings',
      '/admin/ai-providers',
      '/admin/ai-history',
      '/admin/users',
      '/onboarding',
    ]
    for (const doc of HELP_DOCS) {
      const links = [...doc.body.matchAll(/\]\((\/[^)#\s]+)/g)].map((match) => match[1])
      for (const link of links) {
        if (link === '/help' || link.startsWith('/help/')) continue
        expect(known, `${doc.slug} links to unknown route ${link}`).toContain(link)
      }
    }
  })
})

describe('findDoc', () => {
  it('resolves a slug to its document', () => {
    expect(findDoc('scheduling')?.title).toContain('排课')
  })

  it('returns undefined for an unknown slug', () => {
    expect(findDoc('nope')).toBeUndefined()
  })
})

describe('searchDocs', () => {
  it('returns nothing for an empty query', () => {
    expect(searchDocs('   ')).toEqual([])
  })

  it('matches document titles', () => {
    const results = searchDocs('课表查看')
    expect(results.map((result) => result.doc.slug)).toContain('timetable')
  })

  it('matches body text and returns a snippet around the hit', () => {
    const results = searchDocs('每周节数')
    const assignmentHit = results.find((result) => result.doc.slug === 'scheduling')
    expect(assignmentHit).toBeTruthy()
    expect(assignmentHit!.snippet).toContain('每周节数')
  })

  it('is case-insensitive for latin text', () => {
    expect(searchDocs('excel').length).toBeGreaterThan(0)
    expect(searchDocs('EXCEL').length).toBeGreaterThan(0)
  })
})
