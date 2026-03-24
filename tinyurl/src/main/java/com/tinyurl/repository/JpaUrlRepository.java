package com.tinyurl.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaUrlRepository implements UrlRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public long nextSequenceVal() {
        Object value = entityManager.createNativeQuery("SELECT nextval('url_seq')").getSingleResult();
        return ((Number) value).longValue();
    }

    @Override
    @Transactional
    public UrlMappingEntity save(UrlMappingEntity entity) {
        entityManager.persist(entity);
        return entity;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UrlMappingEntity> findByShortCode(String shortCode) {
        return entityManager.createQuery(
                "SELECT u FROM UrlMappingEntity u WHERE u.shortCode = :shortCode",
                UrlMappingEntity.class
            )
            .setParameter("shortCode", shortCode)
            .getResultStream()
            .findFirst();
    }
}
