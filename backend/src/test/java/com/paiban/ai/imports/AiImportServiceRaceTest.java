package com.paiban.ai.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiGateway;
import com.paiban.ai.imports.ImportDtos.PreviewDto;
import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.service.UserService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The races a background parse creates against the request that stops or abandons it.
 * A real database cannot be made to lose a version on demand, so the repository hands
 * out the stale row a concurrent writer would have left behind.
 */
class AiImportServiceRaceTest {

    private final AiImportLogRepository logs = mock(AiImportLogRepository.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final UserService users = mock(UserService.class);
    private AiImportService service;

    @BeforeEach
    void setUp() {
        when(settings.aiImportConfidenceThreshold()).thenReturn(0.8);
        service = new AiImportService(
                mock(AiGateway.class),
                logs,
                new AiImportParser(new ObjectMapper()),
                mock(TeacherRepository.class),
                settings,
                mock(UploadStorage.class),
                new ObjectMapper(),
                users,
                mock(TimetableImportService.class),
                mock(ImportMappingService.class),
                Runnable::run,
                new NoTransaction());
    }

    @Test
    void stopRetriesOnceWhenTheRowChangedUnderIt() {
        AiImportLog outer = parsingSession();
        AiImportLog stale = parsingSession();
        AiImportLog fresh = parsingSession();
        when(logs.findById(1L)).thenReturn(
                Optional.of(outer), Optional.of(stale), Optional.of(fresh), Optional.of(fresh));
        when(logs.save(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(AiImportLog.class, 1L))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PreviewDto result = service.stop(1L, "admin");

        // The first attempt lost the version race; the second read the row again and won.
        verify(logs, times(2)).save(any());
        assertThat(fresh.getStatus()).isEqualTo(AiImportLog.PREVIEW);
        assertThat(result.status()).isEqualTo(AiImportLog.PREVIEW);
        assertThat(result.progress().completed()).isEqualTo(1);
    }

    @Test
    void stopGivesUpWithAConflictWhenTheRowKeepsChanging() {
        when(logs.findById(1L)).thenReturn(
                Optional.of(parsingSession()), Optional.of(parsingSession()), Optional.of(parsingSession()));
        when(logs.save(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(AiImportLog.class, 1L));

        ApiException failure = catchThrowableOfType(
                () -> service.stop(1L, "admin"), ApiException.class);

        assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(failure.getMessage()).isEqualTo("导入状态已被后台更新，请刷新后重试");
    }

    @Test
    void stopIsAConflictWhenTheParseFinishedInTheMeantime() {
        // The pre-check read PARSING, but the worker finished the last batch before the
        // write transaction could claim the stop.
        when(logs.findById(1L)).thenReturn(
                Optional.of(parsingSession()), Optional.of(finishedSession()));

        ApiException failure = catchThrowableOfType(
                () -> service.stop(1L, "admin"), ApiException.class);

        assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(failure.getMessage()).isEqualTo("解析已结束，无法停止");
    }

    private AiImportLog parsingSession() {
        return session(AiImportLog.PARSING);
    }

    private AiImportLog finishedSession() {
        return session(AiImportLog.PREVIEW);
    }

    private AiImportLog session(String status) {
        AiImportLog log = new AiImportLog();
        log.setId(1L);
        log.setUsername("admin");
        log.setImportType("excel");
        log.setTargetType("timetable");
        log.setStatus(status);
        log.setRowCount(1);
        log.setTotalBatches(1);
        log.setCompletedBatches(1);
        log.setBatchesJson("[{\"index\":1,\"label\":\"一年级1班\",\"status\":\"DONE\","
                + "\"sourceRows\":[[\"一年级1班\",\"语文\"]],\"rows\":[],\"mappings\":[]}]");
        return log;
    }

    /** Enough of a transaction manager for a short write: no resource to bind or roll back. */
    private static final class NoTransaction implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // nothing to flush in this harness
        }

        @Override
        public void rollback(TransactionStatus status) {
            // nothing to undo in this harness
        }
    }
}
