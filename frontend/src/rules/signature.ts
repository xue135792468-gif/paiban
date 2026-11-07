import type { RuleCard } from './compiler'

/**
 * The dirty check of the rule page: card identity is deliberately left out, so
 * loading a card back from the server (different keys for the same content) does
 * not read as a user edit.
 */
export function cardsSignature(cards: RuleCard[]): string {
  return JSON.stringify(
    cards.map((card) => ({
      cardId: card.cardId,
      enabled: card.enabled,
      sentences: card.sentences.map((sentence) => ({
        groupKey: sentence.groupKey,
        type: sentence.type,
        classId: sentence.classId,
        teacherId: sentence.teacherId,
        courseId: sentence.courseId,
        days: [...sentence.days].sort((left, right) => left - right),
        periodNos: [...sentence.periodNos].sort((left, right) => left - right),
        amount: sentence.amount,
        strength: sentence.strength,
        enabled: sentence.enabled,
        exceptionClassIds: [...sentence.exceptionClassIds].sort((left, right) => left - right),
      })),
    })),
  )
}
