import { http } from './http'
import type {
  CyclePlanSearch,
  DraftChangeItem,
  DraftChangeResult,
  DraftDetail,
  DraftValidation,
  LockResult,
} from '@/components/draft/types'

/**
 * One place for the draft editor's HTTP calls. The 401 redirect and the
 * `serverMessage` wording stay in `api/http` / `api/errors`; this module only
 * owns the paths and transport shapes (contracts.md §2/§4).
 */
export async function getDraft(draftId: number): Promise<DraftDetail> {
  const { data } = await http.get<DraftDetail>(`/timetable-drafts/${draftId}`)
  return data
}

export async function getValidation(draftId: number): Promise<DraftValidation> {
  const { data } = await http.get<DraftValidation>(`/timetable-drafts/${draftId}/validation`)
  return data
}

export async function listCourses(): Promise<Array<{ id: number; color?: string }>> {
  const { data } = await http.get('/courses')
  return data
}

export async function applyChanges(
  draftId: number,
  changes: DraftChangeItem[],
  expectedVersion: number,
  rulesFingerprint: string,
): Promise<DraftChangeResult> {
  const { data } = await http.post<DraftChangeResult>(`/timetable-drafts/${draftId}/changes`, {
    changes,
    expectedVersion,
    rulesFingerprint,
    verifySource: true,
  })
  return data
}

export async function searchCyclePlans(
  draftId: number,
  request: {
    sourceLessonId: number
    targetPeriodId: number
    targetLessonId: number
    expectedVersion: number
    rulesFingerprint: string
  },
): Promise<CyclePlanSearch> {
  const { data } = await http.post<CyclePlanSearch>(
    `/timetable-drafts/${draftId}/cycle-plans/search`,
    {
      sourceLessonId: request.sourceLessonId,
      targetPeriodId: request.targetPeriodId,
      targetLessonId: request.targetLessonId,
      expectedVersion: request.expectedVersion,
      rulesFingerprint: request.rulesFingerprint,
    },
  )
  return data
}

/**
 * Locks or unlocks concrete lessons. The client expands "this day"/"the whole
 * class" into lesson ids; the server validates the batch atomically (contracts §1).
 */
export async function setLocks(
  draftId: number,
  body: { lessonIds: number[]; locked: boolean; expectedVersion: number },
): Promise<LockResult> {
  const { data } = await http.put<LockResult>(`/timetable-drafts/${draftId}/lessons/locks`, body)
  return data
}

export async function applyCyclePlan(
  draftId: number,
  token: string,
  expectedVersion: number,
): Promise<DraftChangeResult> {
  const { data } = await http.post<DraftChangeResult>(
    `/timetable-drafts/${draftId}/cycle-plans/apply`,
    { token, expectedVersion },
  )
  return data
}
