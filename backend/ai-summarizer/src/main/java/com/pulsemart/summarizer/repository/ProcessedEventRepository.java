package com.pulsemart.summarizer.repository;

import com.pulsemart.summarizer.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    @Modifying
    @Query(value = "INSERT INTO processed_events (event_id, processed_at) " +
            "VALUES (:eventId, now()) ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfAbsent(UUID eventId);
}
