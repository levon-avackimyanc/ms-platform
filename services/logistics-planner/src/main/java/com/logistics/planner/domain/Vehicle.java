package com.logistics.planner.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "vehicles")
@Getter
@Setter
public class Vehicle {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Double maxWeightKg;

    @Column(nullable = false)
    private Double maxVolumeM3;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "depot_location_id")
    private Location depot;

    @Column(nullable = false)
    private LocalTime shiftStart;

    @Column(nullable = false)
    private LocalTime shiftEnd;

    @Column(nullable = false)
    private boolean active = true;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
