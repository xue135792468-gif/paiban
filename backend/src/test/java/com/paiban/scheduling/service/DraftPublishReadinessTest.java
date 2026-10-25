package com.paiban.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.scheduling.service.DraftPublishReadiness.Blocker;
import com.paiban.scheduling.service.DraftPublishReadiness.Facts;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The publishing judgement on its own: no repositories, no Spring. */
class DraftPublishReadinessTest {

    private static Facts facts(int lessons, int unscheduled, int missingRooms, int invalidPeriods, int conflicts) {
        return new Facts(false, lessons, unscheduled, missingRooms, invalidPeriods, conflicts, false);
    }

    @Test
    void aCompleteDraftHasNoBlockers() {
        assertThat(DraftPublishReadiness.blockers(facts(10, 0, 0, 0, 0))).isEmpty();
    }

    @Test
    void missingRoomsAndUnscheduledLessonsAreCountedSeparately() {
        List<Blocker> blockers = DraftPublishReadiness.blockers(facts(10, 2, 8, 1, 0));

        assertThat(blockers)
                .extracting(Blocker::code)
                .containsExactly(
                        DraftPublishReadiness.INVALID_PERIODS,
                        DraftPublishReadiness.UNSCHEDULED_LESSONS,
                        DraftPublishReadiness.MISSING_ROOMS);
        assertThat(blockers).allSatisfy(blocker -> {
            assertThat(blocker.message()).isNotBlank();
            assertThat(blocker.action()).isNotBlank();
        });
        assertThat(blockers.get(1).count()).isEqualTo(2);
        assertThat(blockers.get(1).message()).contains("2");
        assertThat(blockers.get(2).count()).isEqualTo(8);
        assertThat(blockers.get(2).message()).contains("8");
    }

    @Test
    void anEmptyDraftSaysSoWithoutInventingCounts() {
        List<Blocker> blockers = DraftPublishReadiness.blockers(facts(0, 0, 0, 0, 0));

        assertThat(blockers).extracting(Blocker::code).containsExactly(DraftPublishReadiness.DRAFT_EMPTY);
        assertThat(blockers.get(0).count()).isZero();
    }

    @Test
    void statusBlockersComeFirstAndLastSoTheFirstReasonIsTheFirstFix() {
        Facts everything = new Facts(true, 0, 1, 1, 1, 1, true);

        assertThat(DraftPublishReadiness.blockers(everything))
                .extracting(Blocker::code)
                .containsExactly(
                        DraftPublishReadiness.DRAFT_PUBLISHED,
                        DraftPublishReadiness.DRAFT_EMPTY,
                        DraftPublishReadiness.INVALID_PERIODS,
                        DraftPublishReadiness.UNSCHEDULED_LESSONS,
                        DraftPublishReadiness.MISSING_ROOMS,
                        DraftPublishReadiness.HARD_CONFLICTS,
                        DraftPublishReadiness.SEMESTER_ALREADY_PUBLISHED);
    }
}
