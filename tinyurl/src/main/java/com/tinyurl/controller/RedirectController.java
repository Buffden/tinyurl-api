package com.tinyurl.controller;

import com.tinyurl.config.AppProperties;
import com.tinyurl.dto.UrlMapping;
import com.tinyurl.exception.GoneException;
import com.tinyurl.service.UrlService;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class RedirectController {

    private final UrlService urlService;
    private final AppProperties appProperties;

    public RedirectController(UrlService urlService, AppProperties appProperties) {
        this.urlService = urlService;
        this.appProperties = appProperties;
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(
        @PathVariable
        @Size(min = 6, max = 8, message = "INVALID_URL")
        @Pattern(regexp = "^[0-9A-Za-z]+$", message = "INVALID_URL")
        String shortCode
    ) {
        String notFoundUrl = appProperties.frontendUrl() + "/not-found";

        Optional<UrlMapping> mapping;
        try {
            mapping = urlService.resolveCode(shortCode);
        } catch (GoneException e) {
            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(URI.create(notFoundUrl));
            return new ResponseEntity<>(headers, HttpStatus.FOUND);
        }

        if (mapping.isEmpty()) {
            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(URI.create(notFoundUrl));
            return new ResponseEntity<>(headers, HttpStatus.FOUND);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(mapping.get().originalUrl()));
        HttpStatus status = mapping.get().explicitExpiry() ? HttpStatus.FOUND : HttpStatus.MOVED_PERMANENTLY;
        return new ResponseEntity<>(headers, status);
    }
}
