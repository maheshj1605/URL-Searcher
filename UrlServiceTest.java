package com.mahii.urlshortener.service;

import com.mahii.urlshortener.dto.CreateUrlRequest;
import com.mahii.urlshortener.dto.CreateUrlResponse;
import com.mahii.urlshortener.entity.ShortenedUrl;
import com.mahii.urlshortener.exception.UrlNotFoundException;
import com.mahii.urlshortener.repository.UrlRepository;
import com.mahii.urlshortener.util.Base62Encoder;
import com.mahii.urlshortener.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UrlService.
 */
@ExtendWith(MockitoExtension.class)
class UrlServiceTest {
    
    @Mock
    private UrlRepository urlRepository;
    
    @Mock
    private SnowflakeIdGenerator idGenerator;
    
    @Mock
    private Base62Encoder base62Encoder;
    
    @Mock
    private CacheService cacheService;
    
    @Mock
    private AnalyticsService analyticsService;
    
    @Mock
    private RateLimiterService rateLimiterService;
    
    @InjectMocks
    private UrlService urlService;
    
    private CreateUrlRequest validRequest;
    private ShortenedUrl shortenedUrl;
    private String testIp = "192.168.1.1";
    
    @BeforeEach
    void setUp() {
        validRequest = CreateUrlRequest.builder()
                .originalUrl("https://github.com/maheshj1605/url-shortener")
                .title("URL Shortener")
                .description("A distributed URL shortening service")
                .expiryDays(30)
                .build();
        
        shortenedUrl = ShortenedUrl.builder()
                .id(12345L)
                .shortCode("abc123")
                .originalUrl("https://github.com/maheshj1605/url-shortener")
                .title("URL Shortener")
                .description("A distributed URL shortening service")
                .totalClicks(0L)
                .isActive(true)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .createdAt(LocalDateTime.now())
                .build();
    }
    
    @Test
    void testShortenUrl_Success() {
        // Arrange
        when(rateLimiterService.allowRequest(testIp)).thenReturn(true);
        when(idGenerator.nextId()).thenReturn(12345L).thenReturn(99999L);
        when(base62Encoder.encode(12345L)).thenReturn("abc123");
        when(urlRepository.findByOriginalUrl("https://github.com/maheshj1605/url-shortener"))
                .thenReturn(null);
        when(urlRepository.save(any(ShortenedUrl.class))).thenReturn(shortenedUrl);
        
        // Act
        CreateUrlResponse response = urlService.shortenUrl(validRequest, testIp);
        
        // Assert
        assertNotNull(response);
        assertEquals("abc123", response.getShortCode());
        assertTrue(response.getShortUrl().contains("abc123"));
        assertEquals("https://github.com/maheshj1605/url-shortener", response.getOriginalUrl());
        assertTrue(response.getIsActive());
        
        // Verify interactions
        verify(rateLimiterService).allowRequest(testIp);
        verify(urlRepository).save(any(ShortenedUrl.class));
        verify(cacheService).set("abc123", shortenedUrl);
    }
    
    @Test
    void testShortenUrl_RateLimitExceeded() {
        // Arrange
        when(rateLimiterService.allowRequest(testIp)).thenReturn(false);
        
        // Act & Assert
        assertThrows(RateLimitExceededException.class, 
                () -> urlService.shortenUrl(validRequest, testIp));
        
        verify(rateLimiterService).allowRequest(testIp);
        verify(urlRepository, never()).save(any());
    }
    
    @Test
    void testShortenUrl_InvalidUrl() {
        // Arrange
        when(rateLimiterService.allowRequest(testIp)).thenReturn(true);
        validRequest.setOriginalUrl("not-a-valid-url");
        
        // Act & Assert
        assertThrows(IllegalArgumentException.class, 
                () -> urlService.shortenUrl(validRequest, testIp));
    }
    
    @Test
    void testShortenUrl_CustomAlias() {
        // Arrange
        validRequest.setCustomAlias("my-link");
        when(rateLimiterService.allowRequest(testIp)).thenReturn(true);
        when(urlRepository.findByOriginalUrl(validRequest.getOriginalUrl())).thenReturn(null);
        when(urlRepository.findByCustomAlias("my-link")).thenReturn(null);
        when(idGenerator.nextId()).thenReturn(99999L);
        
        shortenedUrl.setShortCode("my-link");
        shortenedUrl.setCustomAlias("my-link");
        when(urlRepository.save(any(ShortenedUrl.class))).thenReturn(shortenedUrl);
        
        // Act
        CreateUrlResponse response = urlService.shortenUrl(validRequest, testIp);
        
        // Assert
        assertEquals("my-link", response.getShortCode());
        assertEquals("my-link", response.getCustomAlias());
    }
    
    @Test
    void testShortenUrl_IdempotentRequest() {
        // Arrange
        when(rateLimiterService.allowRequest(testIp)).thenReturn(true);
        when(urlRepository.findByOriginalUrl(validRequest.getOriginalUrl()))
                .thenReturn(shortenedUrl);
        
        // Act
        CreateUrlResponse response = urlService.shortenUrl(validRequest, testIp);
        
        // Assert
        assertNotNull(response);
        assertEquals("abc123", response.getShortCode());
        verify(urlRepository, never()).save(any()); // Should not save again
    }
    
    @Test
    void testResolveUrl_Success() {
        // Arrange
        when(cacheService.get("abc123")).thenReturn(shortenedUrl);
        String userAgent = "Mozilla/5.0";
        
        // Act
        String resolvedUrl = urlService.resolveUrl("abc123", testIp, userAgent);
        
        // Assert
        assertEquals("https://github.com/maheshj1605/url-shortener", resolvedUrl);
        assertEquals(1L, shortenedUrl.getTotalClicks());
        
        // Verify analytics tracking
        verify(analyticsService).trackClickAsync("abc123", testIp, userAgent);
    }
    
    @Test
    void testResolveUrl_NotFound() {
        // Arrange
        when(cacheService.get("invalid")).thenReturn(null);
        when(urlRepository.findByShortCode("invalid")).thenReturn(null);
        
        // Act & Assert
        assertThrows(UrlNotFoundException.class, 
                () -> urlService.resolveUrl("invalid", testIp, "agent"));
    }
    
    @Test
    void testResolveUrl_Expired() {
        // Arrange
        ShortenedUrl expiredUrl = shortenedUrl.clone();
        expiredUrl.setExpiresAt(LocalDateTime.now().minusDays(1));
        
        when(cacheService.get("abc123")).thenReturn(null);
        when(urlRepository.findByShortCode("abc123")).thenReturn(expiredUrl);
        
        // Act & Assert
        assertThrows(UrlNotFoundException.class, 
                () -> urlService.resolveUrl("abc123", testIp, "agent"));
    }
    
    @Test
    void testResolveUrl_Inactive() {
        // Arrange
        ShortenedUrl inactiveUrl = shortenedUrl.clone();
        inactiveUrl.setIsActive(false);
        
        when(cacheService.get("abc123")).thenReturn(inactiveUrl);
        
        // Act & Assert
        assertThrows(UrlNotFoundException.class, 
                () -> urlService.resolveUrl("abc123", testIp, "agent"));
    }
    
    @Test
    void testDeleteUrl_Success() {
        // Arrange
        when(urlRepository.findByShortCode("abc123")).thenReturn(shortenedUrl);
        
        // Act
        urlService.deleteUrl("abc123");
        
        // Assert
        assertFalse(shortenedUrl.getIsActive());
        verify(urlRepository).save(shortenedUrl);
        verify(cacheService).delete("abc123");
    }
    
    @Test
    void testDeleteUrl_NotFound() {
        // Arrange
        when(urlRepository.findByShortCode("invalid")).thenReturn(null);
        
        // Act & Assert
        assertThrows(UrlNotFoundException.class, 
                () -> urlService.deleteUrl("invalid"));
    }
}
