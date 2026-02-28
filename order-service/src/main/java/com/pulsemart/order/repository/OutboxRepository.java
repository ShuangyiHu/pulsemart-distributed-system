package com.pulsemart.order.repository;

import com.pulsemart.order.domain.OutboxEvent;
import com.pulsemart.order.domain.OutboxStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Fetches up to 50 PENDING outbox rows with FOR UPDATE SKIP LOCKED.
     * If multiple OutboxPublisher instances run concurrently, each will
     * lock a disjoint set of rows — no duplicate publishing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT o FROM OutboxEvent o
            WHERE o.status = 'PENDING'
            ORDER BY o.createdAt
            LIMIT 50
            """)
    List<OutboxEvent> findPendingEventsForUpdate();

    long countByStatus(OutboxStatus status);
}
