package com.paiban.integration.feishu;

public record FeishuMessage(String openId, String userId, String title, String content,
                            String link, boolean card) {}
