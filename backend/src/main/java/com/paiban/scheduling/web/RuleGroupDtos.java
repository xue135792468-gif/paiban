package com.paiban.scheduling.web;

import java.util.List;

/** API shapes of the user-group feature (ticket 04). */
public final class RuleGroupDtos {

    private RuleGroupDtos() {}

    /**
     * One requested member. The server accepts only the fields that belong to the
     * group's type; all others must be null.
     */
    public record RuleGroupMemberRequest(Long teacherId, Long courseId,
                                         Integer dayOfWeek, Integer periodNo) {}

    public record RuleGroupRequest(String type, String name, Long semesterId,
                                   List<RuleGroupMemberRequest> members) {}

    /** {@code kind} is {@code TEACHER} / {@code COURSE} / {@code TIME}. */
    public record RuleGroupMemberDto(Long id, String kind, Long objectId, String name,
                                     Integer dayOfWeek, Integer periodNo) {}

    public record RuleGroupDto(Long id, String type, String name, Long semesterId,
                               List<RuleGroupMemberDto> members) {}

    /**
     * One rule card that references a group. {@code groupKey} is the card key
     * ({@code constraint_entry.group_key}), null for a stand-alone row;
     * {@code entryIds} pins the exact rows so deletion never touches another card.
     */
    public record RuleGroupReferenceDto(Long configId, Long semesterId, String semesterName,
                                        String type, String typeName, String groupKey,
                                        List<Long> entryIds) {}

    /** What deleting a group would touch; returned by both delete-impact and 409. */
    public record RuleGroupImpactDto(Long id, String name, String type,
                                     List<RuleGroupReferenceDto> rules, String message) {}
}
