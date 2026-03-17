package com.tinyurl.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "url_mappings")
public class UrlMappingEntity {

    @Id
    private Long id;

    @Column(name = "short_code", nullable = false, unique = true, length = 32)
    private String shortCode;

    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "has_explicit_expiry", nullable = false)
    private boolean hasExplicitExpiry;

    protected UrlMappingEntity() {
    }

    public UrlMappingEntity(
        Long id,
        String shortCode,
        String originalUrl,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        boolean hasExplicitExpiry
    ) {
        this.id = id;
        this.shortCode = shortCode;
        this.originalUrl = originalUrl;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.hasExplicitExpiry = hasExplicitExpiry;
    }

    public Long getId() {
        return id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public boolean hasExplicitExpiry() {
        return hasExplicitExpiry;
    }

}
