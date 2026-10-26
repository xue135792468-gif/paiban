import type {
  ConstraintConfigDto,
  TemplateApplyPreview,
  TemplateItem,
  TemplateReferenceIssue,
} from './types'

/** What one apply restored, counted from the response the server actually wrote. */
export interface ApplyResult {
  configCount: number
  entryCount: number
}

/**
 * Counts of a `POST /constraint-templates/{id}/apply` response. The page shows these
 * instead of its own totals: the number it announces is the number the server stored.
 */
export function applyResultOf(configs: ConstraintConfigDto[] | null | undefined): ApplyResult {
  const rows = configs ?? []
  return {
    configCount: rows.length,
    entryCount: rows.reduce((total, row) => total + (row.entries?.length ?? 0), 0),
  }
}

export function applyResultText(name: string, result: ApplyResult): string {
  return `已套用模板「${name}」：恢复 ${result.configCount} 类配置、${result.entryCount} 条条目`
}

/** `名称（12 类 · 18 条）` — the select has to say how big a template is. */
export function templateOptionLabel(item: TemplateItem): string {
  return `${item.name}（${item.configCount} 类 · ${item.entryCount} 条）`
}

export interface IssueRow {
  key: string
  typeLabel: string
  location: string
  fieldLabel: string
  objectLabel: string
  reason: string
  /** Classes can be pointed at a class of the target semester; teachers/courses cannot. */
  rebindable: boolean
}

/** One invalid reference as the dialog renders it (never a blank cell). */
export function issueRow(issue: TemplateReferenceIssue): IssueRow {
  return {
    key: issueKey(issue),
    typeLabel: `${issue.typeName}（${issue.type}）`,
    location: issue.position > 0 ? `第 ${issue.position} 条` : '整条配置',
    fieldLabel: issue.fieldLabel || issue.field,
    objectLabel: issue.name
      ? `${issue.name}（#${issue.objectId}）`
      : `#${issue.objectId}`,
    reason: issue.reason,
    rebindable: issue.field === 'classId',
  }
}

export function issueKey(issue: TemplateReferenceIssue): string {
  return `${issue.type}#${issue.position}#${issue.field}#${issue.objectId}`
}

export function issueRows(issues: TemplateReferenceIssue[]): IssueRow[] {
  return issues.map(issueRow)
}

/**
 * The issues that still block an apply: everything that cannot be rebound (a deleted
 * teacher or course) plus every class the user has not pointed at a new class yet.
 * Nothing is guessed — an empty selection is a blocking state, not an implicit match.
 */
export function blockingIssues(
  issues: TemplateReferenceIssue[],
  selections: Record<string, number | null>,
): TemplateReferenceIssue[] {
  return issues.filter((issue) => !issueRow(issue).rebindable || !selections[issueKey(issue)])
}

/** `{ "<template class id>": <target class id> }` for the apply body. */
export function classMappingOf(
  issues: TemplateReferenceIssue[],
  selections: Record<string, number | null>,
): Record<string, number> {
  const mapping: Record<string, number> = {}
  issues.filter((issue) => issueRow(issue).rebindable).forEach((issue) => {
    const chosen = selections[issueKey(issue)]
    if (chosen) {
      mapping[String(issue.objectId)] = chosen
    }
  })
  return mapping
}

/** The line under the list: how many rows still need a decision. */
export function blockingSummary(
  issues: TemplateReferenceIssue[],
  selections: Record<string, number | null>,
): string {
  const blocking = blockingIssues(issues, selections)
  if (blocking.length === 0) {
    return '全部引用都已处理，可以按所选班级重新套用。'
  }
  const unrebindable = blocking.filter((issue) => !issueRow(issue).rebindable).length
  const unselected = blocking.length - unrebindable
  const parts: string[] = []
  if (unselected > 0) {
    parts.push(`${unselected} 处行政班引用还需选择当前学期的班级`)
  }
  if (unrebindable > 0) {
    parts.push(`${unrebindable} 处对象（教师/课程）已不存在，无法通过套用修复`)
  }
  return `${parts.join('；')}。`
}

/**
 * The one report shape both template calls produce. The dry run returns it in a 200
 * body; an apply refusal returns the same two lists in its 409 body (plus a `message`
 * the page only uses when there is nothing structured to show). The page therefore
 * parses one shape and renders one way, whichever call produced it.
 */
export interface TemplateReport {
  /** How much the dry run would restore; null on a refusal, which restores nothing. */
  configCount: number | null
  entryCount: number | null
  /** Reasons the save would be refused that are not object references (slots, ranges…). */
  errors: string[]
  /** Object references the target semester cannot take, row by row. */
  invalidReferences: TemplateReferenceIssue[]
}

/** Reads either body (preview 200 or apply 409) into the one report shape. */
export function templateReportOf(body: unknown): TemplateReport {
  const source = (body ?? {}) as Partial<TemplateApplyPreview>
  return {
    configCount: typeof source.configCount === 'number' ? source.configCount : null,
    entryCount: typeof source.entryCount === 'number' ? source.entryCount : null,
    errors: Array.isArray(source.errors)
      ? source.errors.filter((message): message is string => typeof message === 'string')
      : [],
    invalidReferences: Array.isArray(source.invalidReferences) ? source.invalidReferences : [],
  }
}

/** The blocking reason of a report, or null when the apply can proceed. */
export function blockedReasonOf(report: TemplateReport | null): string | null {
  if (!report || report.errors.length === 0) {
    return null
  }
  return `模板无法套用到当前学期：${report.errors.join('；')}`
}
