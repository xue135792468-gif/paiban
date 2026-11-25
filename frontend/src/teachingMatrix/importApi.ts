import { http } from '@/api/http'

export interface MatrixContext { semesterId: number; gradeId: number }
export interface MatrixOptions { weeklyHours: Record<string, number>; saveAsDefaults: boolean }
type Action = 'CREATE' | 'UPDATE_TEACHER' | 'UPDATE_HOURS' | 'UPDATE' | 'UNCHANGED'
interface Counts { created: number; updated: number; unchanged: number }

export interface MatrixReport {
  summary: { classes: Counts; teachers: Counts; courses: Counts; assignments: Counts }
  teachers: { name: string; action: Action }[]
  courses: {
    name: string
    action: Action
    defaultHours: number | null
    effectiveHours: number | null
    missingHours: boolean
  }[]
  rows: {
    row: number
    className: string
    studentCount: number
    headTeacher: string | null
    action: Action
    assignments: { course: string; teacher: string; weeklyLessons: number | null; action: Action }[]
  }[]
  errors: { row: number; field: string; message: string }[]
  warnings: { row: number; className: string; requested: number; available: number }[]
}

export const actionLabel: Record<Action, string> = {
  CREATE: '新建',
  UPDATE: '更新',
  UPDATE_TEACHER: '改老师',
  UPDATE_HOURS: '改节数',
  UNCHANGED: '不变',
}

export async function requestMatrixImport(
  phase: 'preview' | 'commit', context: MatrixContext, file: File, options: MatrixOptions,
) {
  const body = new FormData()
  body.set('file', file)
  body.set('options', new Blob([JSON.stringify(options)], { type: 'application/json' }))
  const response = await http.post<MatrixReport>(`/assignments/matrix/import/${phase}`, body, {
    params: context,
    validateStatus: (status) => status === 200 || status === 400,
  })
  return response
}

export async function downloadMatrix(mode: 'template' | 'export', context: MatrixContext) {
  const { data } = await http.get<Blob>(`/assignments/matrix/${mode}`, { params: context, responseType: 'blob' })
  const url = URL.createObjectURL(data)
  const link = document.createElement('a')
  link.href = url
  link.download = mode === 'template' ? '任课表导入模板.xlsx' : '任课表.xlsx'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
