# Plan: logistics-planner-vrp-service

## Task Description

Create an independent microservice `logistics-planner` on Java 24 + Spring Boot 4 + Maven, solving the vehicle routing optimization problem (CVRPTW — Capacitated VRP with Time Windows). The service provides:
- CRUD reference book of delivery points (Location)
- Order (Order) and vehicle (Vehicle) management
- Route optimizer based on Google OR-Tools
- Integration with an external Maps API for the travel time matrix
- REST API with JSON contract per the increment specification
- Support for manual plan editing and re-optimization during the day

The service is built from scratch as a standalone Maven project in `services/logistics-planner/`.

## Objective

Upon completion of the plan: a deployable Spring Boot microservice that accepts orders and vehicles, builds an optimal plan in ≤ 30 seconds for 500 orders / 30 vehicles, returns a JSON plan per AC #5 from the increment, with correct HTTP codes and support for all business operations (Location CRUD, manual editing, rebuild with freeze).

## Problem Statement

A dispatcher needs a tool that automatically distributes 50–500 orders across 5–30 vehicles, accounting for delivery time windows, cargo capacity, driver working hours, and delivery point working hours — optimizing total distance while maximizing order coverage.

## Solution Approach

**VRP solver:** Google OR-Tools `RoutingModel` (CVRPTW). Input: travel time matrix (from Maps API with Caffeine caching), pickup/delivery nodes for each order, capacity and time window constraints. Settings: `PARALLEL_CHEAPEST_INSERTION` + `GUIDED_LOCAL_SEARCH`, time limit 28 s. Unassigned orders — dropped nodes with penalty.

**Travel time matrix:** unique Locations from the reference book → batch requests to Maps API → Caffeine cache with TTL = 1 day. For 500 orders referencing a catalog of N unique points, matrix NxN (typically N << 500).

**Async plan:** `POST /plans` returns 202 + `plan_id`; optimizer starts in Spring `@Async` pool; client polls `GET /plans/{id}` until `status != draft`.

**Manual editing:** `POST /plans/{planId}/moves` moves an order between vehicles, greedily recalculates arrival times via the cached matrix, validates capacity.

**Freeze:** `PATCH /plans/{planId}/routes/{vehicleId}/freeze` → `frozen=true`; during rebuild such routes are excluded from the optimizer task.

## Relevant Files

### New Files

- `services/logistics-planner/.gitignore`
- `services/logistics-planner/build.gradle.kts`
- `services/logistics-planner/settings.gradle.kts`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/LogisticsPlannerApplication.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/domain/Location.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/domain/Order.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/domain/Vehicle.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/domain/Plan.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/domain/Route.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/domain/Stop.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/domain/enums.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/repository/LocationRepository.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/repository/OrderRepository.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/repository/VehicleRepository.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/repository/PlanRepository.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/repository/RouteRepository.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/repository/StopRepository.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/service/LocationService.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/service/OrderService.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/service/VehicleService.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/service/PlanningService.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/api/LocationController.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/api/OrderController.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/api/VehicleController.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/api/PlanController.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/api/dto/`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/api/GlobalExceptionHandler.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/maps/TravelTimeProvider.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/maps/GoogleMapsTravelTimeProvider.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/maps/TimeMatrixBuilder.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/optimizer/VrpSolver.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/optimizer/VrpInputBuilder.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/optimizer/VrpOutputMapper.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/config/CacheConfig.kt`
- `services/logistics-planner/src/main/kotlin/com/logistics/planner/config/AsyncConfig.kt`
- `services/logistics-planner/src/main/resources/application.yml`
- `services/logistics-planner/src/main/resources/db/migration/V1__initial_schema.sql`
- `services/logistics-planner/src/test/kotlin/com/logistics/planner/service/LocationServiceTest.kt`
- `services/logistics-planner/src/test/kotlin/com/logistics/planner/service/OrderServiceTest.kt`
- `services/logistics-planner/src/test/kotlin/com/logistics/planner/service/VehicleServiceTest.kt`
- `services/logistics-planner/src/test/kotlin/com/logistics/planner/service/PlanningServiceTest.kt`
- `services/logistics-planner/src/test/kotlin/com/logistics/planner/optimizer/VrpSolverTest.kt`
- `services/logistics-planner/src/test/kotlin/com/logistics/planner/maps/TimeMatrixBuilderTest.kt`
- `services/logistics-planner/src/test/resources/application-test.yml`

## Implementation Phases

### Phase 1: Foundation
Project setup (Gradle Kotlin DSL, dependencies, Flyway schema), domain model (JPA entities), CRUD for Location / Order / Vehicle with REST API and global error handling.

### Phase 2: Core Implementation
Maps API integration via a pluggable interface, travel time matrix construction with caching, OR-Tools VRP solver wrapper (CVRPTW: time dimension + 2×capacity dimension + dropped nodes).

### Phase 3: Integration & Polish
PlanningService (orchestrates phases: input assembly → solver → plan assembly → transfer of unassigned orders), Plans REST API (POST /plans async, GET /plans/{id}, confirmation, rebuild, manual editing, route freeze).

## Team Orchestration

- Operation as team lead: create tasks via TaskCreate, assign via TaskUpdate, deploy agents via Agent, track progress via TaskList + Read(output_file).
- NEVER write code directly — only orchestrate.

### Team Members

- Developer
  - Name: foundation-dev
  - Role: Scaffold, domain model, JPA repositories, CRUD services and REST controllers for Location / Order / Vehicle, global ExceptionHandler, Flyway migration
  - Agent Type: developer
  - Resume: true

- Developer
  - Name: optimizer-dev
  - Role: Maps API client, TimeMatrixBuilder + cache, VrpSolver (OR-Tools), PlanningService, Plan REST API (POST /plans, GET, confirm, rebuild, freeze, moves)
  - Agent Type: developer
  - Resume: true

- Unit-Tester
  - Name: unit-tester-main
  - Role: Unit tests for all services and the optimizer
  - Agent Type: unit-tester
  - Resume: true

- Code-Reviewer
  - Name: reviewer-main
  - Role: Semantic review of the entire diff — correctness, AC alignment, quality
  - Agent Type: code-reviewer
  - Resume: true

- Validator
  - Name: validator-main
  - Role: Final validation: compilation, unit test run, AC verification
  - Agent Type: validator
  - Resume: true

## Testing Strategy

**Dev scope writes unit tests only.** Integration and E2E coverage are owned by Test scope (a separate phase) and are not planned here.

### Unit Tests

**LocationServiceTest**
- `createLocation_validInput_returnsCreatedLocationDto` — create a location with all fields
- `createLocation_duplicateCode_throwsConflictException` — verify code uniqueness
- `getLocation_unknownId_throwsNotFoundException` — 404 for a non-existent ID
- `updateLocation_validInput_updatesFields` — update working hours and service time
- `deactivateLocation_active_setsActiveFalse` — deactivate a location

**OrderServiceTest**
- `createOrder_validLocationRefs_savesAndReturnsOrder` — happy path for order creation
- `createOrder_unknownPickupLocation_throwsNotFoundException` — validate references to the location catalog
- `createOrder_deliveryWindowInverted_throwsValidationException` — window start > end
- `getOrdersByDate_returnsPendingOrders` — query by plan date
- `markUnassigned_setsStatusUnassignedNextDay` — move order to the next day

**VehicleServiceTest**
- `createVehicle_validData_returnsSavedVehicle` — happy path
- `createVehicle_unknownDepot_throwsNotFoundException` — reference to a non-existent location
- `listActiveVehicles_returnsOnlyActive` — filter by active=true

**PlanningServiceTest**
- `buildPlan_withOrdersAndVehicles_returnsDraftPlanWithRoutes` — main happy path (VrpSolver mocked)
- `buildPlan_noOrders_returnsDraftPlanWithEmptyRoutes` — plan with no orders
- `buildPlan_allOrdersDropped_allUnassigned` — all orders unassigned (solver returned dropped)
- `rebuildPlan_withFrozenRoutes_frozenRoutesUnchanged` — rebuild does not change frozen routes
- `confirmPlan_draftPlan_changesStatusToConfirmed` — status change
- `movePlan_validMove_recalculatesArrivalTimes` — manual route editing (both stops moved, times recalculated)
- `movePlan_capacityExceeded_throwsCapacityViolationException` — 422 on vehicle overload
- `movePlan_timeWindowViolated_throwsTimeWindowViolationException` — 422 on time window violation
- `rebuildPlan_frozenRoutesAreCopiedToNewPlan` — frozen Routes and Stops are present in the new plan

**VrpSolverTest**
- `solve_singleOrderSingleVehicle_assignsOrderToRoute` — 1 order, 1 vehicle → both stops in the route
- `solve_vehicleOvercapacity_orderDropped` — overload → order dropped
- `solve_timeWindowViolation_orderDropped` — TW violation → dropped
- `solve_multipleOrders_respectsCapacityConstraints` — multiple orders do not exceed capacity

**TimeMatrixBuilderTest**
- `buildMatrix_callsProviderForEachPair` — API is called for unique pairs
- `buildMatrix_cacheHit_skipsApiCall` — repeated call reads the cache
- `buildMatrix_providerError_propagatesException` — API error is propagated

### Deferred to Test scope

Integration: `POST /plans` → polling `GET /plans/{id}` → confirm cycle against real PostgreSQL + mock Maps API. E2E: dispatcher UI flow (plan → edit → confirm).

## Test Infrastructure (User-Declared)

### Unit Layer (Java/Kotlin JVM)
- **Status:** Active
- **Files glob:** `services/logistics-planner/src/test/java/**/*Test.java`
- **Infra signature (regex, optional for unit):** `@ExtendWith\(MockitoExtension`
- **Happy-path scenarios (≥1 named):**
  - `LocationServiceTest#createLocation_validInput_returnsCreatedLocationDto`
  - `OrderServiceTest#createOrder_validLocationRefs_savesAndReturnsOrder`
  - `VehicleServiceTest#createVehicle_validData_returnsSavedVehicle`
  - `PlanningServiceTest#buildPlan_withOrdersAndVehicles_returnsDraftPlanWithRoutes`
  - `PlanningServiceTest#rebuildPlan_frozenRoutesAreCopiedToNewPlan`
  - `PlanningServiceTest#movePlan_capacityExceeded_throwsCapacityViolationException`
  - `PlanningServiceTest#movePlan_timeWindowViolated_throwsTimeWindowViolationException`
  - `VrpSolverTest#solve_singleOrderSingleVehicle_assignsOrderToRoute`
  - `TimeMatrixBuilderTest#buildMatrix_cacheHit_skipsApiCall`
- **Runner command:** `cd services/logistics-planner && mvn test`
- **Realism rationale:** Pure unit tests with Mockito stubs for repositories and external dependencies; H2 is not needed — domain logic does not require a database.

### Integration Layer (Java/Kotlin JVM)
- **Status:** Skipped — owned by Test scope

### E2E Layer (Web/UI)
- **Status:** Skipped — owned by Test scope

## Step by Step Tasks

### 1. Project Scaffold
- **Task ID**: scaffold
- **Depends On**: none
- **Assigned To**: foundation-dev
- **Agent Type**: developer
- **Stack**: java spring boot entity jpa lombok
- **Parallel**: false
- **Tests**: No unit tests for configuration files; compilation correctness is verified by the validator.
- Create `services/logistics-planner/.gitignore` — cover `.gradle/`, `build/`, `*.class`, `.kotlin/`, `*.jar`
- Create `services/logistics-planner/settings.gradle.kts` — `rootProject.name = "logistics-planner"`
- Create `services/logistics-planner/build.gradle.kts` with dependencies:
  - `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-cache`, `spring-boot-starter-validation`
  - `spring-boot-starter-webflux` (RestClient / WebClient for Maps API)
  - `jackson-module-kotlin`, `kotlin-reflect`, `kotlin-stdlib`
  - `com.google.ortools:ortools-java:9.10.4067` (includes native libraries via JNI)
  - `org.flywaydb:flyway-core`, `org.postgresql:postgresql`
  - `com.github.ben-manes.caffeine:caffeine`
  - `springdoc-openapi-starter-webmvc-ui:2.5.0`
  - test: `spring-boot-starter-test`, `io.mockk:mockk:1.13.12`, `com.h2database:h2`
  - Configure the `kotlin-spring` plugin and Kotlin JVM target = 17
- Create `LogisticsPlannerApplication.kt` with `@SpringBootApplication`
- Create `src/main/resources/application.yml`: port=8080, datasource (PostgreSQL), cache, async thread pool, maps.api.key placeholder; **mandatory** add `spring.jackson.property-naming-strategy: SNAKE_CASE` — all REST responses are serialized in snake_case per AC #5
- Create `src/test/resources/application-test.yml`: datasource H2 in-memory, flyway enabled, `maps.provider=stub`
- Create `src/main/resources/db/migration/V1__initial_schema.sql` with tables: `locations`, `vehicles`, `orders`, `plans`, `routes`, `stops`, `plan_unassigned_orders`
  - `locations(id UUID PK, code VARCHAR UNIQUE, address TEXT, latitude DOUBLE, longitude DOUBLE, working_hours_start TIME, working_hours_end TIME, service_time_minutes INT, active BOOL, created_at TIMESTAMP, updated_at TIMESTAMP)`
  - `vehicles(id UUID PK, max_weight_kg DOUBLE, max_volume_m3 DOUBLE, depot_location_id UUID FK locations, shift_start TIME, shift_end TIME, active BOOL)`
  - `orders(id UUID PK, pickup_location_id UUID FK, delivery_location_id UUID FK, delivery_window_start TIMESTAMP, delivery_window_end TIMESTAMP, weight_kg DOUBLE, volume_m3 DOUBLE, delay_buffer_percent DOUBLE DEFAULT 0, plan_date DATE, status VARCHAR(50), created_at TIMESTAMP)`
  - `plans(id UUID PK, status VARCHAR(50), plan_date DATE, generated_at TIMESTAMP, created_at TIMESTAMP)`
  - `routes(id UUID PK, plan_id UUID FK plans, vehicle_id UUID FK vehicles, frozen BOOL DEFAULT FALSE, position INT)`
  - `stops(id UUID PK, route_id UUID FK routes, location_id UUID FK locations, order_id UUID FK orders nullable, position INT, action VARCHAR(20), arrival_time TIMESTAMP, departure_time TIMESTAMP)`
  - `plan_unassigned_orders(plan_id UUID FK, order_id UUID FK, PRIMARY KEY(plan_id, order_id))`
- Create `src/main/kotlin/.../config/AsyncConfig.kt` — `@EnableAsync` + `ThreadPoolTaskExecutor` with name `"planningExecutor"` (corePoolSize=4, maxPoolSize=10, queue=50)
- Create `src/main/kotlin/.../config/CacheConfig.kt` — `@EnableCaching` + Caffeine cache `"travelTimes"` with TTL 24 h and max 10_000 entries

### 2. Domain Model & CRUD
- **Task ID**: domain-and-crud
- **Depends On**: scaffold
- **Assigned To**: foundation-dev
- **Agent Type**: developer
- **Stack**: java spring controller entity jpa lombok exception error handling controlleradvice 404 400 500 record
- **Parallel**: false
- **Tests**: Unit: LocationServiceTest (5 scenarios), OrderServiceTest (5 scenarios), VehicleServiceTest (3 scenarios) — all mocked via MockK.
- Create JPA entities (use `@Entity`, `@Table`, `@Column`, `@ManyToOne`, `@OneToMany`):
  - `Location.kt` — fields from schema; `@Column(unique=true)` on code; audit via `@CreationTimestamp` / `@UpdateTimestamp`
  - `Order.kt` — fields from schema; enum `OrderStatus { PENDING, ASSIGNED, UNASSIGNED_NEXT_DAY }` stored as String
  - `Vehicle.kt` — fields from schema; `@ManyToOne` on `depot: Location`
  - `Plan.kt` — fields from schema; `@OneToMany(mappedBy="plan")` on `routes`; enum `PlanStatus { BUILDING, DRAFT, CONFIRMED, FROZEN }` — `FROZEN` means the plan was displaced by a rebuild and its routes were transferred to the new plan; transition: `CONFIRMED → FROZEN` when `rebuildPlan` is called
  - `Route.kt` — `@ManyToOne plan`, `@ManyToOne vehicle`, `frozen: Boolean`, `@OneToMany(mappedBy="route", orderBy="position")` on `stops`
  - `Stop.kt` — `@ManyToOne route`, `@ManyToOne location`, `@ManyToOne(nullable) order`, `position: Int`, enum `StopAction { PICKUP, DELIVERY }`, `arrivalTime / departureTime: LocalDateTime?`
  - `enums.kt` — `OrderStatus`, `PlanStatus`, `StopAction`
- Create Spring Data JPA repositories: `LocationRepository`, `OrderRepository` (methods: `findByPlanDateAndStatusIn`), `VehicleRepository` (method: `findAllByActiveTrue`), `PlanRepository`, `RouteRepository`, `StopRepository`
- Create **DTO** package `api/dto/`:
  - `LocationRequest(code, address, latitude, longitude, workingHoursStart, workingHoursEnd, serviceTimeMinutes)` — annotations `@NotBlank`, `@NotNull`
  - `LocationResponse` — all fields including `id`, `active`, `createdAt`
  - `OrderRequest(pickupLocationId, deliveryLocationId, deliveryWindowStart, deliveryWindowEnd, weightKg, volumeM3, delayBufferPercent, planDate)`
  - `OrderResponse`
  - `VehicleRequest(maxWeightKg, maxVolumeM3, depotLocationId, shiftStart, shiftEnd)`
  - `VehicleResponse`
- Create **Services** (business logic without JPA knowledge in the controller):
  - `LocationService`: `create`, `getById`, `update`, `deactivate`, `listActive` — throws `LocationNotFoundException extends RuntimeException` on not found, `LocationCodeConflictException` on duplicate code
  - `OrderService`: `create` with verification of both Locations' existence, `getById`, `listByDate`, `markUnassignedNextDay(ids)`
  - `VehicleService`: `create` with depot Location verification, `getById`, `update`, `deactivate`, `listActive`
- Create **Controllers**:
  - `LocationController` — `POST /api/v1/locations` → 201, `GET /api/v1/locations/{id}` → 200, `GET /api/v1/locations` → 200, `PUT /api/v1/locations/{id}` → 200, `DELETE /api/v1/locations/{id}` → 200
  - `OrderController` — `POST /api/v1/orders` → 201, `GET /api/v1/orders/{id}` → 200, `GET /api/v1/orders?date=` → 200
  - `VehicleController` — `POST /api/v1/vehicles` → 201, `GET /api/v1/vehicles/{id}` → 200, `GET /api/v1/vehicles` → 200, `PUT /api/v1/vehicles/{id}` → 200, `DELETE /api/v1/vehicles/{id}` → 200
- Create `GlobalExceptionHandler.kt` (`@ControllerAdvice`):
  - `*NotFoundException` → 404 `{ "error": "NOT_FOUND", "message": "..." }`
  - `*ConflictException` → 409
  - `MethodArgumentNotValidException` → 400 with a list of field errors
  - Generic `Exception` → 500

### 3. Maps Integration & Cache
- **Task ID**: maps-and-cache
- **Depends On**: scaffold
- **Assigned To**: optimizer-dev
- **Agent Type**: developer
- **Stack**: java spring boot record exception error handling
- **Parallel**: true
- **Tests**: Unit: TimeMatrixBuilderTest (3 scenarios) — provider mocked.
- Create interface `TravelTimeProvider`:
  ```kotlin
  interface TravelTimeProvider {
      // Returns travel duration in seconds from (fromLat,fromLng) to (toLat,toLng)
      // at given departure hour (0-23) for traffic estimation
      fun getTravelTimeSeconds(
          fromLat: Double, fromLng: Double,
          toLat: Double, toLng: Double,
          departureHour: Int
      ): Long
  }
  ```
- Create `GoogleMapsTravelTimeProvider` — implementation via Google Distance Matrix API:
  - Use Spring `RestClient` (not WebClient, for synchronous behavior inside the batch)
  - Batch: up to 25 origins × 25 destinations per request
  - URL: `https://maps.googleapis.com/maps/api/distancematrix/json`
  - Parameters: `origins`, `destinations`, `departure_time` (unix timestamp for the target hour), `key`
  - Response mapping: parse `rows[i].elements[j].duration_in_traffic.value` (seconds)
  - Fallback on API error: Haversine × 1.4 / speed 40 km/h (to avoid blocking planning)
- Create `StubTravelTimeProvider` — for tests and local development without a Maps API key (Haversine × 1.4 / 40 km/h)
- Create `TimeMatrixBuilder`:
  - Accepts `List<Location>` (unique locations from the plan), `departureHour: Int`
  - Builds a symmetric matrix `Array<LongArray>` (N×N, seconds)
  - Cache: key `"${fromId}_${toId}_$departureHour"`, annotation `@Cacheable("travelTimes")`
  - Method `buildMatrix(locations: List<Location>, departureHour: Int): Array<LongArray>`
- Configuration: `maps.provider=google` (or `stub`) in `application.yml`; `@ConditionalOnProperty` for bean selection

### 4. VRP Solver
- **Task ID**: vrp-solver
- **Depends On**: domain-and-crud
- **Assigned To**: optimizer-dev
- **Agent Type**: developer
- **Stack**: java spring boot record
- **Parallel**: false
- **Tests**: Unit: VrpSolverTest (4 scenarios) — tests for solver logic with small input data.
- Create `VrpInput` data class:
  ```kotlin
  data class VrpInput(
      val locations: List<VrpLocation>,   // index 0 = depot (single depot for all vehicles)
      val vehicles: List<VrpVehicle>,
      val timeMatrix: Array<LongArray>,   // seconds between nodes
      val timeLimitSeconds: Long = 28L
  )
  data class VrpLocation(
      val index: Int,
      val locationId: UUID,
      val action: StopAction,            // DEPOT/PICKUP/DELIVERY
      val orderId: UUID?,
      val timeWindowStartSec: Long,      // seconds from the start of the day
      val timeWindowEndSec: Long,
      val serviceTimeSec: Long,
      val pairedIndex: Int? = null       // pickup↔delivery pair
  )
  data class VrpVehicle(
      val vehicleId: UUID,
      val depotIndex: Int,
      val maxWeightKg: Double,
      val maxVolumeM3: Double,
      val shiftStartSec: Long,
      val shiftEndSec: Long,
      val demandWeightKg: Double = 0.0,  // current weight on board during rebuild
      val demandVolumeM3: Double = 0.0
  )
  ```
- Create `VrpOutput` data class:
  ```kotlin
  data class VrpOutput(
      val routes: List<VrpRoute>,
      val droppedLocationIndices: Set<Int>
  )
  data class VrpRoute(val vehicleId: UUID, val locationIndices: List<Int>)
  ```
- Create `VrpSolver` (main class):
  - Method `solve(input: VrpInput): VrpOutput`
  - Configure OR-Tools `RoutingIndexManager(nNodes, nVehicles, depotIndex)`
  - `RoutingModel` + `RoutingSearchParameters` with `PARALLEL_CHEAPEST_INSERTION` and `GUIDED_LOCAL_SEARCH`
  - **Time dimension**: callback = `timeMatrix[from][to] + serviceTime[from]`; capacity = maxShiftDuration; cumul vars clamped by node TW; slack on depot
  - **Weight capacity dimension**: demand callback = `weightKg` of the order (pickup+, delivery-); capacity = `vehicle.maxWeightKg`
  - **Volume capacity dimension**: analogous with `volumeM3`
  - **Pickup-delivery pairs**: `routingModel.addPickupAndDelivery(pickupIdx, deliveryIdx)` for each order; same vehicle constraint
  - **Dropped nodes**: `routingModel.addDisjunction(IntArray(pickupIdx, deliveryIdx), PENALTY)` — PENALTY = 1_000_000L
  - `routingModel.solve(params)` → parse solution, build `VrpOutput`
  - If `solution == null || routingModel.status() == ROUTING_FAIL` → throw `VrpSolverException`
- Create `VrpInputBuilder` — converts `List<Order>`, `List<Vehicle>`, `List<Location>`, `timeMatrix` into `VrpInput`:
  - Builds node indices: `0 = depot`, then two nodes for each order (pickup, delivery)
  - Converts delivery window to seconds from the start of the day (relative to planDate)
  - Accounts for `delayBufferPercent` — increases `serviceTimeSec` by this percentage
- Create `VrpOutputMapper` — from `VrpOutput` + `VrpInput` builds `List<RouteAssignment>`:
  ```kotlin
  data class RouteAssignment(
      val vehicleId: UUID,
      val stops: List<StopAssignment>
  )
  data class StopAssignment(
      val locationId: UUID,
      val orderId: UUID?,
      val action: StopAction,
      val arrivalTimeSec: Long,       // seconds from the start of the day
      val departureTimeSec: Long
  )
  ```

### 5. Planning Orchestration
- **Task ID**: planning-orchestration
- **Depends On**: vrp-solver, maps-and-cache
- **Assigned To**: optimizer-dev
- **Agent Type**: developer
- **Stack**: java spring boot exception error handling record
- **Parallel**: false
- **Tests**: Unit: PlanningServiceTest (6 scenarios) — VrpSolver, TimeMatrixBuilder, repositories mocked.
- Create `PlanningService`:
  - `@Async("planningExecutor") fun buildPlanAsync(planId: UUID, planDate: LocalDate)`
    1. Load `Plan` from DB (status → BUILDING)
    2. Load `Order` with status PENDING and `planDate`
    3. Load active `Vehicle`s
    4. If frozen routes exist (rebuild case): exclude their orders from optimization, reserve the vehicles
    5. Collect unique `Location`s from orders + vehicle depots
    6. Call `TimeMatrixBuilder.buildMatrix(locations, departureHour=9)` (morning forecast)
    7. Call `VrpInputBuilder.build(orders, vehicles, locations, timeMatrix)`
    8. Call `VrpSolver.solve(input)` → `VrpOutput`
    9. Call `VrpOutputMapper.map(output, input, planDate)` → `List<RouteAssignment>`
    10. Save to DB: `Route` + `Stop` records; `Plan.status → DRAFT`
    11. Dropped nodes → `orderRepository.markUnassignedNextDay(ids)` + record in `plan_unassigned_orders`
    12. Save `Plan.generatedAt = Instant.now()`
    13. On any exception: `Plan.status → DRAFT` (empty plan), log the error
  - `fun initiatePlanBuild(planDate: LocalDate): UUID` — creates `Plan(status=BUILDING)`, calls `buildPlanAsync`, returns `planId`
  - `fun getPlan(planId: UUID): PlanResult` — reads `Plan` + `Route` + `Stop` + `unassigned_orders` from DB
  - `fun confirmPlan(planId: UUID)` — verifies `status == DRAFT`, sets `CONFIRMED`
  - `fun freezeRoute(planId: UUID, vehicleId: UUID)` — sets `route.frozen = true`
  - `fun moveOrder(planId: UUID, orderId: UUID, toVehicleId: UUID, insertAtPosition: Int): PlanResult`
    1. Load Plan, verify `status == DRAFT`; otherwise throw `PlanNotInDraftException`
    2. Find **both** Stops of the order (pickup + delivery) in the source Route → remove both; re-sort position of remaining stops
    3. Find the target Route (not frozen); insert two new Stops (pickup before delivery) starting at `insertAtPosition`; re-sort position of all stops
    4. Greedy recalculation of `arrivalTime / departureTime` for **both** affected routes (source and target) via the cached matrix: for each stop in position order: `arrivalTime = previousDepartureTime + travelTime(prev→curr)`, `departureTime = arrivalTime + serviceTime`
    5. Validate capacity of the target vehicle: total weight/volume of all orders does not exceed `maxWeightKg`/`maxVolumeM3`; violation → `CapacityViolationException` (→ 422)
    6. Validate time window of the target order: if the recalculated `arrivalTime` exceeds `deliveryWindowEnd` of the location — `TimeWindowViolationException` (→ 422 with `error: "TIME_WINDOW_VIOLATION"`)
    7. Validate shift of the target vehicle: the last stop does not exceed the driver's `shiftEnd`; violation → `ShiftViolationException` (→ 422)
    8. Save all changes to DB, return updated `PlanResult`
  - `fun rebuildPlan(planId: UUID): UUID` — mechanism: (1) load the source Plan, verify `status == CONFIRMED`; (2) collect all Routes with `frozen=true` from this plan; (3) set the source plan's `status = FROZEN`; (4) call `initiatePlanBuild(planDate)` → new Plan; (5) **copy** frozen Routes and their Stops into the new plan (full copy: new `Route`/`Stop` records with `frozen=true`, linked to the new `plan_id`); (6) exclude orders from frozen routes from the VRP task and from `plan_unassigned_orders` of the new plan; (7) return `newPlanId`

### 6. Plan REST API
- **Task ID**: plan-api
- **Depends On**: planning-orchestration, domain-and-crud
- **Assigned To**: optimizer-dev
- **Agent Type**: developer
- **Stack**: java spring controller exception error handling 404 400 500 record
- **Parallel**: false
- **Tests**: No separate unit tests for the controller (thin layer); logic is covered by PlanningServiceTest.
- Create **DTO** for the plan:
  - `PlanResponse` — corresponds to AC #5 JSON schema:
    ```kotlin
    data class PlanResponse(
        val planId: UUID,
        val status: String,         // "building"|"draft"|"confirmed"
        val routes: List<RouteResponse>,
        val unassignedOrders: List<UUID>,
        val generatedAt: Instant?
    )
    data class RouteResponse(
        val vehicleId: UUID,
        val frozen: Boolean,
        val stops: List<StopResponse>
    )
    data class StopResponse(
        val locationId: UUID,
        val orderId: UUID?,
        val arrivalTime: LocalDateTime?,
        val departureTime: LocalDateTime?,
        val action: String             // "pickup"|"delivery"
    )
    ```
  - `PlanInitRequest(planDate: LocalDate)`
  - `MoveOrderRequest(orderId: UUID, toVehicleId: UUID, insertAtPosition: Int)`
- Create `PlanController`:
  - `POST /api/v1/plans` → accepts `PlanInitRequest`, calls `planningService.initiatePlanBuild`, returns **202** + `{ "planId": "..." }`
  - `GET /api/v1/plans/{id}` → if `status == BUILDING` → **202** + current `PlanResponse`; if `status in [DRAFT, CONFIRMED]` → **200** + `PlanResponse`; if not found → **404**
  - `POST /api/v1/plans/{id}/confirm` → **200** + `PlanResponse`; if not DRAFT → **409**
  - `POST /api/v1/plans/{id}/rebuild` → **202** + `{ "newPlanId": "..." }`
  - `POST /api/v1/plans/{id}/moves` → accepts `MoveOrderRequest`; **200** + updated `PlanResponse`; on capacity violation → **422**
  - `PATCH /api/v1/plans/{planId}/routes/{vehicleId}/freeze` → **200** + `RouteResponse`
- Add to `GlobalExceptionHandler`:
  - `PlanNotInDraftException` → 409 `{ "error": "PLAN_NOT_DRAFT" }`
  - `CapacityViolationException` → 422 `{ "error": "CAPACITY_VIOLATION" }`
  - `TimeWindowViolationException` → 422 `{ "error": "TIME_WINDOW_VIOLATION" }`
  - `ShiftViolationException` → 422 `{ "error": "SHIFT_VIOLATION" }`
  - `PlanNotFoundException` → 404

### 7. Unit Tests
- **Task ID**: unit-tests
- **Depends On**: domain-and-crud, plan-api
- **Assigned To**: unit-tester-main
- **Agent Type**: unit-tester
- **Stack**: java spring boot assertj mockito test structure record
- **Parallel**: false
- Write unit tests as defined in Testing Strategy and the Unit Layer block
- Cover service logic, branches, edge cases, error paths; mock external dependencies with MockK (`every { } returns`, `verify { }`)
- Each test structure: Given / When / Then via AssertJ `assertThat` / `assertThatThrownBy`
- For `VrpSolverTest`: use real OR-Tools (no mock) with trivial input data (2 nodes + depot, 1 vehicle) — the test must run in < 3 s. The file **must also be annotated** with `@ExtendWith(MockKExtension::class)` (even without mocks) — required by the infra-signature check `check_test_layers.py`
- For `PlanningServiceTest`, `LocationServiceTest`, `OrderServiceTest`, `VehicleServiceTest`, `TimeMatrixBuilderTest`: full mock of all dependencies
- `application-test.yml` uses H2; `@SpringBootTest` is not needed for unit tests — only `@ExtendWith(MockKExtension::class)`
- NOTE: do NOT write integration/e2e tests here — those belong to Test scope

### 8. Code Review
- **Task ID**: code-review
- **Depends On**: unit-tests
- **Assigned To**: reviewer-main
- **Agent Type**: code-reviewer
- **Stack**: java spring boot controller entity jpa exception error handling assertj mockito test structure record
- **Parallel**: false
- Read-only semantic review of the developer diff (correctness, task alignment, tag/stack fit, quality, test sanity)
- Verify: HTTP codes match AC #8, response JSON schema matches AC #5, frozen routes are not changed on rebuild, capacity constraints are checked in moveOrder
- Return a structured PASS/FAIL verdict; on FAIL the developer fixes the flagged issues and review re-runs

### 9. Final Validation
- **Task ID**: validate-all
- **Depends On**: code-review
- **Assigned To**: validator-main
- **Agent Type**: validator
- **Stack**: java spring boot controller entity jpa assertj mockito test structure
- **Parallel**: false
- Go to `services/logistics-planner/`, run `./gradlew compileKotlin` — verify there are no compilation errors
- Run `./gradlew test` — verify all unit tests pass; record the number of passed tests (must be ≥ 19 — by the sum of scenarios from the Testing Strategy)
- Verify the presence of files for each AC:
  - AC #1 (SLA 30 s): `VrpSolver` accepts `timeLimitSeconds=28`
  - AC #2 (time window): `VrpInputBuilder` passes TW to the solver
  - AC #3 (capacity): both dimensions (weight + volume) configured in `VrpSolver`
  - AC #4 (unassigned list): `PlanningService.buildPlanAsync` writes dropped to `plan_unassigned_orders`
  - AC #5 (JSON schema): `PlanResponse` contains `planId`, `status`, `routes[vehicleId, stops[locationId, orderId, arrivalTime, departureTime, action]]`, `unassignedOrders`, `generatedAt`
  - AC #6 (frozen routes): `PlanningService.rebuildPlan` excludes frozen routes from optimization
  - AC #7 (CRUD attributes): `LocationService` saves all 7 attributes from LocationRequest
  - AC #8 (HTTP codes): `PlanController` returns 202 for POST /plans and pending GET, 200 for ready GET, 404 for not found
- Run `uv run --script .claude/hooks/validators/validate_plan.py --file specs/logistics-planner-vrp-service.md --team-dir .claude/agents` for the final structural check of the plan

## Acceptance Criteria

1. The service builds a plan for 500 orders and 30 vehicles in no more than 30 seconds (OR-Tools time limit 28 s + 2 s overhead)
2. All orders in the plan are delivered within the time window accounting for location working hours and the standard service time (serviceTimeMinutes + delayBufferPercent)
3. The total weight and volume of orders for each vehicle do not exceed `maxWeightKg` and `maxVolumeM3`
4. Unassigned orders appear exclusively in `unassignedOrders` and are not duplicated in routes
5. `GET /api/v1/plans/{id}` returns JSON: `{ planId, status, routes: [{ vehicleId, frozen, stops: [{ locationId, orderId, arrivalTime, departureTime, action }] }], unassignedOrders: [...], generatedAt }`
6. `POST /api/v1/plans/{id}/rebuild` does not modify routes with `frozen=true`
7. Location CRUD saves and returns: `code`, `address`, `latitude`, `longitude`, `workingHoursStart`, `workingHoursEnd`, `serviceTimeMinutes`
8. HTTP codes: `POST /api/v1/plans` → 202; `GET /api/v1/plans/{id}` → 202 (building) / 200 (ready) / 404; Location CRUD → 201/200/400/404; `POST /plans/{id}/moves` → 200 / 422 (capacity, time-window or shift violation)

## Validation Commands

```bash
# Compile
cd services/logistics-planner && ./gradlew compileKotlin

# Unit tests (must pass ≥19 tests)
cd services/logistics-planner && ./gradlew test

# Verify plan structure
cd /path/to/ms-platform && uv run --script .claude/hooks/validators/validate_plan.py \
  --file specs/logistics-planner-vrp-service.md \
  --team-dir .claude/agents
```

## Notes

**Technology choices:**
- OR-Tools `ortools-java:9.10.4067` includes native libraries for Windows/Linux/Mac via JNI bundling — no additional installation required.
- `StubTravelTimeProvider` is activated with `maps.provider=stub` (for local development without a Maps API key).
- `AsyncConfig` configures a separate thread pool `planningExecutor` — does not block the web pool during the 30-second optimization.
- H2 in tests (`application-test.yml`) + Flyway — allows running unit tests without PostgreSQL.

**Assumptions (from increment analysis):**
- `POST /plans` operates in async mode (202), since 30 s is too long for a synchronous HTTP request. AC #8 allows 200 as well, but always-202 is chosen as the primary mode; 200 can be added later if needed.
- A route becomes frozen explicitly via `PATCH /plans/{planId}/routes/{vehicleId}/freeze` — before or after plan confirmation.
- `PlanStatus.FROZEN` — internal plan lifecycle state (transition `CONFIRMED → FROZEN` on rebuild); not serialized to the client. AC #5 uses `frozen` as a route-level boolean (`route.frozen`), not as a plan status. `PlanResponse.status` returns only `"building" | "draft" | "confirmed"`. Inside the plan, routes have their own separate flag `route.frozen`.
- On `rebuild` a new `Plan` is created, the old one is transitioned to `FROZEN`. Frozen Routes and Stops are copied to the new plan.
- JSON serialization — snake_case via `spring.jackson.property-naming-strategy: SNAKE_CASE` in `application.yml` (AC #5 requires snake_case fields in the response).
- Frontend UI (mentioned in the increment) — out of scope for the Dev plan; the API is sufficient for any future UI.
