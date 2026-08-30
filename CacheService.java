package com.mahii.urlshortener.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mahii.urlshortener.entity.ShortenedUrl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Multi-layer Cache Service for URL resolution.
 * 
 * Caching Strategy:
 * - L1: Caffeine (in-memory, local to each instance)
 * - L2: Redis (distributed, shared across all instances)
 * - L3: Database (source of truth)
 * 
 * Benefits:
 * - Reduced latency: L1 hits are <1ms
 * - Reduced load: Most reads from cache, not DB
 * - Consistency: Redis as single source of truth
 * - Scalability: Works with horizontal scaling
 * 
 * Flow:
 * Read: L1 → L2 → L3
 * Write: L2 + invalidate L1
 */
@Slf4j
@Service
public class CacheService {
    
    @Autowired
    private RedisTemplate<String, ShortenedUrl> redisTemplate;
    
    @Value("${cache.l1.size:10000}")
    private int l1CacheSize;
    
    @Value("${cache.l1.ttl-minutes:5}")
    private int l1TtlMinutes;
    
    @Value("${cache.l2.ttl-minutes:60}")
    private int l2TtlMinutes;
    
    @Value("${cache.enabled:true}")
    private boolean cacheEnabled;
    
    private static final String CACHE_KEY_PREFIX = "url:";
    
    // L1 Cache: In-memory Caffeine cache (local to each instance)
    private final Cache<String, ShortenedUrl> l1Cache;
    
    public CacheService() {
        // Initialize L1 cache with eviction policy
        this.l1Cache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }
    
    /**
     * Get URL from cache (multi-layer).
     * 
     * Lookup order:
     * 1. L1 (Caffeine) - fastest
     * 2. L2 (Redis) - distributed
     * 3. Return null if not cached
     */
    public ShortenedUrl get(String shortCode) {
        if (!cacheEnabled) {
            return null;
        }
        
        String key = CACHE_KEY_PREFIX + shortCode;
        
        try {
            // Try L1 cache first
            ShortenedUrl cached = l1Cache.getIfPresent(key);
            if (cached != null) {
                log.debug("L1 cache hit for: {}", shortCode);
                return cached;
            }
            
            // Try L2 cache (Redis)
            ShortenedUrl redisValue = redisTemplate.opsForValue().get(key);
            if (redisValue != null) {
                log.debug("L2 cache hit for: {}", shortCode);
                // Populate L1 cache
                l1Cache.put(key, redisValue);
                return redisValue;
            }
            
            log.debug("Cache miss for: {}", shortCode);
            return null;
            
        } catch (Exception e) {
            log.error("Cache retrieval error for {}: {}", shortCode, e.getMessage());
            // Fail gracefully - caller will query database
            return null;
        }
    }
    
    /**
     * Set URL in cache (multi-layer).
     * 
     * Stores in both L1 and L2 for consistency.
     */
    public void set(String shortCode, ShortenedUrl url) {
        if (!cacheEnabled) {
            return;
        }
        
        String key = CACHE_KEY_PREFIX + shortCode;
        
        try {
            // Store in L1 cache
            l1Cache.put(key, url);
            
            // Store in L2 cache (Redis) with TTL
            redisTemplate.opsForValue().set(
                    key,
                    url,
                    l2TtlMinutes,
                    TimeUnit.MINUTES
            );
            
            log.debug("Cached URL: {}", shortCode);
            
        } catch (Exception e) {
            log.error("Cache write error for {}: {}", shortCode, e.getMessage());
            // Non-critical - continue without cache
        }
    }
    
    /**
     * Delete URL from cache.
     * 
     * Invalidates both L1 and L2.
     */
    public void delete(String shortCode) {
        String key = CACHE_KEY_PREFIX + shortCode;
        
        try {
            // Remove from L1
            l1Cache.invalidate(key);
            
            // Remove from L2 (Redis)
            redisTemplate.delete(key);
            
            log.debug("Invalidated cache for: {}", shortCode);
            
        } catch (Exception e) {
            log.error("Cache deletion error for {}: {}", shortCode, e.getMessage());
        }
    }
    
    /**
     * Clear all caches.
     * (Use carefully - affects performance)
     */
    public void clearAll() {
        try {
            l1Cache.invalidateAll();
            redisTemplate.delete(redisTemplate.keys(CACHE_KEY_PREFIX + "*"));
            log.info("All caches cleared");
        } catch (Exception e) {
            log.error("Error clearing caches: {}", e.getMessage());
        }
    }
    
    /**
     * Get cache statistics (L1).
     */
    public CacheStats getStats() {
        var stats = l1Cache.stats();
        return new CacheStats(
                stats.hitCount(),
                stats.missCount(),
                stats.loadCount(),
                stats.evictionCount(),
                (double) stats.hitCount() / (stats.hitCount() + stats.missCount())
        );
    }
    
    /**
     * Cache statistics DTO.
     */
    public record CacheStats(
            long hits,
            long misses,
            long loads,
            long evictions,
            double hitRate
    ) {}
}
