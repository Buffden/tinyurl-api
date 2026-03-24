package com.tinyurl.service;

import com.tinyurl.config.AppProperties;
import com.tinyurl.dto.CreateUrlRequest;
import com.tinyurl.dto.UrlMapping;
import com.tinyurl.encoding.Base62Encoder;
import com.tinyurl.exception.GoneException;
import com.tinyurl.repository.UrlMappingEntity;
import com.tinyurl.repository.UrlRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class UrlServiceImpl implements UrlService {

    private static final int MAX_EXPIRY_DAYS = 3650;

    private final UrlRepository urlRepository;
    private final Base62Encoder base62Encoder;
    private final AppProperties appProperties;

    public UrlServiceImpl(UrlRepository urlRepository, Base62Encoder base62Encoder, AppProperties appProperties) {
        this.urlRepository = urlRepository;
        this.base62Encoder = base62Encoder;
        this.appProperties = appProperties;
    }

    @Override
    public UrlMapping shortenUrl(CreateUrlRequest request) {
        validateUrl(request.url());
        boolean hasExplicitExpiry = request.expiresInDays() != null;
        int expiresInDays = normalizeExpiry(request.expiresInDays());

        long id = urlRepository.nextSequenceVal();
        String shortCode = base62Encoder.encode(id);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plusDays(expiresInDays);

        UrlMappingEntity entity = new UrlMappingEntity(
            id,
            shortCode,
            request.url(),
            now,
            expiresAt,
            hasExplicitExpiry
        );

        UrlMappingEntity persisted = urlRepository.save(entity);
        return toDomain(persisted);
    }

    @Override
    public Optional<UrlMapping> resolveCode(String code) {
        Optional<UrlMappingEntity> maybeEntity = urlRepository.findByShortCode(code);
        if (maybeEntity.isEmpty()) {
            return Optional.empty();
        }

        UrlMappingEntity entity = maybeEntity.get();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (entity.getExpiresAt().isBefore(now)) {
            throw new GoneException("This short URL has expired or been removed.");
        }

        return Optional.of(toDomain(entity));
    }

    private UrlMapping toDomain(UrlMappingEntity entity) {
        return new UrlMapping(
            entity.getId(),
            entity.getShortCode(),
            entity.getOriginalUrl(),
            entity.getExpiresAt(),
            entity.hasExplicitExpiry()
        );
    }

    private int normalizeExpiry(Integer expiresInDays) {
        int configuredDefault = appProperties.defaultExpiryDays() == null ? 180 : appProperties.defaultExpiryDays();
        int value = expiresInDays == null ? configuredDefault : expiresInDays;
        if (value <= 0 || value > MAX_EXPIRY_DAYS) {
            throw new IllegalArgumentException("INVALID_EXPIRY");
        }
        return value;
    }

    private void validateUrl(String rawUrl) {
        try {
            URI uri = new URI(rawUrl);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("INVALID_URL");
            }
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("INVALID_URL");
            }
        } catch (URISyntaxException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("INVALID_URL");
        }
    }
}
