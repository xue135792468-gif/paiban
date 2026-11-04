import { describeCard, type PeriodOption, type RuleCard, type RuleNames } from './compiler'
import { cardsSignature } from './signature'

/**
 * Whether one card differs from the version that was loaded. The delete dialog uses
 * it to refuse only this card's own unsaved edits, instead of blocking every delete
 * whenever any other card on the page is dirty.
 */
export function cardChangedSinceLoad(card: RuleCard, loadedCards: RuleCard[]): boolean {
  const saved = loadedCards.find((item) => item.key === card.key)
  if (!saved) {
    return true
  }
  return cardsSignature([card]) !== cardsSignature([saved])
}

/** Readable sentence of one card, so a dialog names the card it acts on. */
export function cardLabelOf(
  card: RuleCard,
  names: RuleNames,
  periods: PeriodOption[],
): string {
  return describeCard(card, names, periods)
}
