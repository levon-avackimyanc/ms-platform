package com.logistics.planner.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "locations")
@Getter
@Setter
public class Location {

    @Id
    private UUID id;

    @Column(unique = true, nullable = false, length = 100)
    private String code;

    @Column(columnDefinition = "TEXT")
    private String address;

    private Double latitude;

    private Double longitude;

    private LocalTime workingHoursStart;

    private LocalTime workingHoursEnd;

    private Integer serviceTimeMinutes;

    @Column(nullable = false)
    private boolean active = true;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
