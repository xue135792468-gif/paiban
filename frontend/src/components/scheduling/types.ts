export interface Semester {
  id: number
  name: string
  startDate?: string
  endDate?: string
  current: boolean
}

export interface GradeOption {
  id: number
  name: string
  sortOrder?: number
  active?: boolean
}

export interface JobDiagnostic {
  teacherId: number
  teacherName?: string | null
  hardCount: number
  messages: string[]
  gradeIds: number[]
  classNames: string[]
}

export interface JobItem {
  id: number
  semesterId: number
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | string
  score?: string | null
  progress: number
  elapsedMs: number
  estimatedRemainingMs?: number
  errorMessage?: string | null
  draftId?: number | null
  draftName?: string | null
  warmStartSource?: 'BASELINE' | 'DRAFT' | 'NONE' | 'SOURCE_DRAFT' | string | null
  solveSeconds: number
  createdAt: string
  startedAt?: string | null
  finishedAt?: string | null
  resultPublishable?: boolean | null
  resultBlockers?: string[]
  gradeIds?: number[]
  diagnostics?: JobDiagnostic[]
}

export interface DraftItem {
  id: number
  semesterId: number
  name: string
  status: 'DRAFT' | 'PUBLISHED' | string
  scoreHard: number
  scoreSoft: number
  createdBy?: string | null
  createdAt: string
  lessonCount: number
  lockedCount?: number
}
