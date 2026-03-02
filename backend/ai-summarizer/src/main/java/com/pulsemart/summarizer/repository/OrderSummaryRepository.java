package com.pulsemart.summarizer.repository;

import com.pulsemart.summarizer.domain.OrderSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderSummaryRepository extends JpaRepository<OrderSummary, UUID> {

    Optional<OrderSummary> findByOrderId(UUID orderId);

    boolean existsByOrderId(UUID orderId);
}
