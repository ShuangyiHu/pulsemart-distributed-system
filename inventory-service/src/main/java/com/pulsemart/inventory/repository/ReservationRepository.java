package com.pulsemart.inventory.repository;

import com.pulsemart.inventory.domain.Reservation;
import com.pulsemart.inventory.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findByOrderIdAndStatus(UUID orderId, ReservationStatus status);
}
