package com.paiban.integration.dingtalk;

import com.paiban.integration.domain.DingUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Deterministic stand-in used by tests and by developers without a DingTalk tenant. */
@Component
@ConditionalOnProperty(name = "paiban.integration.dingtalk.fake", havingValue = "true")
public class FakeDingTalkClient implements DingTalkClient {

    private final Map<String, DingUser> users = new ConcurrentHashMap<>();

    public FakeDingTalkClient() {
        users.put("dd-001", new DingUser("dd-001", "张三", "13800000001", "zhang@example.com", "EMP001"));
        users.put("dd-002", new DingUser("dd-002", "李四", "13800000002", "li@example.com", "EMP002"));
    }

    @Override
    public String exchangeAuthCode(String authCode) {
        if (authCode != null && authCode.startsWith("fake:")) {
            String employeeNo = authCode.substring("fake:".length());
            return users.values().stream()
                    .filter(user -> employeeNo.equals(user.jobNumber()))
                    .map(DingUser::userId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未知的测试工号: " + employeeNo));
        }
        throw new IllegalArgumentException("无效的测试 authCode");
    }

    @Override
    public List<DingUser> listUsers() {
        return new ArrayList<>(users.values());
    }

    @Override
    public boolean sendWorkNotification(String dingtalkUserId, String title, String markdown, String link) {
        return dingtalkUserId != null && !dingtalkUserId.isBlank();
    }
}
