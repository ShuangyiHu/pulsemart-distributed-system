package com.pulsemart.order.repository;

import com.pulsemart.order.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * INSERT ... ON CONFLICT DO NOTHING.
     * Returns 1 if the row was inserted (first time), 0 if it already existed (duplicate).
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_events(event_id, processed_at)
            VALUES (:eventId, now())
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") UUID eventId);
}
