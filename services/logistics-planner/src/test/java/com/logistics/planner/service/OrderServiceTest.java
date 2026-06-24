package com.logistics.planner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.logistics.planner.api.dto.OrderRequest;
import com.logistics.planner.api.dto.OrderResponse;
import com.logistics.planner.domain.Location;
import com.logistics.planner.domain.Order;
import com.logistics.planner.domain.enums.OrderStatus;
import com.logistics.planner.exception.LocationNotFoundException;
import com.logistics.planner.repository.LocationRepository;
import com.logistics.planner.repository.OrderRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orderRepository;

    @Mock LocationRepository locationRepository;

    @InjectMocks OrderService orderService;

    private Location buildLocation(UUID id, String code) {
        Location loc = new Location();
        loc.setId(id);
        loc.setCode(code);
        loc.setLatitude(55.0);
        loc.setLongitude(37.0);
        loc.setServiceTimeMinutes(10);
        loc.setActive(true);
        return loc;
    }

    private Order buildSavedOrder(UUID id, Location pickup, Location delivery) {
        Order order = new Order();
        order.setId(id);
        order.setPickupLocation(pickup);
        order.setDeliveryLocation(delivery);
        order.setWeightKg(10.0);
        order.setVolumeM3(0.5);
        order.setDelayBufferPercent(0.0);
        order.setPlanDate(LocalDate.of(2026, 6, 25));
        order.setDeliveryWindowStart(LocalDateTime.of(2026, 6, 25, 9, 0));
        order.setDeliveryWindowEnd(LocalDateTime.of(2026, 6, 25, 18, 0));
        order.setStatus(OrderStatus.PENDING);
        return order;
    }

    @Test
    void createOrder_validLocationRefs_savesAndReturnsOrder() {
        UUID pickupId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Location pickup = buildLocation(pickupId, "PICKUP-A");
        Location delivery = buildLocation(deliveryId, "DELIVERY-B");
        Order savedOrder = buildSavedOrder(orderId, pickup, delivery);

        OrderRequest req =
                new OrderRequest(
                        pickupId,
                        deliveryId,
                        LocalDateTime.of(2026, 6, 25, 9, 0),
                        LocalDateTime.of(2026, 6, 25, 18, 0),
                        10.0,
                        0.5,
                        null,
                        LocalDate.of(2026, 6, 25));

        when(locationRepository.findById(pickupId)).thenReturn(Optional.of(pickup));
        when(locationRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        OrderResponse result = orderService.create(req);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(orderId);
        assertThat(result.pickupLocationId()).isEqualTo(pickupId);
        assertThat(result.deliveryLocationId()).isEqualTo(deliveryId);
        assertThat(result.weightKg()).isEqualTo(10.0);
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void createOrder_unknownPickupLocation_throwsNotFoundException() {
        UUID unknownPickupId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        OrderRequest req =
                new OrderRequest(
                        unknownPickupId,
                        deliveryId,
                        LocalDateTime.of(2026, 6, 25, 9, 0),
                        LocalDateTime.of(2026, 6, 25, 18, 0),
                        10.0,
                        0.5,
                        null,
                        LocalDate.of(2026, 6, 25));

        when(locationRepository.findById(unknownPickupId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.create(req))
                .isInstanceOf(LocationNotFoundException.class)
                .hasMessageContaining(unknownPickupId.toString());
    }

    @Test
    void createOrder_deliveryWindowInverted_throwsValidationException() {
        UUID pickupId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        // deliveryWindowStart is NOT before deliveryWindowEnd — inverted
        OrderRequest req =
                new OrderRequest(
                        pickupId,
                        deliveryId,
                        LocalDateTime.of(2026, 6, 25, 18, 0),
                        LocalDateTime.of(2026, 6, 25, 9, 0),
                        10.0,
                        0.5,
                        null,
                        LocalDate.of(2026, 6, 25));

        assertThatThrownBy(() -> orderService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deliveryWindowStart must be before deliveryWindowEnd");
    }

    @Test
    void getOrdersByDate_returnsPendingOrders() {
        LocalDate date = LocalDate.of(2026, 6, 25);
        UUID pickupId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        Location pickup = buildLocation(pickupId, "PICKUP-A");
        Location delivery = buildLocation(deliveryId, "DELIVERY-B");

        Order order1 = buildSavedOrder(UUID.randomUUID(), pickup, delivery);
        Order order2 = buildSavedOrder(UUID.randomUUID(), pickup, delivery);
        order2.setStatus(OrderStatus.ASSIGNED);

        when(orderRepository.findByPlanDateAndStatusIn(
                        eq(date), eq(List.of(OrderStatus.PENDING, OrderStatus.ASSIGNED))))
                .thenReturn(List.of(order1, order2));

        List<OrderResponse> results = orderService.listByDate(date);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).id()).isEqualTo(order1.getId());
    }

    @Test
    void markUnassigned_setsStatusUnassignedNextDay() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID pickupId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        Location pickup = buildLocation(pickupId, "PICKUP-X");
        Location delivery = buildLocation(deliveryId, "DELIVERY-Y");

        Order order1 = buildSavedOrder(id1, pickup, delivery);
        Order order2 = buildSavedOrder(id2, pickup, delivery);

        when(orderRepository.findById(id1)).thenReturn(Optional.of(order1));
        when(orderRepository.findById(id2)).thenReturn(Optional.of(order2));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.markUnassignedNextDay(List.of(id1, id2));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(2)).save(captor.capture());
        List<Order> saved = captor.getAllValues();
        assertThat(saved).allMatch(o -> o.getStatus() == OrderStatus.UNASSIGNED_NEXT_DAY);
    }
}
