import { allTargetLabel } from './entryModel'
import { dayLabel, hasValue, roomTypeLabel } from './paramValues'
import type { EntryDraft, ParamDef } from './types'

export interface SummaryContext {
  teacherNames: Map<number, string>
  courseNames: Map<number, string>
}

/** `周一第1节` — the compact form used inside a preference slot set. */
export function formatSlot(dayOfWeek: unknown, periodNo: unknown): string {
  const day = hasValue(dayOfWeek) ? dayLabel(dayOfWeek) : '未选星期'
  const period = hasValue(periodNo) ? `第${periodNo}节` : '未选节次'
  return `${day}${period}`
}

function paramText(param: ParamDef, value: unknown, ctx: SummaryContext): string {
  switch (param.name) {
    case 'teacherId':
      return ctx.teacherNames.get(Number(value)) ?? `教师#${value}`
    case 'courseId':
      return ctx.courseNames.get(Number(value)) ?? `课程#${value}`
    case 'dayOfWeek':
      return dayLabel(value)
    case 'periodNo':
      return `第${value}节`
    case 'minConsecutive':
      return `连排${value}节`
    case 'beforePeriodNo':
      return `上午截止第${value}节`
    case 'roomType':
      return roomTypeLabel(value)
    default:
      break
  }
  if (param.type === 'INT' || param.type === 'PERIOD') {
    return `${param.label}${value}`
  }
  return String(value)
}

function missingText(param: ParamDef): string {
  if (param.type === 'DAY') {
    return '未选星期'
  }
  if (param.type === 'PERIOD') {
    return '未选节次'
  }
  return `未选${param.label}`
}

/**
 * One-line card title built from the catalogue, e.g.
 * `张伟 · 周三 第2节`, `数学 · 实验室`, `数学 · 连排2节`, `全部课程`, `体育类`.
 * A blank optional target is never rendered as an empty string: it says what the
 * entry actually means (every course / every subject / the sport class).
 */
export function formatEntrySummary(
  params: ParamDef[],
  values: Record<string, unknown>,
  ctx: SummaryContext,
): string {
  const parts: string[] = []
  params.forEach((param, index) => {
    const value = values[param.name]
    const text = hasValue(value)
      ? paramText(param, value, ctx)
      : !param.required
        ? allTargetLabel(param)
        : missingText(param)
    // 星期 and 第几节 read as one phrase: `周三 第2节`.
    const previous = params[index - 1]
    if (param.type === 'PERIOD' && previous?.type === 'DAY' && parts.length > 0) {
      parts[parts.length - 1] = `${parts[parts.length - 1]} ${text}`
      return
    }
    parts.push(text)
  })
  return parts.join(' · ')
}

/**
 * A preference-slot card: the teacher plus every slot as alternatives. The server
 * keeps one entry per slot and combines them per teacher with "or", so the summary
 * has to say so instead of looking like a list of requirements.
 */
export function slotCardSummary(entry: EntryDraft, ctx: SummaryContext): string {
  const teacher = hasValue(entry.values.teacherId)
    ? (ctx.teacherNames.get(Number(entry.values.teacherId)) ?? `教师#${entry.values.teacherId}`)
    : '未选教师'
  const slots = entry.slots ?? []
  if (slots.length === 0) {
    return `${teacher} · 还没有时段`
  }
  const texts = slots.map((slot) => formatSlot(slot.dayOfWeek, slot.periodNo))
  if (texts.length === 1) {
    return `${teacher} · ${texts[0]}`
  }
  return `${teacher} · ${texts.join(' 或 ')}（命中任一即满足）`
}
