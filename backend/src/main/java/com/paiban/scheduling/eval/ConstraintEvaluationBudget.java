package com.paiban.scheduling.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The 20-CNY guard for one evaluation round (ticket 14/09).
 *
 * <p>Every issued call reserves its <em>worst-case</em> cost before it is sent: the
 * input upper bound is the prompt's character count (a conservative token bound for
 * Chinese text) and the output bound is the task's 8192-token cap. The guard never
 * lets the reserved total exceed the budget, so failures and timeouts — which may
 * still be billed — cannot push the round over it. Actual usage is reported next to
 * the reservation; it never relaxes the guard.
 */
public final class ConstraintEvaluationBudget {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final BigDecimal OUTPUT_TOKEN_CAP = BigDecimal.valueOf(8192);

    private final BigDecimal budget;
    private final BigDecimal priceInputPer1k;
    private final BigDecimal priceOutputPer1k;
    private BigDecimal reserved = BigDecimal.ZERO;
    private BigDecimal actual = BigDecimal.ZERO;

    public ConstraintEvaluationBudget(
            BigDecimal budget, BigDecimal priceInputPer1k, BigDecimal priceOutputPer1k) {
        this.budget = budget;
        this.priceInputPer1k = priceInputPer1k;
        this.priceOutputPer1k = priceOutputPer1k;
    }

    /** No reliable unit price means the round must not call anything at all. */
    public static boolean reliable(BigDecimal priceInputPer1k, BigDecimal priceOutputPer1k) {
        return priceInputPer1k != null && priceOutputPer1k != null
                && priceInputPer1k.signum() > 0 && priceOutputPer1k.signum() > 0;
    }

    public BigDecimal worstCaseCny(String system, String user) {
        BigDecimal inputTokens = BigDecimal.valueOf(
                (system == null ? 0 : system.length()) + (user == null ? 0 : user.length()));
        BigDecimal inputCost = inputTokens.divide(THOUSAND, 6, RoundingMode.HALF_UP)
                .multiply(priceInputPer1k);
        BigDecimal outputCost = OUTPUT_TOKEN_CAP.divide(THOUSAND, 6, RoundingMode.HALF_UP)
                .multiply(priceOutputPer1k);
        return inputCost.add(outputCost).setScale(4, RoundingMode.HALF_UP);
    }

    public boolean canAfford(BigDecimal cost) {
        return remaining().compareTo(cost) >= 0;
    }

    public void reserve(BigDecimal cost) {
        reserved = reserved.add(cost);
    }

    /** Usage-reported cost when the provider gave tokens; the reservation otherwise. */
    public void recordActual(Integer inputTokens, Integer outputTokens, BigDecimal reservedCost) {
        if (inputTokens == null && outputTokens == null) {
            actual = actual.add(reservedCost);
            return;
        }
        BigDecimal inputCost = BigDecimal.valueOf(inputTokens == null ? 0 : inputTokens)
                .divide(THOUSAND, 6, RoundingMode.HALF_UP).multiply(priceInputPer1k);
        BigDecimal outputCost = BigDecimal.valueOf(outputTokens == null ? 0 : outputTokens)
                .divide(THOUSAND, 6, RoundingMode.HALF_UP).multiply(priceOutputPer1k);
        actual = actual.add(inputCost.add(outputCost).setScale(4, RoundingMode.HALF_UP));
    }

    public BigDecimal remaining() {
        return budget.subtract(reserved).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal reserved() {
        return reserved.setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal actual() {
        return actual.setScale(4, RoundingMode.HALF_UP);
    }
}
