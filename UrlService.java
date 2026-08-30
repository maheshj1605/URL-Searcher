package com.mahii.urlshortener.service;

import com.mahii.urlshortener.dto.CreateUrlRequest;
import com.mahii.urlshortener.dto.CreateUrlResponse;
import com.mahii.urlshortener.entity.ShortenedUrl;
import com.mahii.urlshortener.exception.UrlNotFoundException;
import com.mahii.urlshortener.repository.UrlRepository;
import com.mahii.urlshortener.util.Base62Encoder;
import com.mahii.urlshortener.util.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Service for managing shortened URLs.
 * 
 * Key responsibilities:
 * - Generate unique short codes using Snowflake IDs
 * - Store and retrieve URL mappings
 * - Manage URL expiration
 * - Handle custom aliases
 * - Track click analytics
 */
@Slf4j
@Service
public class UrlService {
    
    @Autowired
    private UrlRepository urlRepository;
    
    @Autowired
    private SnowflakeIdGenerator idGenerator;
    
    @Autowired
    private Base62Encoder base62Encoder;
    
    @Autowired
    private CacheService cacheService;
    
    @Autowired
    private AnalyticsService analyticsService;
    
    @Autowired
    private RateLimiterService rateLimiterService;
    
    @Value("${url-shortener.default-expiry-days:365}")
    private int defaultExpiryDays;
    
    @Value("${url-shortener.base-url:http://short.url}")
    private String baseUrl;
    
    @Value("${url-shortener.max-url-length:2048}")
    private int maxUrlLength;
    
    /**
     * Create a shortened URL from a long URL.
     * 
     * Flow:
     * 1. Validate input and rate limit
     * 2. Check if URL already shortened (idempotency)
     * 3. Generate unique ID using Snowflake
     * 4. Encode to Base62 short code
     * 5. Save to database
     * 6. Cache result
     */
    @Transactional
    public CreateUrlResponse shortenUrl(CreateUrlRequest request, String ipAddress) {
        // Rate limiting
        if (!rateLimiterService.allowRequest(ipAddress)) {
            throw new RateLimitExceededException("Rate limit exceeded for IP: " + ipAddress);
        }
        
        // Validate
        validateUrlRequest(request);
        
        // Check if already shortened (idempotency)
        ShortenedUrl existing = urlRepository.findByOriginalUrl(request.getOriginalUrl());
        if (existing != null && existing.isValid()) {
            log.info("URL already shortened: {}", request.getOriginalUrl());
            return buildResponse(existing);
        }
        
        // Use custom alias if provided, otherwise generate new short code
        String shortCode;
        if (request.getCustomAlias() != null && !request.getCustomAlias().isBlank()) {
            shortCode = request.getCustomAlias();
            
            // Check if custom alias is already taken
            if (urlRepository.findByCustomAlias(shortCode) != null) {
                throw new IllegalArgumentException("Custom alias already taken: " + shortCode);
            }
        } else {
            // Generate using Snowflake ID generator
            long id = idGenerator.nextId();
            shortCode = base62Encoder.encode(id);
        }
        
        // Calculate expiry
        LocalDateTime expiryAt = null;
        if (request.getExpiryDays() != null && request.getExpiryDays() > 0) {
            expiryAt = LocalDateTime.now().plus(request.getExpiryDays(), ChronoUnit.DAYS);
        } else {
            expiryAt = LocalDateTime.now().plus(defaultExpiryDays, ChronoUnit.DAYS);
        }
        
        // Create and save
        ShortenedUrl shortenedUrl = ShortenedUrl.builder()
                .id(idGenerator.nextId())
                .shortCode(shortCode)
                .originalUrl(request.getOriginalUrl())
                .title(request.getTitle())
                .description(request.getDescription())
                .customAlias(request.getCustomAlias())
                .expiresAt(expiryAt)
                .isActive(true)
                .ipAddress(ipAddress)
                .totalClicks(0L)
                .build();
        
        ShortenedUrl saved = urlRepository.save(shortenedUrl);
        
        // Cache the result
        cacheService.set(shortCode, saved);
        
        log.info("URL shortened successfully: {} -> {}", request.getOriginalUrl(), shortCode);
        
        return buildResponse(saved);
    }
    
    /**
     * Retrieve the original URL by short code.
     * 
     * Flow:
     * 1. Check cache first (L1: Redis, L2: in-memory)
     * 2. If not cached, query database
     * 3. Validate expiration
     * 4. Update cache
     * 5. Async track click analytics
     */
    @Transactional
    public String resolveUrl(String shortCode, String ipAddress, String userAgent) {
        // Try cache first
        ShortenedUrl cached = cacheService.get(shortCode);
        ShortenedUrl shortenedUrl;
        
        if (cached != null) {
            shortenedUrl = cached;
        } else {
            // Query database
            shortenedUrl = urlRepository.findByShortCode(shortCode);
            
            if (shortenedUrl == null) {
                throw new UrlNotFoundException("Short URL not found: " + shortCode);
            }
            
            // Update cache
            cacheService.set(shortCode, shortenedUrl);
        }
        
        // Check if expired or inactive
        if (!shortenedUrl.isValid()) {
            throw new UrlNotFoundException("URL has expired or is inactive: " + shortCode);
        }
        
        // Increment click counter
        shortenedUrl.incrementClicks();
        urlRepository.save(shortenedUrl);
        
        // Async track analytics (fire and forget)
        analyticsService.trackClickAsync(shortCode, ipAddress, userAgent);
        
        log.debug("URL resolved: {} -> {}", shortCode, shortenedUrl.getOriginalUrl());
        
        return shortenedUrl.getOriginalUrl();
    }
    
    /**
     * Delete a shortened URL.
     */
    @Transactional
    public void deleteUrl(String shortCode) {
        ShortenedUrl shortenedUrl = urlRepository.findByShortCode(shortCode);
        
        if (shortenedUrl == null) {
            throw new UrlNotFoundException("Short URL not found: " + shortCode);
        }
        
        // Soft delete
        shortenedUrl.setIsActive(false);
        urlRepository.save(shortenedUrl);
        
        // Invalidate cache
        cacheService.delete(shortCode);
        
        log.info("URL deleted: {}", shortCode);
    }
    
    /**
     * Get URL details.
     */
    @Transactional(readOnly = true)
    public CreateUrlResponse getUrlDetails(String shortCode) {
        ShortenedUrl shortenedUrl = cacheService.get(shortCode);
        
        if (shortenedUrl == null) {
            shortenedUrl = urlRepository.findByShortCode(shortCode);
        }
        
        if (shortenedUrl == null) {
            throw new UrlNotFoundException("Short URL not found: " + shortCode);
        }
        
        return buildResponse(shortenedUrl);
    }
    
    /**
     * Validate URL request.
     */
    private void validateUrlRequest(CreateUrlRequest request) {
        if (request.getOriginalUrl() == null || request.getOriginalUrl().isBlank()) {
            throw new IllegalArgumentException("Original URL cannot be empty");
        }
        
        if (request.getOriginalUrl().length() > maxUrlLength) {
            throw new IllegalArgumentException("URL too long (max " + maxUrlLength + " chars)");
        }
        
        // Basic URL validation
        if (!request.getOriginalUrl().matches("^https?://.*")) {
            throw new IllegalArgumentException("URL must start with http:// or https://");
        }
        
        if (request.getCustomAlias() != null) {
            if (request.getCustomAlias().length() < 3) {
                throw new IllegalArgumentException("Custom alias must be at least 3 characters");
            }
            if (request.getCustomAlias().length() > 100) {
                throw new IllegalArgumentException("Custom alias must be at most 100 characters");
            }
            if (!request.getCustomAlias().matches("^[a-zA-Z0-9_-]+$")) {
                throw new IllegalArgumentException("Custom alias can only contain alphanumeric characters, hyphens, and underscores");
            }
        }
    }
    
    /**
     * Build response DTO from entity.
     */
    private CreateUrlResponse buildResponse(ShortenedUrl entity) {
        return CreateUrlResponse.builder()
                .shortCode(entity.getShortCode())
                .shortUrl(baseUrl + "/" + entity.getShortCode())
                .originalUrl(entity.getOriginalUrl())
                .customAlias(entity.getCustomAlias())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .expiresAt(entity.getExpiresAt())
                .createdAt(entity.getCreatedAt())
                .totalClicks(entity.getTotalClicks())
                .isActive(entity.getIsActive())
                .build();
    }
}
