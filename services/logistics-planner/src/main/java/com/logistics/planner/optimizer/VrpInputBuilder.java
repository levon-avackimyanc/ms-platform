package com.logistics.planner.optimizer;

import com.logistics.planner.domain.Location;
import com.logistics.planner.domain.Order;
import com.logistics.planner.domain.Vehicle;
import com.logistics.planner.domain.enums.StopAction;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class VrpInputBuilder {

    public VrpInput build(
            List<Order> orders,
            List<Vehicle> vehicles,
            List<Location> locations,
            long[][] timeMatrix,
            LocalDate planDate,
            long timeLimitSeconds) {

        Map<UUID, Integer> locIndex = new HashMap<>();
        for (int i = 0; i < locations.size(); i++) {
            locIndex.put(locations.get(i).getId(), i);
        }

        List<VrpLocation> vrpLocations = new ArrayList<>();
        Location depot = locations.get(0);
        long dayStartSec = 0L;
        long dayEndSec = 24 * 3600L;
        vrpLocations.add(
                new VrpLocation(0, depot.getId(), null, null, dayStartSec, dayEndSec, 0, -1));

        for (Order order : orders) {
            int pickupIdx = vrpLocations.size();
            int deliveryIdx = pickupIdx + 1;

            Location pickupLoc = order.getPickupLocation();
            Location deliveryLoc = order.getDeliveryLocation();

            long twStart =
                    toSecondsSinceMidnight(order.getDeliveryWindowStart().toLocalTime());
            long twEnd = toSecondsSinceMidnight(order.getDeliveryWindowEnd().toLocalTime());

            double bufferFactor =
                    1.0
                            + (order.getDelayBufferPercent() != null
                                            ? order.getDelayBufferPercent()
                                            : 0.0)
                                    / 100.0;

            long pickupServiceSec =
                    (long)
                            ((pickupLoc.getServiceTimeMinutes() != null
                                            ? pickupLoc.getServiceTimeMinutes()
                                            : 0)
                                    * 60
                                    * bufferFactor);
            long deliveryServiceSec =
                    (long)
                            ((deliveryLoc.getServiceTimeMinutes() != null
                                            ? deliveryLoc.getServiceTimeMinutes()
                                            : 0)
                                    * 60
                                    * bufferFactor);

            long pickupTwStart =
                    pickupLoc.getWorkingHoursStart() != null
                            ? toSecondsSinceMidnight(pickupLoc.getWorkingHoursStart())
                            : dayStartSec;
            long pickupTwEnd =
                    pickupLoc.getWorkingHoursEnd() != null
                            ? toSecondsSinceMidnight(pickupLoc.getWorkingHoursEnd())
                            : dayEndSec;

            vrpLocations.add(
                    new VrpLocation(
                            pickupIdx,
                            pickupLoc.getId(),
                            StopAction.PICKUP,
                            order.getId(),
                            pickupTwStart,
                            pickupTwEnd,
                            pickupServiceSec,
                            deliveryIdx));
            vrpLocations.add(
                    new VrpLocation(
                            deliveryIdx,
                            deliveryLoc.getId(),
                            StopAction.DELIVERY,
                            order.getId(),
                            twStart,
                            twEnd,
                            deliveryServiceSec,
                            pickupIdx));
        }

        List<VrpVehicle> vrpVehicles = new ArrayList<>();
        for (Vehicle v : vehicles) {
            long shiftStart = toSecondsSinceMidnight(v.getShiftStart());
            long shiftEnd = toSecondsSinceMidnight(v.getShiftEnd());
            int depotIdx = locIndex.getOrDefault(v.getDepot().getId(), 0);
            vrpVehicles.add(
                    new VrpVehicle(
                            v.getId(),
                            depotIdx,
                            v.getMaxWeightKg(),
                            v.getMaxVolumeM3(),
                            shiftStart,
                            shiftEnd));
        }

        return new VrpInput(vrpLocations, vrpVehicles, timeMatrix, timeLimitSeconds);
    }

    private long toSecondsSinceMidnight(LocalTime t) {
        return t.toSecondOfDay();
    }
}
