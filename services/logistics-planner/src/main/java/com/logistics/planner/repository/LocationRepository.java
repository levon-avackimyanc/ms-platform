package com.logistics.planner.repository;

import com.logistics.planner.domain.Location;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    boolean existsByCode(String code);
}
