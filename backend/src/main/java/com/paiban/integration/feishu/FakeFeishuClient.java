package com.paiban.integration.feishu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Deterministic stand-in used by tests and by developers without a Feishu tenant. */
@Component
@ConditionalOnProperty(name = "paiban.integration.feishu.fake", havingValue = "true")
public class FakeFeishuClient implements FeishuClient {

    private final Map<String, FeishuUser> usersByEmployeeNo = new ConcurrentHashMap<>();
    private final List<FeishuMessage> sentMessages = new CopyOnWriteArrayList<>();

    public FakeFeishuClient() {
        usersByEmployeeNo.put("EMP001",
                new FeishuUser("fs-001", "ou-001", "张三", "13800000001", "zhang@example.com", "EMP001"));
        usersByEmployeeNo.put("EMP002",
                new FeishuUser("fs-002", "ou-002", "李四", "13800000002", "li@example.com", "EMP002"));
    }

    @Override
    public FeishuUser exchangeAuthCode(String code) {
        if (code != null && code.startsWith("fake:")) {
            String employeeNo = code.substring("fake:".length());
            FeishuUser user = usersByEmployeeNo.get(employeeNo);
            if (user != null) {
                return user;
            }
            throw new IllegalArgumentException("未知的测试工号: " + employeeNo);
        }
        throw new IllegalArgumentException("无效的测试 code");
    }

    @Override
    public List<FeishuDepartment> listDepartments() {
        return List.of(new FeishuDepartment("0", "根部门", null));
    }

    @Override
    public List<FeishuUser> listUsers() {
        return new ArrayList<>(usersByEmployeeNo.values());
    }

    @Override
    public boolean sendMessage(FeishuMessage message) {
        if (message == null || (message.openId() == null && message.userId() == null)) {
            return false;
        }
        sentMessages.add(message);
        return true;
    }

    public List<FeishuMessage> sentMessages() {
        return List.copyOf(sentMessages);
    }

    public void clearMessages() {
        sentMessages.clear();
    }
}
