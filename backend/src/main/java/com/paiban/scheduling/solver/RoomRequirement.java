package com.paiban.scheduling.solver;

import org.springframework.util.StringUtils;

/**
 * Whether a course's required room type names a special room.
 *
 * <p>{@code NORMAL} is the ordinary classroom, so the lesson belongs to the admin
 * class's own fixed classroom like any other ordinary lesson. Only the remaining
 * non-blank types ({@code LAB}, {@code PLAYGROUND}, {@code OTHER}, ...) are special:
 * there the course requirement decides the room.
 */
public final class RoomRequirement {

    private static final String NORMAL = "NORMAL";

    private RoomRequirement() {}

    /**
     * True when the type is present and, once trimmed, is not {@code NORMAL}
     * (case-insensitive). Blank and {@code NORMAL} both mean an ordinary classroom.
     */
    public static boolean isSpecial(String requiredRoomType) {
        return StringUtils.hasText(requiredRoomType)
                && !NORMAL.equalsIgnoreCase(requiredRoomType.trim());
    }
}
