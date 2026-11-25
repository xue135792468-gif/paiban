import { expect, it } from 'vitest'
import { buildTeachingMatrix } from './matrix'

it('indexes assignments by class and course and keeps classes in numeric order', () => {
  const classes = [
    { id: 1, gradeId: 8, name: '八（10）班', studentCount: 41, headTeacherName: '陈老师' },
    { id: 2, gradeId: 8, name: '八（2）班', studentCount: 42, headTeacherName: null },
    { id: 3, gradeId: 9, name: '九（1）班', studentCount: 40, headTeacherName: null },
  ]
  const courses = [
    { id: 6, name: '语文', weeklyHours: 5, active: true },
    { id: 7, name: '数学', weeklyHours: null, active: true },
  ]
  const assignment = { id: 1, classId: 2, courseId: 6, teacherId: 2, teacherName: '张老师', weeklyLessons: 4, active: true }
  const matrix = buildTeachingMatrix(8, classes, courses, [assignment])
  expect(matrix.rows.map((row) => row.name)).toEqual(['八（2）班', '八（10）班'])
  expect(matrix.rows[0].cells.map((cell) => cell.assignment)).toEqual([assignment, undefined])
  expect(matrix.rows[1].cells.map((cell) => cell.assignment)).toEqual([undefined, undefined])
})
