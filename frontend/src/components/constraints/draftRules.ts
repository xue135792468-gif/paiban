import { draftEntryCount, isEntryType, isThresholdType, plannedEntries } from './entryModel'
import { thresholdEntries, thresholdEntryCount } from './thresholdModel'
import type {
  ConstraintConfigRequest,
  ConstraintCounts,
  NavGroup,
  TypeDraft,
} from './types'

/** `已启用 N/24 类` needs a stable order, not the catalogue order. */
const CATEGORY_ORDER = ['CONFLICT', 'RESOURCE', 'PEDAGOGY', 'PREFERENCE']

/**
 * How many entries one type contributes to `具体条目 M 条`:
 * - switch types never have entries;
 * - entry-backed types count the cards they will store;
 * - a threshold counts one per scope (the global default and each complete exception).
 */
export function entryCountOf(row: TypeDraft): number {
  if (isEntryType(row)) {
    return draftEntryCount(row)
  }
  if (isThresholdType(row)) {
    return thresholdEntryCount(row)
  }
  return 0
}

/**
 * The two counts the page shows side by side: types and entries are never mixed.
 * Hard types are counted only when enabled — a disabled hard row is not in play.
 * The entry count comes from `draftEntryCount`, which reads the same planned entries
 * the save sends (switch types contribute none).
 */
export function countsOf(rows: TypeDraft[]): ConstraintCounts {
  return rows.reduce<ConstraintCounts>(
    (counts, row) => {
      counts.totalTypes += 1
      if (row.enabled) {
        counts.enabledTypes += 1
        if (row.hard) {
          counts.hardTypes += 1
        }
      }
      counts.entries += entryCountOf(row)
      return counts
    },
    { enabledTypes: 0, totalTypes: 0, hardTypes: 0, entries: 0 },
  )
}

export function groupRows(rows: TypeDraft[]): NavGroup[] {
  const groups = new Map<string, TypeDraft[]>()
  rows.forEach((row) => {
    const bucket = groups.get(row.category) ?? []
    bucket.push(row)
    groups.set(row.category, bucket)
  })
  return Array.from(groups.entries())
    .sort(([left], [right]) => {
      const leftIndex = CATEGORY_ORDER.indexOf(left)
      const rightIndex = CATEGORY_ORDER.indexOf(right)
      return (leftIndex < 0 ? CATEGORY_ORDER.length : leftIndex) -
        (rightIndex < 0 ? CATEGORY_ORDER.length : rightIndex)
    })
    .map(([category, groupRowsInCategory]) => ({
      category,
      categoryLabel: groupRowsInCategory[0].categoryLabel,
      counts: countsOf(groupRowsInCategory),
      rows: groupRowsInCategory,
    }))
}

/**
 * `PUT /api/constraints` body. Entry-backed types and thresholds send `entries` (never
 * `params`): one card per entry, one entry per threshold scope. An empty entry list is
 * meaningful — "this type is not configured".
 *
 * A switched-off type that carries no entry at all is left out of the request: the
 * server rejects any config whose required parameters are empty, and leaving the row
 * out is exactly what "no configuration" means. Rows with entries, and rows the school
 * mode hides, are always sent so a save never erases a condition that exists.
 */
export function buildSavePayload(
  rows: TypeDraft[],
  options: { isJunior: boolean },
): ConstraintConfigRequest[] {
  return rows.flatMap<ConstraintConfigRequest>((row) => {
    // Junior mode only exposes "must / prefer": must = weight 100, prefer = 50.
    const weight = options.isJunior ? (row.hard ? 100 : 50) : row.weight
    if (isEntryType(row) || isThresholdType(row)) {
      const entries = isThresholdType(row)
        ? thresholdEntries(row)
        : plannedEntries(row)
      if (!row.enabled && entries.length === 0) {
        return []
      }
      return [
        {
          type: row.code,
          entries: entries.map((entry) => ({ id: entry.id, params: entry.params })),
          weight,
          hard: row.hard,
          enabled: row.enabled,
        },
      ]
    }
    // The six switch types carry no parameters at all; their row is the whole state.
    if (!row.enabled) {
      return []
    }
    return [
      {
        type: row.code,
        params: {},
        weight,
        hard: row.hard,
        enabled: row.enabled,
      },
    ]
  })
}
