package com.tinyurl.controller;

import com.tinyurl.dto.UrlMapping;
import com.tinyurl.exception.NotFoundException;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.tinyurl.service.UrlService;
import java.net.URI;
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

    public RedirectController(UrlService urlService) {
        this.urlService = urlService;
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(
        @PathVariable
        @Size(min = 6, max = 8, message = "INVALID_URL")
        @Pattern(regexp = "^[0-9A-Za-z]+$", message = "INVALID_URL")
        String shortCode
    ) {
        UrlMapping mapping = urlService.resolveCode(shortCode)
            .orElseThrow(() -> new NotFoundException("No URL found for this short code."));

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(mapping.originalUrl()));
        HttpStatus status = mapping.explicitExpiry() ? HttpStatus.FOUND : HttpStatus.MOVED_PERMANENTLY;
        return new ResponseEntity<>(headers, status);
    }
}
