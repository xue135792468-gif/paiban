import { missingParts, type RuleCard } from './compiler'

/**
 * Ticket 08: a card belongs in 「本学期用不上」 only when it is paused <em>and</em>
 * its sentences carry a concrete reason. A card the user switched off on purpose has
 * no broken reference and must stay in the normal list.
 */
export function isUnusableCard(card: RuleCard): boolean {
  return !card.enabled && card.sentences.some((sentence) =>
    (sentence.invalidReferences?.length ?? 0) > 0 || missingParts(sentence).length > 0)
}

/** Splits cards once so the list and the unusable panel never show the same card. */
export function splitUnusableRules(cards: RuleCard[]): {
  unusable: RuleCard[]
  usable: RuleCard[]
} {
  const unusable = cards.filter(isUnusableCard)
  return {
    unusable,
    usable: cards.filter((card) => !unusable.includes(card)),
  }
}
