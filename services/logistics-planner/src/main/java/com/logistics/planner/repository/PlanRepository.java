package com.logistics.planner.repository;

import com.logistics.planner.domain.Plan;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, UUID> {}
