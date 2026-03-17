package com.tinyurl.controller;

import com.tinyurl.config.AppProperties;
import com.tinyurl.dto.CreateUrlRequest;
import com.tinyurl.dto.CreateUrlResponse;
import com.tinyurl.dto.UrlMapping;
import com.tinyurl.service.UrlService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/urls")
public class UrlController {

    private final UrlService urlService;
    private final AppProperties appProperties;

    public UrlController(UrlService urlService, AppProperties appProperties) {
        this.urlService = urlService;
        this.appProperties = appProperties;
    }

    @PostMapping
    public ResponseEntity<CreateUrlResponse> create(@Valid @RequestBody CreateUrlRequest request) {
        UrlMapping created = urlService.shortenUrl(request);
        String baseUrl = appProperties.baseUrl().endsWith("/")
            ? appProperties.baseUrl().substring(0, appProperties.baseUrl().length() - 1)
            : appProperties.baseUrl();
        CreateUrlResponse response = new CreateUrlResponse(
            baseUrl + "/" + created.shortCode(),
            created.shortCode(),
            created.originalUrl(),
            created.expiresAt()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
