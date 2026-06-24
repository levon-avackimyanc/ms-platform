package com.logistics.planner.repository;

import com.logistics.planner.domain.Order;
import com.logistics.planner.domain.enums.OrderStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByPlanDateAndStatusIn(LocalDate planDate, List<OrderStatus> statuses);
}
