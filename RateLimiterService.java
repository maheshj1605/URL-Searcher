package com.mahii.urlshortener.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Rate Limiter Service using Token Bucket algorithm.
 * 
 * Token Bucket Algorithm:
 * - Bucket has a max capacity (e.g., 100 tokens)
 * - Tokens refill at a constant rate (e.g., 100 tokens per minute)
 * - Each request consumes a token
 * - If bucket is empty, request is rejected
 * 
 * Benefits:
 * - Allows burst traffic (full bucket)
 * - Prevents sustained overload
 * - Distributed (Redis-backed) for horizontal scaling
 * 
 * Example:
 * - Capacity: 100 tokens
 * - Refill rate: 100 tokens/minute
 * - Allows burst of 100 requests immediately
 * - Then sustains 100 requests/minute
 */
@Slf4j
@Service
public class RateLimiterService {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Value("${rate-limiter.capacity:100}")
    private long capacity;
    
    @Value("${rate-limiter.refill-rate:100}")
    private long refillRate; // tokens per minute
    
    @Value("${rate-limiter.enabled:true}")
    private boolean enabled;
    
    private static final String RATE_LIMIT_KEY_PREFIX = "rate_limit:";
    private static final long REFILL_INTERVAL_MILLIS = 60_000; // 1 minute
    
    /**
     * Check if a request should be allowed based on rate limiting.
     * 
     * @param identifier IP address or user ID
     * @return true if request should be allowed, false if rate limited
     */
    public boolean allowRequest(String identifier) {
        if (!enabled) {
            return true; // Rate limiting disabled
        }
        
        String key = RATE_LIMIT_KEY_PREFIX + identifier;
        
        try {
            // Atomic operation: check tokens and decrement if available
            long tokensAvailable = getTokensAvailable(key);
            
            if (tokensAvailable > 0) {
                // Consume one token
                redisTemplate.opsForValue().decrement(key);
                return true;
            } else {
                log.warn("Rate limit exceeded for identifier: {}", identifier);
                return false;
            }
        } catch (Exception e) {
            log.error("Error checking rate limit for {}: {}", identifier, e.getMessage());
            // Fail open - allow request if rate limiter fails
            return true;
        }
    }
    
    /**
     * Get current number of available tokens for an identifier.
     */
    public long getTokensAvailable(String identifier) {
        String key = RATE_LIMIT_KEY_PREFIX + identifier;
        return getTokensAvailable(key);
    }
    
    /**
     * Reset rate limit for an identifier.
     */
    public void reset(String identifier) {
        String key = RATE_LIMIT_KEY_PREFIX + identifier;
        redisTemplate.delete(key);
        log.info("Rate limit reset for identifier: {}", identifier);
    }
    
    /**
     * Internal method to get available tokens with refill logic.
     */
    private long getTokensAvailable(String key) {
        long now = System.currentTimeMillis();
        
        // Get current state from Redis
        Object tokensObj = redisTemplate.opsForValue().get(key);
        Object lastRefillObj = redisTemplate.opsForValue().get(key + ":last_refill");
        
        long currentTokens = (tokensObj != null) ? Long.parseLong(tokensObj.toString()) : capacity;
        long lastRefill = (lastRefillObj != null) ? Long.parseLong(lastRefillObj.toString()) : now;
        
        // Calculate tokens to add based on time elapsed
        long timeSinceLastRefill = now - lastRefill;
        long tokensToAdd = (timeSinceLastRefill * refillRate) / REFILL_INTERVAL_MILLIS;
        
        if (tokensToAdd > 0) {
            // Update tokens (cap at capacity) and refill timestamp
            currentTokens = Math.min(currentTokens + tokensToAdd, capacity);
            redisTemplate.opsForValue().set(key, currentTokens);
            redisTemplate.opsForValue().set(key + ":last_refill", now);
            
            // Set TTL (24 hours) to avoid accumulating stale keys
            redisTemplate.expire(key, java.time.Duration.ofHours(24));
            redisTemplate.expire(key + ":last_refill", java.time.Duration.ofHours(24));
        }
        
        return currentTokens;
    }
    
    /**
     * Initialize rate limit bucket for an identifier.
     */
    public void initialize(String identifier) {
        String key = RATE_LIMIT_KEY_PREFIX + identifier;
        redisTemplate.opsForValue().set(key, capacity);
        redisTemplate.opsForValue().set(key + ":last_refill", System.currentTimeMillis());
        redisTemplate.expire(key, java.time.Duration.ofHours(24));
        redisTemplate.expire(key + ":last_refill", java.time.Duration.ofHours(24));
    }
    
    /**
     * Get rate limit status for monitoring/debugging.
     */
    public RateLimitStatus getStatus(String identifier) {
        String key = RATE_LIMIT_KEY_PREFIX + identifier;
        long tokensAvailable = getTokensAvailable(key);
        long utilizationPercent = ((capacity - tokensAvailable) * 100) / capacity;
        
        return new RateLimitStatus(
                identifier,
                tokensAvailable,
                capacity,
                refillRate,
                utilizationPercent,
                enabled
        );
    }
    
    /**
     * DTO for rate limit status response.
     */
    public record RateLimitStatus(
            String identifier,
            long tokensAvailable,
            long capacity,
            long refillRatePerMinute,
            long utilizationPercent,
            boolean enabled
    ) {}
}
