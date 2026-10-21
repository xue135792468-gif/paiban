import { describe, expect, it } from 'vitest'
import { candidateFilterOptions, groupCandidates, type CandidateFilters } from './swapCandidates'
import type { SwapCandidate, SwapCandidatesResponse } from './types'

function candidate(overrides: Partial<SwapCandidate>): SwapCandidate {
  return {
    lessonId: 1,
    courseId: 10,
    courseName: '语文',
    subject: '语文',
    teacherId: 100,
    teacherName: '张老师',
    classId: 1000,
    className: '高一1班',
    gradeId: 1,
    gradeName: '高一',
    periodId: 500,
    dayOfWeek: 1,
    periodNo: 1,
    periodLabel: '周一第1节',
    roomId: 20,
    roomName: '101',
    available: true,
    conflicts: [],
    warnings: [],
    ...overrides,
  }
}

const NO_FILTERS: CandidateFilters = { subject: null, gradeId: null, classId: null, teacherId: null }

function response(): SwapCandidatesResponse {
  return {
    lessonId: 1,
    intraClass: [
      candidate({ lessonId: 11, courseId: 10, courseName: '语文', subject: '语文', periodId: 501, periodNo: 2 }),
      candidate({ lessonId: 12, courseId: 11, courseName: '数学', subject: '数学', periodId: 502, periodNo: 3 }),
      candidate({
        lessonId: 13,
        courseId: 12,
        courseName: '英语',
        subject: '英语',
        periodId: 503,
        periodNo: 4,
        available: false,
        conflicts: [{ type: 'TEACHER_CONFLICT', message: '教师冲突', lessonId: 13 }],
      }),
    ],
    crossClass: [
      candidate({
        lessonId: 21,
        teacherId: 101,
        teacherName: '李老师',
        classId: 2000,
        className: '高一2班',
        gradeId: 2,
        gradeName: '高二',
        periodId: 601,
        dayOfWeek: 2,
      }),
      candidate({
        lessonId: 22,
        teacherId: 102,
        teacherName: '王老师',
        classId: 2001,
        className: '高一3班',
        gradeId: 2,
        gradeName: '高二',
        subject: '数学',
        courseId: 11,
        courseName: '数学',
        periodId: 602,
        dayOfWeek: 3,
      }),
    ],
  }
}

describe('groupCandidates', () => {
  it('keeps the two swap groups apart and preserves server order', () => {
    const groups = groupCandidates(response(), NO_FILTERS)

    expect(groups.intraClass.map((item) => item.lessonId)).toEqual([11, 12, 13])
    expect(groups.crossClass.map((item) => item.lessonId)).toEqual([21, 22])
  })

  it('filters both groups by subject', () => {
    const groups = groupCandidates(response(), { ...NO_FILTERS, subject: '数学' })

    expect(groups.intraClass.map((item) => item.lessonId)).toEqual([12])
    expect(groups.crossClass.map((item) => item.lessonId)).toEqual([22])
  })

  it('filters by grade, class and teacher together', () => {
    const groups = groupCandidates(response(), {
      ...NO_FILTERS,
      gradeId: 2,
      classId: 2000,
      teacherId: 101,
    })

    expect(groups.intraClass).toEqual([])
    expect(groups.crossClass.map((item) => item.lessonId)).toEqual([21])
  })

  it('keeps unavailable candidates when no availability filter is asked for', () => {
    const groups = groupCandidates(response(), NO_FILTERS)

    expect(groups.intraClass.find((item) => item.lessonId === 13)?.available).toBe(false)
  })
})

describe('candidateFilterOptions', () => {
  it('lists each subject, grade, class and teacher once, sorted by name', () => {
    const all = [...response().intraClass, ...response().crossClass]

    const options = candidateFilterOptions(all)

    expect(options.subjects).toEqual(['数学', '英语', '语文'])
    expect(options.grades).toEqual([
      { id: 1, name: '高一' },
      { id: 2, name: '高二' },
    ])
    expect(options.classes.map((item) => item.id)).toEqual([1000, 2000, 2001])
    expect(options.teachers).toEqual([
      { id: 101, name: '李老师' },
      { id: 102, name: '王老师' },
      { id: 100, name: '张老师' },
    ])
  })

  it('skips candidates with missing labels instead of rendering blank options', () => {
    const options = candidateFilterOptions([
      candidate({ lessonId: 31, subject: undefined, gradeId: undefined, gradeName: undefined, teacherName: undefined }),
    ])

    expect(options.subjects).toEqual([])
    expect(options.grades).toEqual([])
    expect(options.teachers).toEqual([])
    expect(options.classes).toEqual([{ id: 1000, name: '高一1班' }])
  })
})
