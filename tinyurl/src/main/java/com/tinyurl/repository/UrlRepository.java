package com.tinyurl.repository;

import java.util.Optional;

public interface UrlRepository {
    long nextSequenceVal();
    UrlMappingEntity save(UrlMappingEntity entity);
    Optional<UrlMappingEntity> findByShortCode(String shortCode);
}
