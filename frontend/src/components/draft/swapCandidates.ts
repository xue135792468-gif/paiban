import type { SwapCandidate, SwapCandidatesResponse } from './types'

/** Panel filters; `null` means "no filter" so the panel can reset to all candidates. */
export interface CandidateFilters {
  subject: string | null
  gradeId: number | null
  classId: number | null
  teacherId: number | null
}

export const EMPTY_CANDIDATE_FILTERS: CandidateFilters = {
  subject: null,
  gradeId: null,
  classId: null,
  teacherId: null,
}

export interface CandidateGroup {
  intraClass: SwapCandidate[]
  crossClass: SwapCandidate[]
}

export interface CandidateOption {
  id: number
  name: string
}

export interface CandidateFilterOptions {
  subjects: string[]
  grades: CandidateOption[]
  classes: CandidateOption[]
  teachers: CandidateOption[]
}

const collator = new Intl.Collator('zh-CN')

function matches(candidate: SwapCandidate, filters: CandidateFilters) {
  return (
    (filters.subject == null || candidate.subject === filters.subject) &&
    (filters.gradeId == null || candidate.gradeId === filters.gradeId) &&
    (filters.classId == null || candidate.classId === filters.classId) &&
    (filters.teacherId == null || candidate.teacherId === filters.teacherId)
  )
}

export function filterCandidates(candidates: SwapCandidate[], filters: CandidateFilters) {
  return candidates.filter((candidate) => matches(candidate, filters))
}

/**
 * The server returns both groups already split; the panel only narrows them.
 * Unavailable candidates stay in the list when the caller asked for them, so the
 * panel can explain why a swap is blocked.
 */
export function groupCandidates(
  response: SwapCandidatesResponse,
  filters: CandidateFilters,
): CandidateGroup {
  return {
    intraClass: filterCandidates(response.intraClass, filters),
    crossClass: filterCandidates(response.crossClass, filters),
  }
}

function optionsOf(
  candidates: SwapCandidate[],
  idOf: (candidate: SwapCandidate) => number | undefined,
  nameOf: (candidate: SwapCandidate) => string | undefined,
  sort: (left: CandidateOption, right: CandidateOption) => number,
): CandidateOption[] {
  const map = new Map<number, string>()
  candidates.forEach((candidate) => {
    const id = idOf(candidate)
    const name = nameOf(candidate)
    if (id != null && name) map.set(id, name)
  })
  return [...map.entries()].map(([id, name]) => ({ id, name })).sort(sort)
}

/** Filter dropdown values, derived from the candidates the server returned. */
export function candidateFilterOptions(candidates: SwapCandidate[]): CandidateFilterOptions {
  const subjects = new Set<string>()
  candidates.forEach((candidate) => {
    if (candidate.subject) subjects.add(candidate.subject)
  })
  return {
    subjects: [...subjects].sort((left, right) => collator.compare(left, right)),
    grades: optionsOf(
      candidates,
      (candidate) => candidate.gradeId,
      (candidate) => candidate.gradeName,
      (left, right) => left.id - right.id,
    ),
    classes: optionsOf(
      candidates,
      (candidate) => candidate.classId,
      (candidate) => candidate.className,
      (left, right) => collator.compare(left.name, right.name),
    ),
    teachers: optionsOf(
      candidates,
      (candidate) => candidate.teacherId,
      (candidate) => candidate.teacherName,
      (left, right) => collator.compare(left.name, right.name),
    ),
  }
}
