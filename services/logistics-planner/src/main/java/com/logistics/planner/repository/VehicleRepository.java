package com.logistics.planner.repository;

import com.logistics.planner.domain.Vehicle;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    List<Vehicle> findAllByActiveTrue();
}
