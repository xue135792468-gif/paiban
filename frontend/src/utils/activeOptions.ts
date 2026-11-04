/**
 * Keeps only objects that are not explicitly disabled. The management lists still show
 * disabled rows so they can be re-enabled, but pickers (semester / grade / class /
 * course / teacher selects) must not offer them. A missing/null flag means "active" so
 * older API payloads keep working.
 */
export function activeOnly<T extends { active?: boolean | null }>(
  items: readonly T[] | null | undefined,
): T[] {
  return (items ?? []).filter((item) => item.active !== false)
}
