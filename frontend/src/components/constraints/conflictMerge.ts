import type { ConstraintConfigDto, ConstraintConfigRequest } from './types'

/**
 * Merges a newer server configuration into the user's draft after a version conflict
 * (ticket 14/07).
 *
 * The rule is "keep what I changed, take what I did not":
 * - a type whose current payload equals the payload the page loaded with was not
 *   touched by the user, so the newer server row wins (this is how another writer's
 *   work survives);
 * - a type the user edited keeps the user's version, because they have reviewed it;
 * - a type only the server has is added; a type only the user has is kept.
 *
 * Nothing is guessed about entry identity: the caller saves the merged result as a new
 * full configuration and the server assigns ids.
 */
export function mergeConfigs(
  base: ConstraintConfigRequest[],
  current: ConstraintConfigRequest[],
  latest: ConstraintConfigDto[],
): ConstraintConfigDto[] {
  const baseByType = new Map(base.map((config) => [config.type, config]))
  const currentByType = new Map(current.map((config) => [config.type, config]))
  const latestTypes = new Set(latest.map((config) => config.type))
  const merged: ConstraintConfigDto[] = []

  latest.forEach((latestConfig) => {
    const currentConfig = currentByType.get(latestConfig.type)
    const baseConfig = baseByType.get(latestConfig.type)
    const touched = currentConfig !== undefined
      && JSON.stringify(currentConfig) !== JSON.stringify(baseConfig)
    merged.push(touched ? toDto(currentConfig) : latestConfig)
  })

  current.forEach((currentConfig) => {
    if (!latestTypes.has(currentConfig.type)) {
      merged.push(toDto(currentConfig))
    }
  })
  return merged
}

/** The request shape read back as the response shape the editor hydrates from. */
function toDto(config: ConstraintConfigRequest): ConstraintConfigDto {
  return {
    type: config.type,
    params: config.params ?? {},
    entries: (config.entries ?? []).map((entry) => ({
      id: entry.id ?? null,
      params: entry.params,
      enabled: entry.enabled,
      hard: entry.hard,
      weight: entry.weight,
      groupKey: entry.groupKey,
    })),
    weight: config.weight,
    hard: config.hard,
    enabled: config.enabled,
  }
}
