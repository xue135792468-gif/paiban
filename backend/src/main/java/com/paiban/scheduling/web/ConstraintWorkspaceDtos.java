package com.paiban.scheduling.web;

import com.paiban.scheduling.web.ConstraintDtos.TemplateReferenceIssue;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ConstraintWorkspaceDtos {

    private ConstraintWorkspaceDtos() {}

    /**
     * {@code baseRevision} is the workspace revision the device started from; an
     * autosave that names an older one is refused with the newer copy.
     */
    public record SaveWorkspaceRequest(Long baseRevision, Long baseVersion,
                                       Map<String, Object> payload) {}

    /**
     * The stored workspace plus the server's revalidation of its unsaved table
     * configuration. {@code exists=false} is not an error: it simply means this account
     * has no unfinished work in this semester.
     */
    public record ConstraintWorkspaceDto(boolean exists, long revision, long baseVersion,
                                         Map<String, Object> payload, Instant updatedAt,
                                         List<String> errors,
                                         List<TemplateReferenceIssue> invalidReferences) {}
}
