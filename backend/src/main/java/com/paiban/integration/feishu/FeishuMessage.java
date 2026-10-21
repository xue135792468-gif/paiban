package com.paiban.integration.feishu;

/**
 * A message to deliver through the Feishu IM API.
 *
 * @param templateCode the notification template that produced this message; the
 *                     card builder uses it to pick the header colour
 */
public record FeishuMessage(String openId, String userId, String title, String content,
                            String link, boolean card, String templateCode) {}
