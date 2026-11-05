package com.paiban.substitution.service;

import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;

/**
 * One place for "the baseline entry this history row pointed at is gone".
 *
 * <p>Withdrawing a publish deletes {@code timetable_entry}; the foreign keys turn the
 * rows' {@code entry_id} into NULL so the history survives. From that moment the class,
 * course and slot only exist as the request's snapshot fields, and every read model that
 * needs them (class filtering, record/CSV display) must fall back the same way.
 */
final class SubstitutionSnapshots {

    private SubstitutionSnapshots() {}

    /**
     * The request that supplies the snapshot, or {@code null} when the entry still exists
     * or the row never had one.
     */
    static SubstitutionRequest requestOf(Substitution row, SubstitutionRequestRepository requests) {
        if (row.getEntryId() != null || row.getRequestId() == null) {
            return null;
        }
        return requests.findById(row.getRequestId()).orElse(null);
    }
}
