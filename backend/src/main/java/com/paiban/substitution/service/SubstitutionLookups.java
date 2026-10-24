package com.paiban.substitution.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;

/** Shared existence checks; kept out of the services so they cannot drift. */
final class SubstitutionLookups {

    private SubstitutionLookups() {}

    static SubstitutionRequest requireRequest(SubstitutionRequestRepository requests, Long requestId) {
        return requests.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("调代申请不存在: " + requestId));
    }

    static void requireTeacher(TeacherRepository teachers, Long teacherId) {
        if (!teachers.existsById(teacherId)) {
            throw ApiException.notFound("教师不存在: " + teacherId);
        }
    }
}
