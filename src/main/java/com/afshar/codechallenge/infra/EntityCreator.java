package com.afshar.codechallenge.infra;

import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("h2 | postgres")
public class EntityCreator {

    private final EntityManager entityManager;

    public EntityCreator(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public <T> T persistAndFlush(T entity) {
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }
}
