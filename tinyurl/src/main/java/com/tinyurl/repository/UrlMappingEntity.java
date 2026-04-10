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

    @Column(name = "creator_ip", length = 45)
    private String creatorIp;

    @Column(name = "creator_user_agent", length = 512)
    private String creatorUserAgent;

    @Column(name = "referer", length = 2048)
    private String referer;

    @Column(name = "click_count", nullable = false)
    private long clickCount;

    protected UrlMappingEntity() {
    }

    public UrlMappingEntity(
        Long id,
        String shortCode,
        String originalUrl,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        boolean hasExplicitExpiry,
        String creatorIp,
        String creatorUserAgent,
        String referer
    ) {
        this.id = id;
        this.shortCode = shortCode;
        this.originalUrl = originalUrl;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.hasExplicitExpiry = hasExplicitExpiry;
        this.creatorIp = creatorIp;
        this.creatorUserAgent = creatorUserAgent;
        this.referer = referer;
        this.clickCount = 0;
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

    public String getCreatorIp() {
        return creatorIp;
    }

    public String getCreatorUserAgent() {
        return creatorUserAgent;
    }

    public String getReferer() {
        return referer;
    }

    public long getClickCount() {
        return clickCount;
    }

    public void incrementClickCount() {
        this.clickCount++;
    }

}
