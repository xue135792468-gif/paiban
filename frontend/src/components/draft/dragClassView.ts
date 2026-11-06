import type { DraftLesson } from './types'

/**
 * 票 17：教师视图拖动换课时的「浅色班级课表」数据。
 *
 * 在「按教师」视角拖起一节课，网格临时换成这节课所属班级的课表（同班其他老师的课
 * 也能看见），并把该教师在别的班有课的节次标灰。这里只做纯数据计算：改变的只是
 * 拖动过程中显示的内容，放下后仍走班级视图同一套 drop()，不动后端接口。
 */

/** 拖起时网格要显示的课：该课所属班级的全部课；没在拖就返回空列表。 */
export function dragClassLessons(
  allLessons: DraftLesson[],
  dragged: DraftLesson | null,
): DraftLesson[] {
  if (!dragged) return []
  return allLessons.filter((lesson) => lesson.classId === dragged.classId)
}

/**
 * 拖起时的他班占用：该教师在别的班、且已排入节次的课，按节次聚合成
 * 「某老师在某班有课」，同一节次只留第一条原因。
 */
export function foreignTeacherPeriods(
  allLessons: DraftLesson[],
  dragged: DraftLesson | null,
): Map<number, string> {
  const busy = new Map<number, string>()
  if (!dragged) return busy
  const teacherName = dragged.teacherName ?? '该教师'
  allLessons.forEach((lesson) => {
    if (lesson.teacherId !== dragged.teacherId) return
    // 源课所在班自己的课不算「他班占用」，那一格是同班换课的目标。
    if (lesson.classId === dragged.classId) return
    if (lesson.periodId == null || busy.has(lesson.periodId)) return
    const className = lesson.className ?? `班级${lesson.classId}`
    busy.set(lesson.periodId, `${teacherName}在${className}有课`)
  })
  return busy
}
