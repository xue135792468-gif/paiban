import { describe, expect, it } from 'vitest'
import { dragClassLessons, foreignTeacherPeriods } from './dragClassView'
import type { DraftLesson } from './types'

function lesson(id: number, overrides: Partial<DraftLesson> = {}): DraftLesson {
  return {
    id,
    assignmentId: id,
    courseId: id,
    courseName: `课程${id}`,
    teacherId: id,
    teacherName: `教师${id}`,
    classId: 1,
    className: '高一1班',
    studentCount: 40,
    periodId: 100 + id,
    dayOfWeek: 1,
    periodNo: id,
    locked: false,
    ...overrides,
  }
}

/** 源课：周一第 2 节，高一1班，王老师。 */
const source = lesson(9, { teacherId: 7, teacherName: '王老师', classId: 1, className: '高一1班' })

describe('dragClassLessons', () => {
  it('没有拖起的课时返回空列表', () => {
    expect(dragClassLessons([source], null)).toEqual([])
  })

  it('只返回该课所属班级的全部课，含同班其他老师的课', () => {
    const others = lesson(10, { teacherId: 8, teacherName: '李老师', classId: 1 })
    const foreignClass = lesson(11, { teacherId: 7, teacherName: '王老师', classId: 2 })

    const result = dragClassLessons([source, others, foreignClass], source)

    expect(result.map((item) => item.id)).toEqual([9, 10])
  })
})

describe('foreignTeacherPeriods', () => {
  it('没有拖起的课时没有他班占用', () => {
    expect(foreignTeacherPeriods([source], null).size).toBe(0)
  })

  it('该教师在别的班有课的节次按 periodId 聚合，原因含教师名与班名', () => {
    const foreign = lesson(11, {
      teacherId: 7,
      teacherName: '王老师',
      classId: 2,
      className: '高一2班',
      periodId: 101,
    })
    const otherTeacher = lesson(12, { teacherId: 8, teacherName: '李老师', classId: 3, periodId: 102 })

    const busy = foreignTeacherPeriods([source, foreign, otherTeacher], source)

    expect([...busy.keys()]).toEqual([101])
    expect(busy.get(101)).toBe('王老师在高一2班有课')
  })

  it('同一节次只留一条原因', () => {
    const first = lesson(11, { teacherId: 7, classId: 2, className: '高一2班', periodId: 101 })
    const second = lesson(12, { teacherId: 7, classId: 3, className: '高一3班', periodId: 101 })

    const busy = foreignTeacherPeriods([source, first, second], source)

    expect(busy.size).toBe(1)
    expect(busy.get(101)).toBe('王老师在高一2班有课')
  })

  it('源课本班的课不算他班占用', () => {
    const sameClass = lesson(10, { teacherId: 7, teacherName: '王老师', classId: 1, periodId: 101 })

    expect(foreignTeacherPeriods([source, sameClass], source).size).toBe(0)
  })

  it('未排入节次的他班课不进 Map', () => {
    const unscheduled = lesson(11, {
      teacherId: 7,
      teacherName: '王老师',
      classId: 2,
      className: '高一2班',
      periodId: null,
      dayOfWeek: null,
      periodNo: null,
    })

    expect(foreignTeacherPeriods([source, unscheduled], source).size).toBe(0)
  })
})
