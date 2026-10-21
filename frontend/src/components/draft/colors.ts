import type { DraftCourse } from './types'

const PALETTE = [
  '#AEC6CF',
  '#FFD1DC',
  '#B5EAD7',
  '#FFDAC1',
  '#C7CEEA',
  '#E2F0CB',
  '#F1C0E8',
  '#FDFD96',
  '#B0E0E6',
  '#FFB7B2',
]

/** Course colour from the catalogue, falling back to a stable palette slot. */
export function courseColor(courses: DraftCourse[], courseId: number) {
  const course = courses.find((item) => item.id === courseId)
  if (course?.color) return course.color
  return PALETTE[courseId % PALETTE.length]
}
