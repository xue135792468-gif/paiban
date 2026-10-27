package com.paiban.scheduling.eval;

import com.paiban.scheduling.eval.ConstraintEvaluationRunner.ModelRef;
import com.paiban.scheduling.eval.ConstraintEvaluationRunner.Report;
import com.paiban.scheduling.eval.ConstraintEvaluationRunner.Request;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Manual entry point for the real-model evaluation (ticket 14/09).
 *
 * <p>Only {@code paiban.eval.enabled} is namespaced; the remaining options are read
 * by their bare names, matching {@link ApplicationArguments#getOptionValues(String)}.
 *
 * <pre>
 * java -jar paiban-backend.jar --paiban.eval.enabled=true \
 *   --output=reports/constraint-eval \
 *   --model=1:2 --price-in=0.001 --price-out=0.002 \
 *   --budget=20
 * </pre>
 *
 * <p>Add {@code --stub=true} for the deterministic pipeline check, and
 * {@code --bind=true} to keep the winning model bound to the task.
 *
 * <p>It is deliberately not an HTTP endpoint: a real round costs money, so it only
 * runs when a person starts the process with the flag, and CI never sets it.
 */
@Component
@ConditionalOnProperty(name = "paiban.eval.enabled", havingValue = "true")
public class ConstraintEvaluationCli implements ApplicationRunner {

    private final ConstraintEvaluationRunner runner;

    public ConstraintEvaluationCli(ConstraintEvaluationRunner runner) {
        this.runner = runner;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path output = Path.of(option(args, "output", "constraint-eval-report"));
        BigDecimal budget = decimal(option(args, "budget", "20"));
        BigDecimal priceIn = decimal(option(args, "price-in", null));
        BigDecimal priceOut = decimal(option(args, "price-out", null));
        boolean stub = Boolean.parseBoolean(option(args, "stub", "false"));
        boolean bind = Boolean.parseBoolean(option(args, "bind", "false"));
        List<ModelRef> models = new ArrayList<>();
        for (String value : list(args, "model")) {
            String[] parts = value.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "--model 必须是 providerId:modelId，实际为 " + value);
            }
            long providerId = Long.parseLong(parts[0]);
            long modelId = Long.parseLong(parts[1]);
            models.add(new ModelRef(providerId, modelId,
                    "provider " + providerId + " / model " + modelId));
        }
        Report report = runner.run(new Request(
                output, budget, priceIn, priceOut,
                ConstraintEvaluationRunner.DEFAULT_RUNS, models, stub, bind));
        System.out.println("约束模型评测结论: " + report.verdict());
        if (report.blockedReason() != null) {
            System.out.println("阻塞原因: " + report.blockedReason());
        }
        System.out.println("报告已写入: " + output.toAbsolutePath());
        System.exit(0);
    }

    private String option(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.get(values.size() - 1);
    }

    private List<String> list(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return values == null ? List.of() : values;
    }

    private BigDecimal decimal(String value) {
        return value == null || !StringUtils.hasText(value) ? null : new BigDecimal(value.trim());
    }
}
