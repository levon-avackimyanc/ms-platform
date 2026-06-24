package com.logistics.planner.domain;

import com.logistics.planner.domain.enums.PlanStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "plans")
@Getter
@Setter
public class Plan {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PlanStatus status;

    private LocalDate planDate;

    private LocalDateTime generatedAt;

    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Route> routes = new ArrayList<>();

    @ManyToMany
    @JoinTable(
            name = "plan_unassigned_orders",
            joinColumns = @JoinColumn(name = "plan_id"),
            inverseJoinColumns = @JoinColumn(name = "order_id"))
    private List<Order> unassignedOrders = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = LocalDateTime.now();
    }
}
