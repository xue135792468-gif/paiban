package com.paiban.ai.domain;

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
@Table(name = "ai_provider")
@Getter
@Setter
@NoArgsConstructor
public class AiProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** preset / custom */
    @Column(nullable = false, length = 20)
    private String type;

    /** openai / anthropic / gemini — the wire protocol, independent of the vendor name. */
    @Column(nullable = false, length = 20)
    private String protocol = "openai";

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    /** Base64(iv || AES-GCM ciphertext). Never decrypted outside the service layer. */
    @Column(name = "api_key_cipher", length = 1000)
    private String apiKeyCipher;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
