import { blockedPeriodsForClass } from './availability'
import type { BlockedClassSlot, DraftLesson, DraftPeriod, MoveTargetState } from './types'

export type TargetPeriodStatus =
  | 'current'
  | 'available'
  | 'swappable'
  | 'teacher-busy'
  | 'blocked'
  | 'locked'
  | 'unavailable'

export interface TargetPeriodState {
  status: TargetPeriodStatus
  disabled: boolean
  label: string
  tooltip: string
}

export interface TargetAvailabilityInput {
  periods: DraftPeriod[]
  lessons: DraftLesson[]
  selectedLesson: DraftLesson | null
  blockedSlots?: BlockedClassSlot[]
  moveTargets?: Record<number, MoveTargetState> | null
}

/**
 * Computes per-period availability and human-readable reasons for DraftTargetDialog.
 * Only conflicts involving the selected lesson's class, teacher, or blocked/locked slots
 * disable a period; other classes occupying the same period are normal.
 */
export function computeTargetPeriodStates(
  input: TargetAvailabilityInput,
): Record<number, TargetPeriodState> {
  const { periods, lessons, selectedLesson, blockedSlots = [], moveTargets = null } = input
  if (!selectedLesson) return {}

  const classBlocked = blockedPeriodsForClass(blockedSlots, selectedLesson.classId)
  const sameClassByPeriod = new Map<number, DraftLesson[]>()
  const teacherBusyByPeriod = new Map<number, DraftLesson>()

  for (const lesson of lessons) {
    if (lesson.id === selectedLesson.id || lesson.periodId == null) continue
    if (lesson.classId === selectedLesson.classId) {
      const list = sameClassByPeriod.get(lesson.periodId) ?? []
      list.push(lesson)
      sameClassByPeriod.set(lesson.periodId, list)
    } else if (lesson.teacherId === selectedLesson.teacherId && !teacherBusyByPeriod.has(lesson.periodId)) {
      teacherBusyByPeriod.set(lesson.periodId, lesson)
    }
  }

  const states: Record<number, TargetPeriodState> = {}
  for (const period of periods) {
    if (period.id === selectedLesson.periodId) {
      states[period.id] = {
        status: 'current',
        disabled: true,
        label: '当前位置',
        tooltip: '当前所在位置',
      }
      continue
    }

    const sameClassLessons = sameClassByPeriod.get(period.id) ?? []
    const lockedLesson = sameClassLessons.find((lesson) => lesson.locked)
    if (lockedLesson) {
      states[period.id] = {
        status: 'locked',
        disabled: true,
        label: '已锁定',
        tooltip: `该时段本班「${lockedLesson.courseName ?? '课节'}」已锁定，需先解锁`,
      }
      continue
    }

    const blockedReason = classBlocked[period.id]
    if (blockedReason) {
      states[period.id] = {
        status: 'blocked',
        disabled: true,
        label: '禁排时段',
        tooltip: blockedReason,
      }
      continue
    }

    const targetState = moveTargets?.[period.id]
    const nonClassConflicts = (targetState?.conflicts ?? []).filter(
      (conflict) => conflict.type !== 'CLASS_CONFLICT',
    )
    const unavailableConflict = nonClassConflicts.find(
      (conflict) => conflict.type === 'CLASS_UNAVAILABLE' || conflict.type === 'TEACHER_UNAVAILABLE',
    )
    if (unavailableConflict) {
      states[period.id] = {
        status: 'blocked',
        disabled: true,
        label: '禁排时段',
        tooltip: unavailableConflict.message,
      }
      continue
    }

    const teacherBusyLesson = teacherBusyByPeriod.get(period.id)
    const teacherConflict = nonClassConflicts.find((conflict) => conflict.type === 'TEACHER_CONFLICT')
    if (teacherBusyLesson || teacherConflict) {
      const fallback = teacherBusyLesson
        ? `${selectedLesson.teacherName ?? '该教师'} 此时在 ${teacherBusyLesson.className ?? '其他班'} 有 ${teacherBusyLesson.courseName ?? '课'}`
        : `${selectedLesson.teacherName ?? '该教师'} 该时段有课`
      states[period.id] = {
        status: 'teacher-busy',
        disabled: true,
        label: '教师有课',
        tooltip: teacherConflict?.message || fallback,
      }
      continue
    }

    if (targetState && !targetState.available && nonClassConflicts.length > 0) {
      states[period.id] = {
        status: 'unavailable',
        disabled: true,
        label: '不可用',
        tooltip: nonClassConflicts.map((conflict) => conflict.message).join('；'),
      }
      continue
    }

    if (sameClassLessons.length > 1) {
      states[period.id] = {
        status: 'unavailable',
        disabled: true,
        label: '本班已占用',
        tooltip: '该时段本班存在多节冲突课，请先调整冲突后再换位',
      }
      continue
    }

    if (sameClassLessons.length === 1) {
      const target = sameClassLessons[0]
      const detail = [target.courseName, target.teacherName].filter(Boolean).join(' · ')
      states[period.id] = {
        status: 'swappable',
        disabled: false,
        label: '本班已占用',
        tooltip: detail ? `本班已有「${detail}」，点击可尝试对调` : '本班该时段已有课，点击可尝试对调',
      }
      continue
    }

    if (targetState && !targetState.available) {
      states[period.id] = {
        status: 'unavailable',
        disabled: true,
        label: '不可用',
        tooltip: '该时段不可放置',
      }
      continue
    }

    states[period.id] = {
      status: 'available',
      disabled: false,
      label: period.label || '可放置',
      tooltip: `可移动到第${period.periodNo}节`,
    }
  }

  return states
}
