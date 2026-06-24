package com.logistics.planner.optimizer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class VrpOutputMapper {

    public List<RouteAssignment> map(VrpOutput output, VrpInput input, LocalDate planDate) {
        Map<Integer, VrpLocation> locByIndex =
                input.locations().stream()
                        .collect(Collectors.toMap(VrpLocation::index, l -> l));
        Map<UUID, VrpVehicle> vehById =
                input.vehicles().stream()
                        .collect(Collectors.toMap(VrpVehicle::vehicleId, v -> v));

        List<RouteAssignment> result = new ArrayList<>();
        for (VrpRoute route : output.routes()) {
            VrpVehicle veh = vehById.get(route.vehicleId());
            long cursor = veh.shiftStartSec();

            List<StopAssignment> stops = new ArrayList<>();
            for (int nodeIdx : route.locationIndices()) {
                VrpLocation loc = locByIndex.get(nodeIdx);
                long arrivalSec = cursor;
                long departureSec = arrivalSec + loc.serviceTimeSec();
                cursor = departureSec;

                stops.add(
                        new StopAssignment(
                                loc.locationId(),
                                loc.orderId(),
                                loc.action(),
                                arrivalSec,
                                departureSec));
            }
            result.add(new RouteAssignment(route.vehicleId(), stops));
        }
        return result;
    }
}
