package com.logistics.planner.repository;

import com.logistics.planner.domain.Stop;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StopRepository extends JpaRepository<Stop, UUID> {

    List<Stop> findByRouteIdOrderByPosition(UUID routeId);
}
