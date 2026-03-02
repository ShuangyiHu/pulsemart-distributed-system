package com.pulsemart.order.repository;

import com.pulsemart.order.domain.OutboxEvent;
import com.pulsemart.order.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Fetches up to 50 PENDING outbox rows with FOR UPDATE SKIP LOCKED.
     * Native query is required because:
     *   1. JPQL does not support the LIMIT keyword.
     *   2. FOR UPDATE SKIP LOCKED must be written as literal SQL;
     *      Hibernate's JPQL lock hints do not reliably emit SKIP LOCKED
     *      across all versions.
     * If multiple OutboxPublisher instances run concurrently, each will
     * lock a disjoint set of rows — no duplicate publishing.
     */
    @Query(value = """
            SELECT * FROM outbox
            WHERE status = 'PENDING'
            ORDER BY created_at
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingEventsForUpdate();

    long countByStatus(OutboxStatus status);
}
