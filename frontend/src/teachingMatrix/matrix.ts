export interface MatrixClass {
  id: number
  name: string
  gradeId: number
  studentCount: number
  headTeacherName: string | null
}

export interface MatrixCourse {
  id: number
  name: string
  weeklyHours: number | null
  active: boolean
}

export interface MatrixAssignment {
  id: number
  classId: number
  courseId: number
  teacherId: number
  teacherName: string | null
  weeklyLessons: number
  active: boolean
}

export interface MatrixCell {
  course: MatrixCourse
  assignment?: MatrixAssignment
}

const byName = new Intl.Collator('zh-CN', { numeric: true })

export function buildTeachingMatrix(
  gradeId: number | undefined,
  classes: MatrixClass[],
  courses: MatrixCourse[],
  assignments: MatrixAssignment[],
) {
  const rows = classes.filter((item) => item.gradeId === gradeId)
    .sort((first, second) => byName.compare(first.name, second.name) || first.id - second.id)
  const classIds = new Set(rows.map((item) => item.id))
  const visibleAssignments = assignments.filter((item) => classIds.has(item.classId))
  const assignedCourseIds = new Set(visibleAssignments.map((item) => item.courseId))
  const columns = courses.filter((item) => item.active || assignedCourseIds.has(item.id))
  const cells = new Map(visibleAssignments.map((item) => [`${item.classId}:${item.courseId}`, item]))
  return {
    columns,
    rows: rows.map((item) => ({
      ...item,
      cells: columns.map((course): MatrixCell => ({
        course,
        assignment: cells.get(`${item.id}:${course.id}`),
      })),
    })),
  }
}
