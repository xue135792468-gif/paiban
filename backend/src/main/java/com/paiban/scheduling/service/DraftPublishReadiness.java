package com.paiban.scheduling.service;

import java.util.ArrayList;
import java.util.List;

/**
 * The single answer to "can this draft become the published baseline?".
 *
 * <p>Both the whole-draft check ({@link DraftValidator#validateDraft}) and publishing
 * ({@code TimetablePublishService#publish}) read this one list of blockers, so the page
 * can never report "passed" for a draft the publisher refuses.
 *
 * <p>Codes are part of the REST contract: once returned, a code must keep its meaning.
 * Messages are user facing and may be reworded. {@code count} is the number of affected
 * lessons (0 when the gap is the draft itself). {@code action} is a stable identifier the
 * page turns into a link or button; it carries no text.
 */
public final class DraftPublishReadiness {

    /** The draft is already the baseline and cannot be published again. */
    public static final String DRAFT_PUBLISHED = "DRAFT_PUBLISHED";
    /** The draft holds no lessons at all. */
    public static final String DRAFT_EMPTY = "DRAFT_EMPTY";
    /** A lesson points at a period that no longer belongs to the semester. */
    public static final String INVALID_PERIODS = "INVALID_PERIODS";
    /** A lesson has no period yet. */
    public static final String UNSCHEDULED_LESSONS = "UNSCHEDULED_LESSONS";
    /** A lesson sits in the grid but has no room. */
    public static final String MISSING_ROOMS = "MISSING_ROOMS";
    /** The 24 scheduling rules report at least one hard conflict. */
    public static final String HARD_CONFLICTS = "HARD_CONFLICTS";
    /** The semester already has a published baseline; it is read-only from here on. */
    public static final String SEMESTER_ALREADY_PUBLISHED = "SEMESTER_ALREADY_PUBLISHED";

    public static final String ACTION_NONE = "NONE";
    public static final String ACTION_VIEW_TIMETABLE = "VIEW_TIMETABLE";
    public static final String ACTION_EDIT_LESSONS = "EDIT_LESSONS";
    public static final String ACTION_ASSIGN_ROOMS = "ASSIGN_ROOMS";
    public static final String ACTION_EDIT_PERIODS = "EDIT_PERIODS";
    public static final String ACTION_OPEN_SUBSTITUTIONS = "OPEN_SUBSTITUTIONS";

    /** One reason a draft cannot be published, with the action that closes it. */
    public record Blocker(String code, String message, int count, String action) {}

    /**
     * Everything the judgement depends on. Counts are lesson counts; {@code published}
     * and {@code semesterPublished} are status facts about the draft and its semester.
     */
    public record Facts(
            boolean published,
            int lessonCount,
            int unscheduledCount,
            int missingRoomCount,
            int invalidPeriodCount,
            int hardConflicts,
            boolean semesterPublished) {}

    private DraftPublishReadiness() {}

    /** Evaluates the blockers in display and precedence order: fix the first one first. */
    public static List<Blocker> blockers(Facts facts) {
        List<Blocker> blockers = new ArrayList<>();
        if (facts.published()) {
            blockers.add(new Blocker(
                    DRAFT_PUBLISHED, "该草稿已发布，不能重复发布", 1, ACTION_VIEW_TIMETABLE));
        }
        if (facts.lessonCount() == 0) {
            blockers.add(new Blocker(DRAFT_EMPTY, "草稿没有课节，无法发布", 0, ACTION_NONE));
        }
        if (facts.invalidPeriodCount() > 0) {
            blockers.add(new Blocker(
                    INVALID_PERIODS,
                    "有 " + facts.invalidPeriodCount() + " 节课引用的作息时段不属于当前学期，请先检查学期作息",
                    facts.invalidPeriodCount(),
                    ACTION_EDIT_PERIODS));
        }
        if (facts.unscheduledCount() > 0) {
            blockers.add(new Blocker(
                    UNSCHEDULED_LESSONS,
                    "还有 " + facts.unscheduledCount() + " 节课未排课",
                    facts.unscheduledCount(),
                    ACTION_EDIT_LESSONS));
        }
        if (facts.missingRoomCount() > 0) {
            blockers.add(new Blocker(
                    MISSING_ROOMS,
                    "有 " + facts.missingRoomCount() + " 节课还没有分配教室",
                    facts.missingRoomCount(),
                    ACTION_ASSIGN_ROOMS));
        }
        if (facts.hardConflicts() > 0) {
            blockers.add(new Blocker(
                    HARD_CONFLICTS,
                    "课表存在 " + facts.hardConflicts() + " 处排课冲突",
                    facts.hardConflicts(),
                    ACTION_NONE));
        }
        if (facts.semesterPublished()) {
            blockers.add(new Blocker(
                    SEMESTER_ALREADY_PUBLISHED,
                    "该学期课表已发布，基线只读；后续调整请走调代流程",
                    1,
                    ACTION_OPEN_SUBSTITUTIONS));
        }
        return List.copyOf(blockers);
    }
}
