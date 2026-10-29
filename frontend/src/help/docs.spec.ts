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
  /** 票 09：管理员调代工作台说明，四个锚点对应票据 1-4 条。 */
  'substitution-workbench': ['one-time', 'concepts', 'approve', 'compare'],
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
    expect(HELP_GROUPS[0].docs.map((doc) => doc.slug)).toEqual(HELP_SLUGS.slice(0, 8))
    expect(HELP_GROUPS[1].docs.map((doc) => doc.slug)).toEqual(HELP_SLUGS.slice(8))
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
      '/substitutions/workbench',
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

  it('indexes the AI import guide', () => {
    expect(searchDocs('AI 数据导入').map((result) => result.doc.slug)).toContain('ai-import')
    expect(searchDocs('映射步骤').map((result) => result.doc.slug)).toContain('ai-import')
  })

  it('indexes the import restore and grade inference guidance', () => {
    expect(searchDocs('切页或刷新后继续').map((result) => result.doc.slug)).toContain('ai-import')
    expect(searchDocs('年级推断').map((result) => result.doc.slug)).toContain('ai-import')
  })

  it('covers the background parse states and what to do in each', () => {
    const body = findDoc('ai-import')!.body
    for (const state of ['PARSING', 'PARTIAL', 'ABANDONED']) {
      expect(body).toContain(state)
    }
    for (const action of ['停止解析', '重试失败批次', '放弃导入']) {
      expect(body).toContain(action)
    }
    expect(searchDocs('后台解析').map((result) => result.doc.slug)).toContain('ai-import')
  })

  it('indexes the admin workbench guide (ticket 09)', () => {
    expect(searchDocs('调代工作台').map((result) => result.doc.slug)).toContain(
      'substitution-workbench',
    )
    expect(searchDocs('恢复基线').map((result) => result.doc.slug)).toContain(
      'substitution-workbench',
    )
    // 票据 1-3 条的关键句必须在正文里，供帮助搜索与人工核对。
    const body = findDoc('substitution-workbench')!.body
    expect(body).toContain('下周同一节次自动恢复常规课表')
    expect(body).toContain('开课后不能再改')
    expect(body).toContain('变化清单')
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
