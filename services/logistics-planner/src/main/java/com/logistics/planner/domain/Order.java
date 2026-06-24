package com.logistics.planner.domain;

import com.logistics.planner.domain.enums.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "orders")
@Getter
@Setter
public class Order {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pickup_location_id")
    private Location pickupLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_location_id")
    private Location deliveryLocation;

    private LocalDateTime deliveryWindowStart;

    private LocalDateTime deliveryWindowEnd;

    @Column(nullable = false)
    private Double weightKg;

    @Column(nullable = false)
    private Double volumeM3;

    private Double delayBufferPercent = 0.0;

    private LocalDate planDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private OrderStatus status;

    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = OrderStatus.PENDING;
        }
    }
}
