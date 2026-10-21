package com.paiban.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notification_log")
@Getter
@Setter
@NoArgsConstructor
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String channel;

    @Column(nullable = false, length = 200)
    private String recipient;

    @Column(name = "template_code", nullable = false, length = 64)
    private String templateCode;

    @Column(length = 2000)
    private String content;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(length = 20)
    private String platform;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
