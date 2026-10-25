package com.paiban.scheduling.web;

import com.paiban.scheduling.service.DraftPublishReadiness;
import java.util.List;

/**
 * The REST contract of the explicit "fill fixed rooms" action (ticket 07). One shape
 * serves the preview and the apply step, so the page renders the same rows before and
 * after the user confirms.
 */
public final class RoomFillDtos {

    /**
     * One admin class and the ordinary room it gets. {@code roomId} is null in a preview
     * of a room that still has to be created; {@code action} is {@code CREATE} or
     * {@code REUSE}. {@code studentCount} is the class size the capacity is derived from
     * and {@code requiredCapacity} the seats the room ends up with.
     */
    public record ClassRoomPlanDto(
            Long classId,
            String className,
            int studentCount,
            int requiredCapacity,
            int lessonCount,
            String roomName,
            Long roomId,
            int roomCapacity,
            String action) {}

    /** One class this action refuses to handle, with a stable code and a readable reason. */
    public record ManualItemDto(Long classId, String className, String code, String message) {}

    /**
     * {@code applied} is true only for a successful apply. {@code applicable} tells the
     * page whether confirming can work at all; {@code blockers} is what still stands
     * between the draft and publishing after this action, never a promise that nothing does.
     *
     * <p>{@code message} is the one-line reason a refusal is shown to the user (null on
     * success), so the client has a readable text without rebuilding it from the manual
     * items — the whole plan keeps its own fields either way.
     */
    public record RoomFillPlanDto(
            boolean applied,
            boolean applicable,
            int createdRooms,
            int reusedRooms,
            int assignedLessons,
            List<ClassRoomPlanDto> classes,
            List<ManualItemDto> manualItems,
            List<DraftPublishReadiness.Blocker> blockers,
            String message) {}

    private RoomFillDtos() {}
}
