/**
 * The server rejects a whole save with one message that can name several types
 * and entry positions, e.g.
 * `教师禁排时段(TEACHER_UNAVAILABLE) 第2 条: 参数「教师」不能为空; 未知约束类型: X`.
 * Not every message carries the code — type-level errors are just
 * `课程不排晚自习: 参数「课程」不能为空` — so the catalogue is passed in to turn a
 * display name back into a code. Splitting the message here keeps the view free of
 * message parsing and lets the page put each error next to the row it belongs to.
 */
export interface SaveErrorReport {
  /** Messages that do not name a type; shown above the page. */
  general: string[]
  /** Type-level messages, keyed by constraint type code. */
  byType: Record<string, string[]>
  /** Entry-level messages, keyed by type code then 1-based position. */
  byEntry: Record<string, Record<number, string[]>>
  /** Types with any error, for the left navigation's red dot. */
  codes: string[]
}

export interface NamedType {
  code: string
  name: string
}

/** `显示名(CODE) 第N 条: 详情` — the form entry-level validation uses. */
const WITH_CODE = /^(.+?)\(([A-Z_]+)\)(?: 第(\d+) 条)?[:：] (.+)$/
/** `显示名: 详情` — type-level messages without the code. */
const NAME_ONLY = /^([^:：]+?)[:：] (.+)$/

export function parseSaveErrors(
  message: string | null | undefined,
  types: readonly NamedType[] = [],
): SaveErrorReport {
  const report: SaveErrorReport = { general: [], byType: {}, byEntry: {}, codes: [] }
  const text = (message ?? '').trim()
  if (!text) {
    return report
  }
  const codeByName = new Map(types.map((type) => [type.name, type.code]))
  text
    .split('; ')
    .map((part) => part.trim())
    .filter((part) => part.length > 0)
    .forEach((part) => {
      const located = locate(part, codeByName)
      if (!located) {
        report.general.push(part)
        return
      }
      if (located.position) {
        const byPosition = (report.byEntry[located.code] ??= {})
        const messages = (byPosition[located.position] ??= [])
        messages.push(located.detail)
      } else {
        const messages = (report.byType[located.code] ??= [])
        messages.push(located.detail)
      }
      if (!report.codes.includes(located.code)) {
        report.codes.push(located.code)
      }
    })
  return report
}

function locate(
  part: string,
  codeByName: Map<string, string>,
): { code: string; position?: number; detail: string } | null {
  const withCode = WITH_CODE.exec(part)
  if (withCode) {
    const [, , code, position, detail] = withCode
    return position === undefined
      ? { code, detail }
      : { code, position: Number(position), detail }
  }
  const nameOnly = NAME_ONLY.exec(part)
  if (nameOnly) {
    const [, name, detail] = nameOnly
    const code = codeByName.get(name)
    if (code) {
      return { code, detail }
    }
  }
  return null
}

export function emptySaveErrors(): SaveErrorReport {
  return { general: [], byType: {}, byEntry: {}, codes: [] }
}
