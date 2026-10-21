package com.paiban.integration.feishu;

public record FeishuUser(String userId, String openId, String name, String mobile,
                         String email, String employeeNo) {}
