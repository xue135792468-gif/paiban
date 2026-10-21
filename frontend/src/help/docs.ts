import { HELP_SLUGS, type HelpSlug } from './links'

export type HelpGroupTitle = '功能模块' | '场景流程'

export interface HelpDoc {
  slug: HelpSlug
  title: string
  summary: string
  group: HelpGroupTitle
  /** Raw markdown, bundled at build time. */
  body: string
}

export interface HelpGroup {
  title: HelpGroupTitle
  docs: HelpDoc[]
}

export interface HelpSearchResult {
  doc: HelpDoc
  snippet: string
}

const bodies = import.meta.glob('./docs/*.md', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const META: Record<HelpSlug, { title: string; summary: string; group: HelpGroupTitle }> = {
  foundation: {
    title: '基础数据',
    summary: '年级、行政班、教师、教室、课程与批量导入',
    group: '功能模块',
  },
  scheduling: {
    title: '排课',
    summary: '约束配置、提交排课、草稿微调与发布',
    group: '功能模块',
  },
  'manual-adjust': {
    title: '手工调课',
    summary: '草稿上的同班换位、跨班换人与撤销',
    group: '功能模块',
  },
  substitution: {
    title: '调代',
    summary: '调代申请、审批与通知',
    group: '功能模块',
  },
  timetable: {
    title: '课表查看',
    summary: '四视角切换、调代标记与导出',
    group: '功能模块',
  },
  settings: {
    title: '设置',
    summary: '学校类型、AI 供应商与平台切换',
    group: '功能模块',
  },
  'ai-import': {
    title: 'AI 数据导入',
    summary: '教师与课表的通用导入：原始网格、目标识别、名称映射与草稿生成',
    group: '功能模块',
  },
  'flow-term-start': {
    title: '新学期开学流程',
    summary: '从初始化向导到第一次自动排课的完整步骤',
    group: '场景流程',
  },
  'flow-substitution': {
    title: '日常调代流程',
    summary: '从提交申请到审批生效，谁在什么时间做什么',
    group: '场景流程',
  },
  'flow-term-end': {
    title: '期末统计流程',
    summary: '查看本学期课时、代课次数并归档',
    group: '场景流程',
  },
}

export const HELP_DOCS: HelpDoc[] = HELP_SLUGS.map((slug) => ({
  slug,
  ...META[slug],
  body: bodies[`./docs/${slug}.md`] ?? '',
}))

export const HELP_GROUPS: HelpGroup[] = [
  { title: '功能模块', docs: HELP_DOCS.filter((doc) => doc.group === '功能模块') },
  { title: '场景流程', docs: HELP_DOCS.filter((doc) => doc.group === '场景流程') },
]

export function findDoc(slug: string): HelpDoc | undefined {
  return HELP_DOCS.find((doc) => doc.slug === slug)
}

/** Plain contains search over titles and bodies; title hits rank first. */
export function searchDocs(query: string): HelpSearchResult[] {
  const needle = query.trim().toLowerCase()
  if (!needle) return []

  const titleHits: HelpSearchResult[] = []
  const bodyHits: HelpSearchResult[] = []

  for (const doc of HELP_DOCS) {
    if (doc.title.toLowerCase().includes(needle)) {
      titleHits.push({ doc, snippet: doc.summary })
      continue
    }
    const index = doc.body.toLowerCase().indexOf(needle)
    if (index >= 0) {
      bodyHits.push({ doc, snippet: snippetAround(doc.body, index, needle.length) })
    }
  }

  return [...titleHits, ...bodyHits]
}

function snippetAround(body: string, index: number, length: number): string {
  const start = Math.max(0, index - 30)
  const end = Math.min(body.length, index + length + 50)
  const prefix = start > 0 ? '…' : ''
  const suffix = end < body.length ? '…' : ''
  return `${prefix}${body.slice(start, end).replace(/\s+/g, ' ').trim()}${suffix}`
}
