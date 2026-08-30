package com.mahii.urlshortener.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * ShortenedUrl entity - stores URL mappings.
 */
@Entity
@Table(name = "shortened_urls", indexes = {
        @Index(name = "idx_short_code", columnList = "short_code", unique = true),
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_expires_at", columnList = "expires_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShortenedUrl {
    
    @Id
    private Long id;
    
    @Column(nullable = false, unique = true, length = 10)
    private String shortCode;
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String originalUrl;
    
    @Column(length = 255)
    private String title;
    
    @Column(length = 500)
    private String description;
    
    @Column(name = "user_id")
    private String userId;
    
    @Column(name = "custom_alias", unique = true, length = 100)
    private String customAlias;
    
    @Column(name = "total_clicks", columnDefinition = "INTEGER DEFAULT 0")
    private Long totalClicks = 0L;
    
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
    
    @Column(name = "is_active", columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean isActive = true;
    
    @Column(name = "ip_address", length = 50)
    private String ipAddress;
    
    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;
    
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    /**
     * Check if this shortened URL has expired.
     */
    public boolean isExpired() {
        if (expiresAt == null) {
            return false; // No expiry set
        }
        return LocalDateTime.now().isAfter(expiresAt);
    }
    
    /**
     * Check if this URL is valid (active and not expired).
     */
    public boolean isValid() {
        return isActive && !isExpired();
    }
    
    /**
     * Increment click counter.
     */
    public void incrementClicks() {
        this.totalClicks = (totalClicks == null ? 0L : totalClicks) + 1L;
    }
}

/**
 * ClickAnalytics entity - stores click event data for analytics.
 */
@Entity
@Table(name = "click_analytics", indexes = {
        @Index(name = "idx_short_code_analytics", columnList = "short_code"),
        @Index(name = "idx_clicked_at", columnList = "clicked_at"),
        @Index(name = "idx_country", columnList = "country")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
class ClickAnalytics {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String shortCode;
    
    @Column(nullable = false)
    private String ipAddress;
    
    @Column(columnDefinition = "TEXT")
    private String userAgent;
    
    @Column(length = 100)
    private String country;
    
    @Column(length = 100)
    private String city;
    
    @Column(length = 100)
    private String referrer;
    
    @Column(length = 50)
    private String device;
    
    @Column(length = 50)
    private String browser;
    
    @Column(columnDefinition = "DOUBLE PRECISION")
    private Double latitude;
    
    @Column(columnDefinition = "DOUBLE PRECISION")
    private Double longitude;
    
    @CreationTimestamp
    @Column(name = "clicked_at", nullable = false, updatable = false)
    private LocalDateTime clickedAt;
}

/**
 * RateLimitKey entity - for Redis-backed rate limiting.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
class RateLimitKey {
    private String key;
    private long tokens;
    private long lastRefillTime;
    
    /**
     * Constructor for new rate limit key.
     */
    public RateLimitKey(String key, long capacity) {
        this.key = key;
        this.tokens = capacity;
        this.lastRefillTime = System.currentTimeMillis();
    }
}
