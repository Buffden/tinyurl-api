package com.tinyurl.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tinyurl.config.AppProperties;
import com.tinyurl.dto.CreateUrlRequest;
import com.tinyurl.dto.UrlMapping;
import com.tinyurl.encoding.Base62Encoder;
import com.tinyurl.exception.GoneException;
import com.tinyurl.repository.UrlMappingEntity;
import com.tinyurl.repository.UrlRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UrlServiceImplTest {

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private Base62Encoder base62Encoder;

    private UrlServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UrlServiceImpl(urlRepository, base62Encoder, new AppProperties("http://localhost", 180, 6, null));
    }

    @Test
    void shortenUrlShouldRejectMalformedUrl() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> service.shortenUrl(new CreateUrlRequest("not-a-url", 30))
        );
        assertEquals("INVALID_URL", ex.getMessage());
    }

    @Test
    void shortenUrlShouldRejectNonHttpUrl() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> service.shortenUrl(new CreateUrlRequest("ftp://example.com", 30))
        );
        assertEquals("INVALID_URL", ex.getMessage());
    }

    @Test
    void shortenUrlShouldRejectZeroExpiry() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> service.shortenUrl(new CreateUrlRequest("https://example.com", 0))
        );
        assertEquals("INVALID_EXPIRY", ex.getMessage());
    }

    @Test
    void shortenUrlShouldRejectTooLargeExpiry() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> service.shortenUrl(new CreateUrlRequest("https://example.com", 3651))
        );
        assertEquals("INVALID_EXPIRY", ex.getMessage());
    }

    @Test
    void shortenUrlShouldUseDefaultExpiryAndMarkAsNonExplicit() {
        when(urlRepository.nextSequenceVal()).thenReturn(1000L);
        when(base62Encoder.encode(1000L)).thenReturn("0000Ab");
        when(urlRepository.save(any(UrlMappingEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        UrlMapping result = service.shortenUrl(new CreateUrlRequest("https://example.com/default", null));
        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);

        assertEquals("0000Ab", result.shortCode());
        assertEquals("https://example.com/default", result.originalUrl());
        assertTrue(!result.explicitExpiry());
        assertTrue(!result.expiresAt().isBefore(before.plusDays(180)));
        assertTrue(!result.expiresAt().isAfter(after.plusDays(180)));

        ArgumentCaptor<UrlMappingEntity> captor = ArgumentCaptor.forClass(UrlMappingEntity.class);
        verify(urlRepository).save(captor.capture());
        assertTrue(!captor.getValue().hasExplicitExpiry());
    }

    @Test
    void shortenUrlShouldUseProvidedExpiryAndMarkAsExplicit() {
        when(urlRepository.nextSequenceVal()).thenReturn(1001L);
        when(base62Encoder.encode(1001L)).thenReturn("0000Ac");
        when(urlRepository.save(any(UrlMappingEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        UrlMapping result = service.shortenUrl(new CreateUrlRequest("https://example.com/explicit", 30));
        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);

        assertEquals("0000Ac", result.shortCode());
        assertTrue(result.explicitExpiry());
        assertTrue(!result.expiresAt().isBefore(before.plusDays(30)));
        assertTrue(!result.expiresAt().isAfter(after.plusDays(30)));

        ArgumentCaptor<UrlMappingEntity> captor = ArgumentCaptor.forClass(UrlMappingEntity.class);
        verify(urlRepository).save(captor.capture());
        assertTrue(captor.getValue().hasExplicitExpiry());
    }

    @Test
    void resolveCodeShouldReturnEmptyWhenMissing() {
        when(urlRepository.findByShortCode("0000Ab")).thenReturn(Optional.empty());
        assertTrue(service.resolveCode("0000Ab").isEmpty());
    }

    @Test
    void resolveCodeShouldThrowGoneWhenExpired() {
        UrlMappingEntity expired = new UrlMappingEntity(
            1002L,
            "0000Ad",
            "https://example.com/expired",
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(40),
            OffsetDateTime.now(ZoneOffset.UTC).minus(1, ChronoUnit.MINUTES),
            true
        );
        when(urlRepository.findByShortCode("0000Ad")).thenReturn(Optional.of(expired));

        assertThrows(GoneException.class, () -> service.resolveCode("0000Ad"));
    }
}
