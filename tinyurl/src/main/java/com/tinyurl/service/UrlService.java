package com.tinyurl.service;

import com.tinyurl.dto.CreateUrlRequest;
import com.tinyurl.dto.UrlMapping;
import java.util.Optional;

public interface UrlService {
    UrlMapping shortenUrl(CreateUrlRequest request);
    Optional<UrlMapping> resolveCode(String code);
}
