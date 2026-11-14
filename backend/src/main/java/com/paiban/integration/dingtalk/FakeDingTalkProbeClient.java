package com.paiban.integration.dingtalk;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Probe-only fake, independent of directory/auth/notification clients so replacing
 * DingTalkClient in existing integration tests does not remove the probe capability.
 */
@Component
@ConditionalOnProperty(name = "paiban.integration.dingtalk.fake", havingValue = "true")
public class FakeDingTalkProbeClient implements DingTalkProbeClient {

    /** Fake-only E2E convention; never interpreted by the real HTTP adapter. */
    public static final String PROBE_RESULT_PREFIX = "fake-probe:";

    private final AtomicReference<DingTalkProbeCode> probeResult =
            new AtomicReference<>(DingTalkProbeCode.OK);
    private final List<ProbeCall> probeCalls = new CopyOnWriteArrayList<>();

    public void resetToDefault() {
        probeResult.set(DingTalkProbeCode.OK);
        probeCalls.clear();
    }

    public FakeDingTalkProbeClient alwaysReturnProbe(DingTalkProbeCode result) {
        probeResult.set(result);
        return this;
    }

    public List<ProbeCall> probeCalls() {
        return List.copyOf(probeCalls);
    }

    @Override
    public DingTalkProbeCode probe(String appKey, String appSecret) {
        probeCalls.add(new ProbeCall(appKey, appSecret));
        if (appSecret != null && appSecret.startsWith(PROBE_RESULT_PREFIX)) {
            try {
                String requested = appSecret.substring(PROBE_RESULT_PREFIX.length())
                        .trim().toUpperCase(Locale.ROOT);
                return DingTalkProbeCode.valueOf(requested);
            } catch (IllegalArgumentException ex) {
                return DingTalkProbeCode.INVALID_RESPONSE;
            }
        }
        return probeResult.get();
    }

    public record ProbeCall(String appKey, String appSecret) {}
}
