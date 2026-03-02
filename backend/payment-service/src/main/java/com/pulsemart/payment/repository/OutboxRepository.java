package com.pulsemart.payment.repository;

import com.pulsemart.payment.domain.OutboxEvent;
import com.pulsemart.payment.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

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
