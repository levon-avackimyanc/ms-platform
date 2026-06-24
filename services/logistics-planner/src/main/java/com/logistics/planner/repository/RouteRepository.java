package com.logistics.planner.repository;

import com.logistics.planner.domain.Route;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteRepository extends JpaRepository<Route, UUID> {

    List<Route> findByPlanIdAndFrozenTrue(UUID planId);
}
