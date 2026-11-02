package com.paiban.common.policy;

import com.paiban.common.settings.SettingsService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Service;

/**
 * Single source of truth for the exchange policy shared by draft swaps and
 * published-timetable substitutions.
 *
 * <p>It reads the legacy settings keys through {@link SettingsService}, so
 * stored values, defaults and clamping stay backward compatible. Two settings
 * reads per {@link #current()} call are intentional: the fingerprint must
 * describe exactly the values returned in the same snapshot.
 */
@Service
public class ExchangePolicyService {

    private final SettingsService settings;

    public ExchangePolicyService(SettingsService settings) {
        this.settings = settings;
    }

    public ExchangePolicySnapshot current() {
        int maxMovedLessons = settings.exchangeMaxMovedLessons();
        ExchangeRankingPreset preset = settings.exchangeRankingPreset();
        return new ExchangePolicySnapshot(maxMovedLessons, preset,
                fingerprint(maxMovedLessons, preset));
    }

    /** SHA-256 hex of {@code maxMovedLessons + "|" + preset.code()} (UTF-8). */
    static String fingerprint(int maxMovedLessons, ExchangeRankingPreset preset) {
        String raw = maxMovedLessons + "|" + preset.code();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required on the JVM", ex);
        }
    }
}
