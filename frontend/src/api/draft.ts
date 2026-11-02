import { http } from './http'
import type {
  CyclePlanSearch,
  DraftChangeItem,
  DraftChangeResult,
  DraftDetail,
  DraftValidation,
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
