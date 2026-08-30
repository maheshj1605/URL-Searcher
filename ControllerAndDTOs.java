package com.mahii.urlshortener.controller;

import com.mahii.urlshortener.dto.CreateUrlRequest;
import com.mahii.urlshortener.dto.CreateUrlResponse;
import com.mahii.urlshortener.service.UrlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API Controller for URL shortening.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@Tag(name = "URL Shortener API", description = "Create and manage shortened URLs")
public class UrlController {
    
    @Autowired
    private UrlService urlService;
    
    /**
     * Create a shortened URL.
     * 
     * POST /api/v1/shorten
     * Content-Type: application/json
     * 
     * {
     *   "originalUrl": "https://example.com/very/long/url",
     *   "customAlias": "my-link",
     *   "expiryDays": 30
     * }
     */
    @PostMapping("/shorten")
    @Operation(summary = "Create a shortened URL", description = "Generate a short code for a long URL")
    public ResponseEntity<CreateUrlResponse> shortenUrl(
            @Valid @RequestBody CreateUrlRequest request,
            HttpServletRequest httpRequest) {
        
        String ipAddress = getClientIpAddress(httpRequest);
        log.info("Shorten request from {}: {}", ipAddress, request.getOriginalUrl());
        
        CreateUrlResponse response = urlService.shortenUrl(request, ipAddress);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    /**
     * Redirect to original URL.
     * 
     * GET /:shortCode
     * Response: 301 Moved Permanently
     */
    @GetMapping("/{shortCode}")
    @Operation(summary = "Redirect to original URL", description = "Get the original URL and redirect")
    public ResponseEntity<Void> redirect(
            @PathVariable String shortCode,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        
        String ipAddress = getClientIpAddress(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        
        String originalUrl = urlService.resolveUrl(shortCode, ipAddress, userAgent);
        
        log.info("Redirect from {} to {}", shortCode, originalUrl);
        
        response.setHeader("Location", originalUrl);
        response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
        
        return new ResponseEntity<>(HttpStatus.MOVED_PERMANENTLY);
    }
    
    /**
     * Get URL details.
     * 
     * GET /api/v1/urls/{shortCode}
     */
    @GetMapping("/urls/{shortCode}")
    @Operation(summary = "Get URL details", description = "Retrieve metadata about a shortened URL")
    public ResponseEntity<CreateUrlResponse> getUrlDetails(
            @PathVariable String shortCode) {
        
        log.info("Get details for: {}", shortCode);
        CreateUrlResponse response = urlService.getUrlDetails(shortCode);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Delete a shortened URL.
     * 
     * DELETE /api/v1/urls/{shortCode}
     */
    @DeleteMapping("/urls/{shortCode}")
    @Operation(summary = "Delete a shortened URL", description = "Deactivate a shortened URL")
    public ResponseEntity<Void> deleteUrl(
            @PathVariable String shortCode) {
        
        log.info("Delete request for: {}", shortCode);
        urlService.deleteUrl(shortCode);
        return ResponseEntity.noContent().build();
    }
    
    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Check if service is running")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(new HealthResponse("UP", "URL Shortener service is running"));
    }
    
    /**
     * Extract client IP address from request.
     * Handles proxied requests (X-Forwarded-For, X-Real-IP).
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }
}

// ============ DTOs ============

package com.mahii.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Request DTO for creating a shortened URL.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to create a shortened URL")
public class CreateUrlRequest {
    
    @NotBlank(message = "Original URL is required")
    @Schema(description = "The long URL to shorten", example = "https://github.com/maheshj1605/url-shortener")
    private String originalUrl;
    
    @Schema(description = "Custom alias for the short URL (optional)", example = "my-project")
    private String customAlias;
    
    @Schema(description = "URL title (optional)", example = "My URL Shortener")
    private String title;
    
    @Schema(description = "URL description (optional)", example = "A distributed URL shortening service")
    private String description;
    
    @Min(value = 1, message = "Expiry days must be at least 1")
    @Max(value = 3650, message = "Expiry days cannot exceed 10 years")
    @Schema(description = "Days until URL expires (optional, default: 365)", example = "30")
    private Integer expiryDays;
}

/**
 * Response DTO for shortened URL.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response containing the shortened URL details")
public class CreateUrlResponse {
    
    @Schema(description = "The short code", example = "abc123")
    private String shortCode;
    
    @Schema(description = "The full short URL", example = "http://short.url/abc123")
    private String shortUrl;
    
    @Schema(description = "The original long URL")
    private String originalUrl;
    
    @Schema(description = "Custom alias if provided")
    private String customAlias;
    
    @Schema(description = "URL title")
    private String title;
    
    @Schema(description = "URL description")
    private String description;
    
    @Schema(description = "When this URL expires")
    private LocalDateTime expiresAt;
    
    @Schema(description = "When this URL was created")
    private LocalDateTime createdAt;
    
    @Schema(description = "Total click count")
    private Long totalClicks;
    
    @Schema(description = "Is this URL active")
    private Boolean isActive;
}

/**
 * Health check response.
 */
@Data
@AllArgsConstructor
class HealthResponse {
    private String status;
    private String message;
}

/**
 * Error response.
 */
@Data
@AllArgsConstructor
@Builder
@Schema(description = "Error response")
class ErrorResponse {
    
    @Schema(description = "HTTP status code")
    private int status;
    
    @Schema(description = "Error message")
    private String message;
    
    @Schema(description = "Error timestamp")
    private LocalDateTime timestamp;
    
    @Schema(description = "Request path")
    private String path;
}
