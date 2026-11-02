package com.paiban.common.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paiban.common.policy.ExchangePolicyService;
import com.paiban.common.policy.ExchangePolicySnapshot;
import com.paiban.common.policy.ExchangeRankingPreset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the backward-compatible reads behind the renamed "最多变动几节课" setting
 * and the shared policy fingerprint.
 */
class SettingsPolicyTest {

    private static final String MAX_KEY = SettingsService.SUBSTITUTION_MAX_PLAN_TEACHERS;
    private static final String PRESET_KEY = SettingsService.SUBSTITUTION_RANKING_PRESET;

    @Test
    void defaultsToSixLessonsAndMinImpact() {
        SettingsService settings = serviceWith(Map.of());

        assertThat(settings.exchangeMaxMovedLessons()).isEqualTo(6);
        assertThat(settings.exchangeRankingPreset()).isEqualTo(ExchangeRankingPreset.MIN_IMPACT);

        ExchangePolicySnapshot policy = new ExchangePolicyService(settings).current();
        assertThat(policy.maxMovedLessons()).isEqualTo(6);
        assertThat(policy.rankingPreset()).isEqualTo(ExchangeRankingPreset.MIN_IMPACT);
        assertThat(policy.fingerprint()).hasSize(64);
    }

    @Test
    void legacyKeyStillClampsToTwoThroughTenWithSixAsFallback() {
        assertThat(settingsWith(MAX_KEY, "2").exchangeMaxMovedLessons()).isEqualTo(2);
        assertThat(settingsWith(MAX_KEY, "10").exchangeMaxMovedLessons()).isEqualTo(10);
        assertThat(settingsWith(MAX_KEY, "11").exchangeMaxMovedLessons()).isEqualTo(10);
        assertThat(settingsWith(MAX_KEY, "abc").exchangeMaxMovedLessons()).isEqualTo(6);
    }

    @Test
    void legacyTeacherCapMethodAgreesWithTheNewName() {
        SettingsService settings = settingsWith(MAX_KEY, "8");

        assertThat(settings.substitutionMaxPlanTeachers()).isEqualTo(settings.exchangeMaxMovedLessons());
    }

    @Test
    void everyPresetHasItsOwnStableFingerprint() {
        ExchangePolicyService service = new ExchangePolicyService(settingsWith(MAX_KEY, "6"));

        String minImpact = service.current().fingerprint();
        SettingsService minTeachers = serviceWith(Map.of(MAX_KEY, "6", PRESET_KEY, "min-teachers"));
        SettingsService stable = serviceWith(Map.of(MAX_KEY, "6", PRESET_KEY, "stable-timetable"));

        String minTeachersFingerprint = new ExchangePolicyService(minTeachers).current().fingerprint();
        String stableFingerprint = new ExchangePolicyService(stable).current().fingerprint();

        assertThat(minImpact).isNotEqualTo(minTeachersFingerprint);
        assertThat(minImpact).isNotEqualTo(stableFingerprint);
        assertThat(minTeachersFingerprint).isNotEqualTo(stableFingerprint);
        assertThat(service.current().fingerprint()).isEqualTo(minImpact);
        assertThat(new ExchangePolicyService(minTeachers).current().fingerprint())
                .isEqualTo(minTeachersFingerprint);
    }

    private SettingsService settingsWith(String key, String value) {
        return serviceWith(Map.of(key, value));
    }

    private SettingsService serviceWith(Map<String, String> values) {
        AppSettingRepository repository = mock(AppSettingRepository.class);
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        values.forEach((key, value) -> when(repository.findById(key))
                .thenReturn(Optional.of(new AppSetting(key, value))));
        return new SettingsService(repository,
                "approval", "minimal",
                false, "", "", "", "dingtalk",
                false, "", "", "", "", "feishu",
                "");
    }
}
