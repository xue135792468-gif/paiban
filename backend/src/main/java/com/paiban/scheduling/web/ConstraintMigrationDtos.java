package com.paiban.scheduling.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read models of the 26-to-19 legacy rule migration (ticket 11). */
public final class ConstraintMigrationDtos {

    private ConstraintMigrationDtos() {}

    /**
     * One old row's decision. {@code oldEntryId} is 0 for a type-level (config) rule and
     * the stored entry id for a parameter row. {@code newType} is null when the old type
     * is disabled, because no new type replaces it.
     */
    public record MigrationRowDto(String oldType,
                                  String oldTypeName,
                                  Long oldEntryId,
                                  String action,
                                  String newType,
                                  String groupKey,
                                  Map<String, Object> params,
                                  String note,
                                  boolean requiresReview) {}

    /**
     * The plan of a migration without writing anything. {@code retained},
     * {@code converted} and {@code disabled} count type-level rules; {@code review}
     * counts the concrete rows that need a human decision.
     */
    public record MigrationPreviewDto(Long semesterId,
                                      int configCount,
                                      int entryCount,
                                      String settingsFingerprint,
                                      int retained,
                                      int converted,
                                      int disabled,
                                      int review,
                                      List<MigrationRowDto> rows) {}

    /**
     * The draft-check comparison of one side of a migration. {@code skipped} is true
     * when the semester has no DRAFT, in which case every number is 0 and the
     * fingerprint/config/entry counts are still recorded.
     */
    public record MigrationEvaluationDto(boolean skipped,
                                         int lessons,
                                         int hardConflicts,
                                         int softPenalty,
                                         String settingsFingerprint,
                                         int configCount,
                                         int enabledConfigCount,
                                         int entryCount,
                                         int enabledEntryCount) {}

    /** The latest run plus its map rows and the before/after comparison. */
    public record MigrationReportDto(String runId,
                                     Long semesterId,
                                     String status,
                                     Instant startedAt,
                                     Instant finishedAt,
                                     String runBy,
                                     int retained,
                                     int converted,
                                     int disabled,
                                     int review,
                                     MigrationEvaluationDto before,
                                     MigrationEvaluationDto after,
                                     List<MigrationRowDto> rows) {}
}
