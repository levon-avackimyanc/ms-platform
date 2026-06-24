package com.logistics.planner.service;

import com.logistics.planner.api.dto.OrderRequest;
import com.logistics.planner.api.dto.OrderResponse;
import com.logistics.planner.domain.Location;
import com.logistics.planner.domain.Order;
import com.logistics.planner.domain.enums.OrderStatus;
import com.logistics.planner.exception.LocationNotFoundException;
import com.logistics.planner.exception.OrderNotFoundException;
import com.logistics.planner.repository.LocationRepository;
import com.logistics.planner.repository.OrderRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final LocationRepository locationRepository;

    public OrderService(OrderRepository orderRepository, LocationRepository locationRepository) {
        this.orderRepository = orderRepository;
        this.locationRepository = locationRepository;
    }

    public OrderResponse create(OrderRequest req) {
        if (req.deliveryWindowStart() != null
                && req.deliveryWindowEnd() != null
                && !req.deliveryWindowStart().isBefore(req.deliveryWindowEnd())) {
            throw new IllegalArgumentException(
                    "deliveryWindowStart must be before deliveryWindowEnd");
        }
        Location pickup =
                locationRepository
                        .findById(req.pickupLocationId())
                        .orElseThrow(() -> new LocationNotFoundException(req.pickupLocationId()));
        Location delivery =
                locationRepository
                        .findById(req.deliveryLocationId())
                        .orElseThrow(
                                () -> new LocationNotFoundException(req.deliveryLocationId()));
        Order order = new Order();
        order.setPickupLocation(pickup);
        order.setDeliveryLocation(delivery);
        order.setDeliveryWindowStart(req.deliveryWindowStart());
        order.setDeliveryWindowEnd(req.deliveryWindowEnd());
        order.setWeightKg(req.weightKg());
        order.setVolumeM3(req.volumeM3());
        order.setDelayBufferPercent(req.delayBufferPercent() != null ? req.delayBufferPercent() : 0.0);
        order.setPlanDate(req.planDate());
        return toResponse(orderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(UUID id) {
        return toResponse(
                orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id)));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listByDate(LocalDate date) {
        return orderRepository
                .findByPlanDateAndStatusIn(
                        date, List.of(OrderStatus.PENDING, OrderStatus.ASSIGNED))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public void markUnassignedNextDay(List<UUID> ids) {
        ids.forEach(
                id -> {
                    Order o =
                            orderRepository
                                    .findById(id)
                                    .orElseThrow(() -> new OrderNotFoundException(id));
                    o.setStatus(OrderStatus.UNASSIGNED_NEXT_DAY);
                    orderRepository.save(o);
                });
    }

    private OrderResponse toResponse(Order o) {
        return new OrderResponse(
                o.getId(),
                o.getPickupLocation() != null ? o.getPickupLocation().getId() : null,
                o.getDeliveryLocation() != null ? o.getDeliveryLocation().getId() : null,
                o.getDeliveryWindowStart(),
                o.getDeliveryWindowEnd(),
                o.getWeightKg(),
                o.getVolumeM3(),
                o.getDelayBufferPercent(),
                o.getPlanDate(),
                o.getStatus() != null ? o.getStatus().name() : null,
                o.getCreatedAt());
    }
}
