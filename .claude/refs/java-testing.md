# Java Testing Standards

<!-- section:philosophy -->

## Testing Philosophy

```
┌─────────────────────────────────────────────────────────────────┐
│           REAL INTEGRATION TESTS                               │
│         (HTTP, Kafka, JDBC with Testcontainers)                 │
│                                                                 │
│    → Core basket of basic scenarios                            │
│    → Maximum stability in agentic development                  │
│    → Catch REAL bugs                                           │
└─────────────────────────────────────────────────────────────────┘
                            +
┌─────────────────────────────────────────────────────────────────┐
│              UNIT TESTS WITH MOCKS                              │
│            (Mockito, edge cases)                                │
│                                                                 │
│    → Push coverage to 80/80 JaCoCo                             │
│    → Edge cases that are hard to reproduce otherwise           │
│    → Fast feedback loop                                        │
└─────────────────────────────────────────────────────────────────┘
```

## Integration Test Writing Rules

### 1. Research Real API Usage

For **medium/hard** functionality — **understand how the API will be used**:

```
Typical code (CRUD, REST)?
  → Use standard test baskets (see below)

Atypical code (integrations, complex logic)?
  → Context7: find library documentation and test examples
  → Understand real usage scenarios
```

**Example: Kafka integration**
```bash
# Atypical code → find how to properly test in Context7
mcp__context7__resolve-library-id(libraryName="spring-kafka", query="testing")
mcp__context7__query-docs(libraryId="...", query="integration test consumer producer")
```

**Goal:** Create optimal test scenarios that reflect real usage, not just cover code.

### 2. Scenario Priority

```
┌─────────────────────────────────────────────────────────────────┐
│  1. POSITIVE SCENARIOS (first!)                                 │
│     → Happy path: valid request → successful response           │
│     → Core business flow works                                 │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│  2. CRITICAL NEGATIVES                                          │
│     → 404: resource not found                                   │
│     → 400: invalid data (empty ID, null)                       │
│     → 409: business logic conflict                              │
│     → 401/403: unauthorized/forbidden (if auth is present)     │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│  3. EDGE CASES (unit tests with mocks)                         │
│     → Boundary values                                           │
│     → Concurrent updates                                        │
│     → Retry logic                                              │
└─────────────────────────────────────────────────────────────────┘
```

### 3. DO NOT Write in Integration Tests

```java
// ❌ DO NOT write performance benchmarks
@Test
void createOrder_performanceTest() {
    long start = System.currentTimeMillis();
    for (int i = 0; i < 1000; i++) {
        orderService.createOrder(request);
    }
    long duration = System.currentTimeMillis() - start;
    assertThat(duration).isLessThan(5000);  // ❌ Flaky!
}

// ❌ DO NOT test throughput
@Test
void api_shouldHandle100RequestsPerSecond() { ... }  // ❌ Use JMeter/Gatling for this

// ✅ Test functionality, not speed
@Test
void createOrder_withValidRequest_returns201() {
    // given
    final CreateOrderRequest request = createValidRequest();

    // when
    final ResponseEntity<Order> response = restTemplate.postForEntity(
        "/api/orders", request, Order.class);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
}
```

<!-- /section:philosophy -->

---

<!-- section:structure -->

# Part 1: Basic Patterns

## 1. Naming Convention

```java
// Format: method_condition_expectedResult
@Test
void createOrder_withValidItems_returnsOrderWithCorrectTotal() { ... }

@Test
void createOrder_withEmptyItems_throwsIllegalArgumentException() { ... }

@Test
void findById_whenOrderNotFound_throwsNotFoundException() { ... }
```

## 2. Given-When-Then Structure

Comments `// given`, `// when`, `// then` **are required**.

```java
@Test
void createOrder_withValidItems_calculatesCorrectTotal() {
    // given
    final CreateOrderRequest request = createValidRequest();

    // when
    final Order result = orderService.createOrder(request);

    // then
    assertThat(result.getTotal()).isEqualByComparingTo(BigDecimal.valueOf(300));
}
```

## 3. AssertJ — Fluent Assertions

```java
// Collections
assertThat(orders)
    .hasSize(2)
    .extracting(Order::getStatus)
    .containsExactly(OrderStatus.CREATED, OrderStatus.SHIPPED);

// Exceptions
assertThatThrownBy(() -> service.findById(null))
    .isInstanceOf(IllegalArgumentException.class)
    .hasMessageContaining("id cannot be blank");

// Objects
assertThat(result)
    .isNotNull()
    .satisfies(order -> {
        assertThat(order.getId()).isNotBlank();
        assertThat(order.getTotal()).isPositive();
    });
```

## 4. Allure Annotations

```java
@Epic("Orders")
@Feature("Order Creation")
class OrderServiceIntegrationTest {

    @Test
    @Story("Successful Creation")
    @Description("Verifies order creation with valid data and total amount calculation")
    @Severity(SeverityLevel.CRITICAL)
    void createOrder_withValidItems_success() {
        // ...
    }

    @Test
    @Story("Validation")
    @Description("Verifies order rejection without items")
    @Severity(SeverityLevel.NORMAL)
    void createOrder_withEmptyItems_throwsException() {
        // ...
    }
}
```

### Allure @Step for Readable Reports

```java
@Test
@Story("Full Order Lifecycle")
void orderLifecycle_fromCreationToDelivery() {
    // given
    final String orderId = createOrder();

    // when
    payForOrder(orderId);
    shipOrder(orderId);
    deliverOrder(orderId);

    // then
    assertOrderStatus(orderId, OrderStatus.DELIVERED);
}

@Step("Create order")
private String createOrder() {
    final CreateOrderRequest request = createValidRequest();
    final Order order = orderService.createOrder(request);
    return order.getId();
}

@Step("Pay for order {orderId}")
private void payForOrder(final String orderId) {
    paymentService.processPayment(orderId);
}

@Step("Ship order {orderId}")
private void shipOrder(final String orderId) {
    shippingService.ship(orderId);
}

@Step("Deliver order {orderId}")
private void deliverOrder(final String orderId) {
    deliveryService.deliver(orderId);
}

@Step("Verify order status {orderId} = {expectedStatus}")
private void assertOrderStatus(final String orderId, final OrderStatus expectedStatus) {
    final Order order = orderService.findById(orderId);
    assertThat(order.getStatus()).isEqualTo(expectedStatus);
}
```

## 5. Nested Test Classes

Grouping tests by scenario. Improves readability in Allure.

```java
@Epic("Orders")
@Feature("OrderService")
class OrderServiceIntegrationTest extends BaseIntegrationTest {

    @Nested
    @DisplayName("createOrder()")
    class CreateOrder {

        @Test
        @Story("Successful Creation")
        void withValidItems_returnsOrderWithCorrectTotal() {
            // given
            final CreateOrderRequest request = createValidRequest();

            // when
            final Order result = orderService.createOrder(request);

            // then
            assertThat(result.getId()).isNotBlank();
            assertThat(result.getTotal()).isEqualByComparingTo(BigDecimal.valueOf(300));
        }

        @Test
        @Story("Validation")
        void withEmptyItems_throwsIllegalArgumentException() {
            // given
            final CreateOrderRequest request = CreateOrderRequest.builder()
                .customerId("customer-1")
                .items(List.of())
                .build();

            // when & then
            assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @Story("Validation")
        void withNullCustomerId_throwsIllegalArgumentException() {
            // given
            final CreateOrderRequest request = CreateOrderRequest.builder()
                .customerId(null)
                .items(List.of(createValidItem()))
                .build();

            // when & then
            assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customerId");
        }
    }

    @Nested
    @DisplayName("findById()")
    class FindById {

        @Test
        @Story("Successful Search")
        void whenExists_returnsOrder() {
            // given
            final Order created = orderService.createOrder(createValidRequest());

            // when
            final Order found = orderService.findById(created.getId());

            // then
            assertThat(found.getId()).isEqualTo(created.getId());
        }

        @Test
        @Story("Error Handling")
        void whenNotFound_throwsNotFoundException() {
            // when & then
            assertThatThrownBy(() -> orderService.findById("non-existent"))
                .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("cancel()")
    class Cancel {

        @Test
        @Story("Successful Cancellation")
        void whenCreated_cancelsSuccessfully() {
            // given
            final Order order = orderService.createOrder(createValidRequest());

            // when
            final Order cancelled = orderService.cancel(order.getId());

            // then
            assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @Story("Business Rules")
        void whenShipped_throwsConflictException() {
            // given
            final Order order = createShippedOrder();

            // when & then
            assertThatThrownBy(() -> orderService.cancel(order.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("shipped");
        }
    }
}
```

<!-- /section:structure -->

---

<!-- section:integration -->

# Part 2: Real Integration Tests

## 6. Podman + Testcontainers

**Use Podman instead of Docker.** Configuration for different OSes:

### Linux

```bash
# ~/.bashrc or ~/.zshrc
export DOCKER_HOST=unix://${XDG_RUNTIME_DIR}/podman/podman.sock
```

### MacOS

```bash
# ~/.bashrc or ~/.zshrc
export DOCKER_HOST=unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

### Rootless mode (disable Ryuk)

```bash
# Ryuk does not work in rootless mode
export TESTCONTAINERS_RYUK_DISABLED=true
```

### Verification

```bash
# Is Podman running?
podman info

# Does Testcontainers detect Podman?
mvn test -Dtest=SomeIT -X | grep -i "docker\|podman"
```

## 7. Base Integration Test Class (Testcontainers)

```java
/**
 * Base class for integration tests.
 * Starts real PostgreSQL and Kafka via Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    /** PostgreSQL container — shared across all tests. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Kafka container — shared across all tests. */
    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
    );

    /** HTTP client for testing the REST API. */
    @Autowired
    protected TestRestTemplate restTemplate;

    /** Kafka consumer for verifying sent messages. */
    @Autowired
    protected KafkaTemplate<String, String> kafkaTemplate;

    /** Repository for preparing test data. */
    @Autowired
    protected OrderRepository orderRepository;

    @BeforeEach
    void cleanUp() {
        orderRepository.deleteAll();
    }
}
```

### application-test.yml

```yaml
spring:
  # Testcontainers will configure automatically via @ServiceConnection
  datasource:
    # Will be overridden by Testcontainers
  kafka:
    # Will be overridden by Testcontainers

  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: true

logging:
  level:
    org.springframework.test: DEBUG
    org.testcontainers: INFO
```

<!-- /section:integration -->

<!-- section:http -->

## 8. HTTP Integration Tests

```java
@Epic("REST API")
@Feature("Orders API")
class OrderControllerIntegrationTest extends BaseIntegrationTest {

    @Nested
    @DisplayName("POST /api/orders")
    class CreateOrderEndpoint {

        @Test
        @Story("Successful Creation")
        @Severity(SeverityLevel.CRITICAL)
        void withValidRequest_returns201AndOrder() {
            // given
            final CreateOrderRequest request = CreateOrderRequest.builder()
                .customerId("customer-1")
                .items(List.of(
                    new ItemDto("product-1", BigDecimal.valueOf(100)),
                    new ItemDto("product-2", BigDecimal.valueOf(200))
                ))
                .build();

            // when
            final ResponseEntity<Order> response = restTemplate.postForEntity(
                "/api/orders",
                request,
                Order.class
            );

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getId()).isNotBlank();
            assertThat(response.getBody().getTotal()).isEqualByComparingTo(BigDecimal.valueOf(300));

            // verify persisted in DB
            final Optional<Order> fromDb = orderRepository.findById(response.getBody().getId());
            assertThat(fromDb).isPresent();
        }

        @Test
        @Story("Validation")
        void withEmptyItems_returns400() {
            // given
            final CreateOrderRequest request = CreateOrderRequest.builder()
                .customerId("customer-1")
                .items(List.of())
                .build();

            // when
            final ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/api/orders",
                request,
                ErrorResponse.class
            );

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Nested
    @DisplayName("GET /api/orders/{id}")
    class GetOrderEndpoint {

        @Test
        @Story("Successful Request")
        void whenExists_returns200AndOrder() {
            // given
            final Order saved = orderRepository.save(createTestOrder());

            // when
            final ResponseEntity<Order> response = restTemplate.getForEntity(
                "/api/orders/{id}",
                Order.class,
                saved.getId()
            );

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getId()).isEqualTo(saved.getId());
        }

        @Test
        @Story("Error Handling")
        void whenNotFound_returns404() {
            // when
            final ResponseEntity<ErrorResponse> response = restTemplate.getForEntity(
                "/api/orders/{id}",
                ErrorResponse.class,
                "non-existent-id"
            );

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
```

<!-- /section:http -->

<!-- section:kafka -->

## 9. Kafka Integration Tests

```java
@Epic("Messaging")
@Feature("Order Events")
class OrderKafkaIntegrationTest extends BaseIntegrationTest {

    /** Topic for order events. */
    private static final String ORDERS_TOPIC = "orders.events";

    /** Consumer for reading messages from Kafka. */
    @Autowired
    private KafkaConsumer<String, OrderEvent> kafkaConsumer;

    @BeforeEach
    void subscribeToTopic() {
        kafkaConsumer.subscribe(List.of(ORDERS_TOPIC));
    }

    @Test
    @Story("Event Publishing")
    @Severity(SeverityLevel.CRITICAL)
    void createOrder_publishesOrderCreatedEvent() {
        // given
        final CreateOrderRequest request = createValidRequest();

        // when
        restTemplate.postForEntity("/api/orders", request, Order.class);

        // then — verify that the event was sent to Kafka
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            final ConsumerRecords<String, OrderEvent> records =
                kafkaConsumer.poll(Duration.ofMillis(100));

            assertThat(records).isNotEmpty();

            final OrderEvent event = records.iterator().next().value();
            assertThat(event.getType()).isEqualTo("ORDER_CREATED");
            assertThat(event.getOrderId()).isNotBlank();
        });
    }

    @Test
    @Story("Incoming Event Processing")
    void paymentCompletedEvent_updatesOrderStatus() {
        // given
        final Order order = orderRepository.save(createTestOrder());
        final PaymentCompletedEvent event = new PaymentCompletedEvent(order.getId());

        // when — send event to Kafka
        kafkaTemplate.send("payments.events", event.getOrderId(), toJson(event));

        // then — verify that the order was updated in the DB
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            final Order updated = orderRepository.findById(order.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.PAID);
        });
    }
}
```

<!-- /section:kafka -->

<!-- section:jdbc -->

## 10. JDBC Integration Tests

```java
@Epic("Persistence")
@Feature("OrderRepository")
class OrderRepositoryIntegrationTest extends BaseIntegrationTest {

    @Nested
    @DisplayName("Custom queries")
    class CustomQueries {

        @Test
        @Story("Search by Status")
        void findByStatus_returnsMatchingOrders() {
            // given
            orderRepository.saveAll(List.of(
                createOrderWithStatus(OrderStatus.CREATED),
                createOrderWithStatus(OrderStatus.CREATED),
                createOrderWithStatus(OrderStatus.SHIPPED)
            ));

            // when
            final List<Order> created = orderRepository.findByStatus(OrderStatus.CREATED);

            // then
            assertThat(created).hasSize(2);
            assertThat(created).allMatch(o -> o.getStatus() == OrderStatus.CREATED);
        }

        @Test
        @Story("Search by Customer")
        void findByCustomerId_returnsCustomerOrders() {
            // given
            orderRepository.saveAll(List.of(
                createOrderForCustomer("customer-1"),
                createOrderForCustomer("customer-1"),
                createOrderForCustomer("customer-2")
            ));

            // when
            final List<Order> orders = orderRepository.findByCustomerId("customer-1");

            // then
            assertThat(orders).hasSize(2);
        }

        @Test
        @Story("Aggregation")
        void calculateTotalByCustomer_returnsCorrectSum() {
            // given
            orderRepository.saveAll(List.of(
                createOrderWithTotal("customer-1", BigDecimal.valueOf(100)),
                createOrderWithTotal("customer-1", BigDecimal.valueOf(200)),
                createOrderWithTotal("customer-2", BigDecimal.valueOf(500))
            ));

            // when
            final BigDecimal total = orderRepository.calculateTotalByCustomer("customer-1");

            // then
            assertThat(total).isEqualByComparingTo(BigDecimal.valueOf(300));
        }
    }

    @Nested
    @DisplayName("Transactions")
    class Transactions {

        @Test
        @Story("Rollback on Error")
        void whenExceptionThrown_rollbacksTransaction() {
            // given
            final int initialCount = (int) orderRepository.count();

            // when & then
            assertThatThrownBy(() -> orderService.createOrderWithFailingStep(createValidRequest()))
                .isInstanceOf(RuntimeException.class);

            // verify rollback
            assertThat(orderRepository.count()).isEqualTo(initialCount);
        }
    }
}
```

<!-- /section:jdbc -->

<!-- section:wiremock -->

## 11. External API Tests (WireMock)

```java
@Epic("External Integrations")
@Feature("Payment Gateway")
@WireMockTest(httpPort = 8089)
class PaymentGatewayIntegrationTest extends BaseIntegrationTest {

    @Test
    @Story("Successful Payment")
    void processPayment_whenGatewayReturnsSuccess_updatesOrder() {
        // given
        final Order order = orderRepository.save(createTestOrder());

        stubFor(post(urlEqualTo("/api/payments"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "transactionId": "txn-123",
                        "status": "SUCCESS"
                    }
                    """)));

        // when
        final ResponseEntity<Order> response = restTemplate.postForEntity(
            "/api/orders/{id}/pay",
            null,
            Order.class,
            order.getId()
        );

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getStatus()).isEqualTo(OrderStatus.PAID);

        verify(postRequestedFor(urlEqualTo("/api/payments"))
            .withRequestBody(containing(order.getId())));
    }

    @Test
    @Story("Gateway Error")
    void processPayment_whenGatewayFails_returns502() {
        // given
        final Order order = orderRepository.save(createTestOrder());

        stubFor(post(urlEqualTo("/api/payments"))
            .willReturn(aResponse()
                .withStatus(500)
                .withBody("Internal Server Error")));

        // when
        final ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
            "/api/orders/{id}/pay",
            null,
            ErrorResponse.class,
            order.getId()
        );

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    @Story("Timeout")
    void processPayment_whenGatewayTimeout_returns504() {
        // given
        final Order order = orderRepository.save(createTestOrder());

        stubFor(post(urlEqualTo("/api/payments"))
            .willReturn(aResponse()
                .withFixedDelay(5000)  // Timeout
                .withStatus(200)));

        // when
        final ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
            "/api/orders/{id}/pay",
            null,
            ErrorResponse.class,
            order.getId()
        );

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }
}
```

<!-- /section:wiremock -->

---

<!-- section:mockito -->

# Part 3: Unit Tests with Mocks (for Coverage)

## 12. Mockito for Edge Cases

```java
/**
 * Unit tests for reaching coverage.
 * Edge cases that are hard to reproduce in integration tests.
 */
@Epic("Unit Tests")
@Feature("OrderService")
@ExtendWith(MockitoExtension.class)
class OrderServiceUnitTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private OrderService orderService;

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @Story("Concurrent modification")
        void cancel_whenConcurrentUpdate_retriesAndSucceeds() {
            // given
            final Order order = createTestOrder();
            when(orderRepository.findById(order.getId()))
                .thenReturn(Optional.of(order));
            when(orderRepository.save(any()))
                .thenThrow(new OptimisticLockingFailureException("Concurrent update"))
                .thenReturn(order.withStatus(OrderStatus.CANCELLED));

            // when
            final Order result = orderService.cancel(order.getId());

            // then
            assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            verify(orderRepository, times(2)).save(any());
        }

        @Test
        @Story("Notification failure")
        void createOrder_whenNotificationFails_stillSavesOrder() {
            // given
            final CreateOrderRequest request = createValidRequest();
            final Order savedOrder = createTestOrder();

            when(orderRepository.save(any())).thenReturn(savedOrder);
            doThrow(new RuntimeException("Email service down"))
                .when(notificationService).sendOrderCreatedNotification(any());

            // when
            final Order result = orderService.createOrder(request);

            // then — order is saved despite notification error
            assertThat(result).isNotNull();
            verify(orderRepository).save(any());
        }

        @Test
        @Story("Partial payment")
        void processPayment_whenPartiallyPaid_updatesRemainingAmount() {
            // given
            final Order order = createOrderWithTotal(BigDecimal.valueOf(1000));
            when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
            when(paymentGateway.charge(any())).thenReturn(
                new PaymentResult(BigDecimal.valueOf(500), PaymentStatus.PARTIAL)
            );

            // when
            orderService.processPayment(order.getId());

            // then
            verify(orderRepository).save(argThat(o ->
                o.getRemainingAmount().equals(BigDecimal.valueOf(500))
            ));
        }
    }

    @Nested
    @DisplayName("Validation branches")
    class ValidationBranches {

        @Test
        void createOrder_withNullRequest_throwsException() {
            assertThatThrownBy(() -> orderService.createOrder(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void createOrder_withNegativePrice_throwsException() {
            // given
            final CreateOrderRequest request = CreateOrderRequest.builder()
                .customerId("customer-1")
                .items(List.of(new ItemDto("item-1", BigDecimal.valueOf(-100))))
                .build();

            // when & then
            assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price");
        }
    }
}
```

<!-- /section:mockito -->

---

<!-- section:e2e -->

# Part 4: UI/E2E Testing (Selenide + Testcontainers)

## 13. Base E2E Test Class

For projects with a frontend — E2E tests via **Selenide** and **BrowserWebDriverContainer**.

```java
/**
 * Base class for E2E tests.
 * Starts a headless browser in a Docker/Podman container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class BaseE2ETest {

    /** Application port — assigned by Spring Boot at startup. */
    @LocalServerPort
    protected int port;

    /**
     * Browser in container.
     * seleniarm — for ARM64 (Apple M1/M2/M3).
     * VNC available on port 5900 for debugging.
     */
    @Container
    static BrowserWebDriverContainer<?> browser = new BrowserWebDriverContainer<>(
            DockerImageName.parse("seleniarm/standalone-chromium:latest")
                .asCompatibleSubstituteFor("selenium/standalone-chrome"))
        .withCapabilities(chromeOptions());

    /** Chrome options for headless mode. */
    private static ChromeOptions chromeOptions() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments(
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--disable-gpu",
            "--window-size=1920,1080"
        );
        return options;
    }

    @BeforeAll
    static void setupSelenide() {
        // Connect Selenide to the browser in the container
        Configuration.remote = browser.getSeleniumAddress().toString();

        // Allure integration for screenshots
        SelenideLogger.addListener("AllureSelenide",
            new AllureSelenide()
                .screenshots(true)
                .savePageSource(true)
        );
    }

    @BeforeEach
    void setupBaseUrl() {
        // host.containers.internal — for Podman (localhost does not work from inside the container)
        Configuration.baseUrl = "http://host.containers.internal:" + port;

        // Timeouts
        Configuration.timeout = 10_000;
        Configuration.pageLoadTimeout = 30_000;
    }

    @AfterAll
    static void tearDown() {
        SelenideLogger.removeListener("AllureSelenide");
    }
}
```

### Key Notes

| Aspect | Solution |
|--------|---------|
| **ARM64 Mac** | `seleniarm/standalone-chromium` instead of `selenium/standalone-chrome` |
| **Podman** | `host.containers.internal` instead of `localhost` |
| **Headless** | `--no-sandbox`, `--disable-gpu`, `--disable-dev-shm-usage` |
| **Allure** | `AllureSelenide` for screenshots and page source |

## 14. E2E Test Example

```java
@Epic("UI")
@Feature("Catalog")
class CatalogPageIT extends BaseE2ETest {

    @Nested
    @DisplayName("Product Catalog")
    class CatalogTests {

        @Test
        @Story("Catalog Loading")
        @Severity(SeverityLevel.CRITICAL)
        void catalogPage_loadsSuccessfully() {
            // when
            open("/catalog");

            // then
            $("h1").shouldHave(text("Catalog"));
            $$(".product-card").shouldHave(sizeGreaterThan(0));
        }

        @Test
        @Story("Product Search")
        void searchProduct_findsMatchingItems() {
            // given
            open("/catalog");

            // when
            $("[data-testid='search-input']").setValue("Java");
            $("[data-testid='search-button']").click();

            // then
            $$(".product-card").shouldHave(sizeGreaterThan(0));
            $$(".product-card").first()
                .shouldHave(text("Java"));
        }

        @Test
        @Story("Filtering")
        void filterByCategory_showsOnlyMatchingProducts() {
            // given
            open("/catalog");

            // when
            $("[data-testid='category-filter']").click();
            $("[data-value='programming']").click();

            // then
            $$(".product-card").forEach(card ->
                card.$(".category").shouldHave(text("Programming"))
            );
        }
    }

    @Nested
    @DisplayName("Product Card")
    class ProductCardTests {

        @Test
        @Story("Navigate to Product")
        void clickOnProduct_opensProductPage() {
            // given
            open("/catalog");
            String productName = $$(".product-card").first()
                .$(".product-name").getText();

            // when
            $$(".product-card").first().click();

            // then
            $("h1").shouldHave(text(productName));
            $(".product-details").shouldBe(visible);
        }

        @Test
        @Story("Add to Cart")
        void addToCart_updatesCartBadge() {
            // given
            open("/catalog");

            // when
            $$(".product-card").first()
                .$("[data-testid='add-to-cart']").click();

            // then
            $("[data-testid='cart-badge']").shouldHave(text("1"));
        }
    }
}
```

## 15. E2E with Authorization

```java
@Epic("UI")
@Feature("User Profile")
class UserProfileIT extends BaseE2ETest {

    @BeforeEach
    void login() {
        open("/login");
        $("[data-testid='email']").setValue("test@example.com");
        $("[data-testid='password']").setValue("password123");
        $("[data-testid='login-button']").click();

        // Wait for redirect after successful authorization
        $("h1").shouldHave(text("User Profile"));
    }

    @Test
    @Story("User Profile")
    void userProfile_showsUserData() {
        // then
        $("[data-testid='user-email']").shouldHave(text("test@example.com"));
        $("[data-testid='user-name']").shouldBe(visible);
    }

    @Test
    @Story("Profile Editing")
    void editProfile_savesChanges() {
        // when
        $("[data-testid='edit-profile']").click();
        $("[data-testid='user-name-input']").setValue("New Name");
        $("[data-testid='save-button']").click();

        // then
        $(".success-message").shouldBe(visible);
        $("[data-testid='user-name']").shouldHave(text("New Name"));
    }
}
```

## 16. Page Objects (optional)

```java
/** Page Object for the catalog page. */
public class CatalogPage {

    public static void open() {
        Selenide.open("/catalog");
    }

    public static SelenideElement searchInput() {
        return $("[data-testid='search-input']");
    }

    public static SelenideElement searchButton() {
        return $("[data-testid='search-button']");
    }

    public static ElementsCollection productCards() {
        return $$(".product-card");
    }

    @Step("Search product: {query}")
    public static void search(String query) {
        searchInput().setValue(query);
        searchButton().click();
    }

    @Step("Filter by category: {category}")
    public static void filterByCategory(String category) {
        $("[data-testid='category-filter']").click();
        $("[data-value='" + category + "']").click();
    }
}
```

Usage:
```java
@Test
void searchProduct_findsItems() {
    CatalogPage.open();
    CatalogPage.search("Java");
    CatalogPage.productCards().shouldHave(sizeGreaterThan(0));
}
```

## 17. E2E Dependencies

```xml
<!-- Selenide -->
<dependency>
    <groupId>com.codeborne</groupId>
    <artifactId>selenide</artifactId>
    <version>7.2.2</version>
    <scope>test</scope>
</dependency>

<!-- Testcontainers Selenium -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>selenium</artifactId>
    <scope>test</scope>
</dependency>

<!-- Allure Selenide -->
<dependency>
    <groupId>io.qameta.allure</groupId>
    <artifactId>allure-selenide</artifactId>
    <version>${allure.version}</version>
    <scope>test</scope>
</dependency>
```

## 18. Docker Images for Different Platforms

```java
// Universal approach — detect architecture
private static DockerImageName getBrowserImage() {
    String arch = System.getProperty("os.arch").toLowerCase();

    if (arch.contains("aarch64") || arch.contains("arm")) {
        // ARM64: Apple M1/M2/M3, Linux ARM
        return DockerImageName.parse("seleniarm/standalone-chromium:latest")
            .asCompatibleSubstituteFor("selenium/standalone-chrome");
    } else {
        // x86_64: Intel/AMD
        return DockerImageName.parse("selenium/standalone-chrome:latest");
    }
}

@Container
static BrowserWebDriverContainer<?> browser = new BrowserWebDriverContainer<>(getBrowserImage())
    .withCapabilities(chromeOptions());
```

---

# Part 5: Test Data Builders

## 19. Reusable Builders

```java
/**
 * Builders for test data.
 * Extracted into a separate class for reuse.
 */
public final class TestDataBuilders {

    private TestDataBuilders() {}

    // ─────────────────────────────────────────────────────────────
    // Orders
    // ─────────────────────────────────────────────────────────────

    /** Creates a valid order creation request. */
    public static CreateOrderRequest createValidRequest() {
        return CreateOrderRequest.builder()
            .customerId("customer-" + UUID.randomUUID())
            .items(List.of(
                new ItemDto("product-1", BigDecimal.valueOf(100)),
                new ItemDto("product-2", BigDecimal.valueOf(200))
            ))
            .build();
    }

    /** Creates a test order with default values. */
    public static Order createTestOrder() {
        return Order.builder()
            .id("order-" + UUID.randomUUID())
            .customerId("customer-1")
            .status(OrderStatus.CREATED)
            .total(BigDecimal.valueOf(300))
            .items(List.of(createTestItem()))
            .createdAt(Instant.now())
            .build();
    }

    /** Creates an order with the specified status. */
    public static Order createOrderWithStatus(final OrderStatus status) {
        return createTestOrder().toBuilder()
            .status(status)
            .build();
    }

    /** Creates an order for the specified customer. */
    public static Order createOrderForCustomer(final String customerId) {
        return createTestOrder().toBuilder()
            .customerId(customerId)
            .build();
    }

    /** Creates an order with the specified total. */
    public static Order createOrderWithTotal(final String customerId, final BigDecimal total) {
        return createTestOrder().toBuilder()
            .customerId(customerId)
            .total(total)
            .build();
    }

    /** Creates a shipped order (for cancellation tests). */
    public static Order createShippedOrder() {
        return createOrderWithStatus(OrderStatus.SHIPPED);
    }

    // ─────────────────────────────────────────────────────────────
    // Items
    // ─────────────────────────────────────────────────────────────

    /** Creates a test item. */
    public static ItemDto createTestItem() {
        return new ItemDto("product-" + UUID.randomUUID(), BigDecimal.valueOf(100));
    }

    /** Creates a valid item. */
    public static ItemDto createValidItem() {
        return createTestItem();
    }

    // ─────────────────────────────────────────────────────────────
    // JSON helpers
    // ─────────────────────────────────────────────────────────────

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Converts an object to JSON. */
    @SneakyThrows
    public static String toJson(final Object obj) {
        return MAPPER.writeValueAsString(obj);
    }
}
```

Usage in tests:
```java
import static com.example.TestDataBuilders.*;

class OrderServiceTest {

    @Test
    void createOrder_success() {
        // given
        final CreateOrderRequest request = createValidRequest();

        // when
        final Order result = orderService.createOrder(request);

        // then
        assertThat(result).isNotNull();
    }
}
```

<!-- /section:e2e -->

---

<!-- section:maven -->

# Part 6: Package Structure & Maven Configuration

## 20. Separating Unit and Integration Tests

```
src/
├── main/java/
│   └── com/example/order/
│       ├── controller/
│       ├── service/
│       └── repository/
└── test/java/
    └── com/example/order/
        ├── unit/                          # Unit tests (Surefire)
        │   ├── service/
        │   │   └── OrderServiceTest.java  # *Test.java
        │   └── TestDataBuilders.java
        └── integration/                   # Integration tests (Failsafe)
            ├── BaseIntegrationTest.java
            ├── api/
            │   └── OrderControllerIT.java # *IT.java
            ├── kafka/
            │   └── OrderKafkaIT.java
            └── repository/
                └── OrderRepositoryIT.java
```

**Naming conventions:**
- `*Test.java` — Unit tests → **Surefire** (mvn test)
- `*IT.java` — Integration tests → **Failsafe** (mvn verify)

## 21. Properties

```xml
<properties>
    <jacoco.version>0.8.12</jacoco.version>
    <allure.version>2.29.0</allure.version>
    <aspectj.version>1.9.24</aspectj.version>
    <surefire.version>3.5.3</surefire.version>
    <failsafe.version>3.5.3</failsafe.version>

    <!-- JaCoCo writes its -javaagent here, empty by default -->
    <argLine></argLine>
</properties>
```

## 22. JaCoCo Plugin

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>${jacoco.version}</version>
    <executions>
        <!-- Writes -javaagent into the argLine property -->
        <execution>
            <id>prepare-agent</id>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>

        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

**Quality gate (in a separate profile or pluginManagement):**

```xml
<execution>
    <id>check</id>
    <phase>verify</phase>
    <goals>
        <goal>check</goal>
    </goals>
    <configuration>
        <rules>
            <rule>
                <element>BUNDLE</element>
                <limits>
                    <limit>
                        <counter>LINE</counter>
                        <value>COVEREDRATIO</value>
                        <minimum>0.80</minimum>
                    </limit>
                    <limit>
                        <counter>BRANCH</counter>
                        <value>COVEREDRATIO</value>
                        <minimum>0.80</minimum>
                    </limit>
                </limits>
            </rule>
        </rules>
        <excludes>
            <exclude>**/*Config.class</exclude>
            <exclude>**/*Properties.class</exclude>
            <exclude>**/Application.class</exclude>
        </excludes>
    </configuration>
</execution>
```

## 23. Surefire Plugin (Unit Tests)

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>${surefire.version}</version>
    <configuration>
        <!--
            ${argLine} = JaCoCo writes its -javaagent here
            -XX:+EnableDynamicAgentLoading = for Java 21+
            -javaagent:aspectjweaver = for Allure @Step
        -->
        <argLine>${argLine} -XX:+EnableDynamicAgentLoading -Dfile.encoding=${project.build.sourceEncoding} -javaagent:"${settings.localRepository}/org/aspectj/aspectjweaver/${aspectj.version}/aspectjweaver-${aspectj.version}.jar"</argLine>

        <includes>
            <include>**/*Test.java</include>
        </includes>
        <excludes>
            <exclude>**/*IT.java</exclude>
        </excludes>

        <systemPropertyVariables>
            <allure.results.directory>${project.build.directory}/allure-results</allure.results.directory>
        </systemPropertyVariables>
    </configuration>
    <dependencies>
        <dependency>
            <groupId>org.aspectj</groupId>
            <artifactId>aspectjweaver</artifactId>
            <version>${aspectj.version}</version>
        </dependency>
    </dependencies>
</plugin>
```

## 24. Failsafe Plugin (Integration Tests)

**Usually in a separate profile `integration-tests`:**

```xml
<profiles>
    <profile>
        <id>integration-tests</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-failsafe-plugin</artifactId>
                    <version>${failsafe.version}</version>
                    <executions>
                        <execution>
                            <goals>
                                <goal>integration-test</goal>
                                <goal>verify</goal>
                            </goals>
                        </execution>
                    </executions>
                    <configuration>
                        <argLine>${argLine} -XX:+EnableDynamicAgentLoading -Dfile.encoding=${project.build.sourceEncoding} -javaagent:"${settings.localRepository}/org/aspectj/aspectjweaver/${aspectj.version}/aspectjweaver-${aspectj.version}.jar"</argLine>

                        <includes>
                            <include>**/*IT.java</include>
                        </includes>

                        <systemPropertyVariables>
                            <allure.results.directory>${project.build.directory}/allure-results</allure.results.directory>
                        </systemPropertyVariables>
                    </configuration>
                    <dependencies>
                        <dependency>
                            <groupId>org.aspectj</groupId>
                            <artifactId>aspectjweaver</artifactId>
                            <version>${aspectj.version}</version>
                        </dependency>
                    </dependencies>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

## 25. Allure Maven Plugin

```xml
<plugin>
    <groupId>io.qameta.allure</groupId>
    <artifactId>allure-maven</artifactId>
    <version>2.15.2</version>
    <configuration>
        <reportVersion>${allure.version}</reportVersion>
        <resultsDirectory>${project.build.directory}/allure-results</resultsDirectory>
    </configuration>
</plugin>
```

## 26. Run Commands

```bash
# Unit tests only (fast, no Docker)
mvn test

# Integration tests (profile)
mvn verify -Pintegration-tests

# Coverage check (profile)
mvn verify -Pcoverage

# Allure report
mvn allure:serve
```

## 27. Test Dependencies (pom.xml)

```xml
<dependencies>
    <!-- Spring Boot Test (JUnit 5 + Mockito + AssertJ) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- Testcontainers -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-testcontainers</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>kafka</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- Awaitility for async tests -->
    <dependency>
        <groupId>org.awaitility</groupId>
        <artifactId>awaitility</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- WireMock for external APIs -->
    <dependency>
        <groupId>org.wiremock</groupId>
        <artifactId>wiremock-standalone</artifactId>
        <version>3.3.1</version>
        <scope>test</scope>
    </dependency>

    <!-- Allure JUnit 5 -->
    <dependency>
        <groupId>io.qameta.allure</groupId>
        <artifactId>allure-junit5</artifactId>
        <version>${allure.version}</version>
        <scope>test</scope>
    </dependency>

    <!-- AspectJ for Allure @Step -->
    <dependency>
        <groupId>org.aspectj</groupId>
        <artifactId>aspectjweaver</artifactId>
        <version>${aspectj.version}</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

# Quick Checklist

**Test structure:**
- [ ] Naming: `method_condition_expectedResult`
- [ ] Given-When-Then with comments
- [ ] AssertJ assertions (not JUnit)
- [ ] Allure: @Epic, @Feature, @Story, @Severity, @Step

**Organization:**
- [ ] @Nested classes for grouping by method
- [ ] TestDataBuilders for reuse
- [ ] Package separation: `unit/` and `integration/`

**Naming conventions:**
- [ ] `*Test.java` — Unit tests (Surefire, mvn test)
- [ ] `*IT.java` — Integration tests (Failsafe, mvn verify)

**Integration tests (core basket):**
- [ ] For atypical code: Context7 → how the API is really used
- [ ] Create optimal test scenarios (not just coverage)
- [ ] **First: positive** scenarios (happy path)
- [ ] **Then: critical negatives** (404, 400, 409, 401/403)
- [ ] **DO NOT write benchmarks** (use JMeter/Gatling for that)
- [ ] **Podman** configured: `DOCKER_HOST`, `TESTCONTAINERS_RYUK_DISABLED=true`
- [ ] BaseIntegrationTest with Testcontainers
- [ ] HTTP endpoints via TestRestTemplate
- [ ] Kafka via real broker (Testcontainers)
- [ ] JDBC via real PostgreSQL (Testcontainers)
- [ ] External APIs via WireMock

**Unit tests (for coverage):**
- [ ] Edge cases with Mockito
- [ ] Validation branches
- [ ] Error handling paths

**E2E tests (if frontend is present):**
- [ ] Selenide + BrowserWebDriverContainer
- [ ] ARM64: `seleniarm/standalone-chromium` (Apple M1/M2/M3)
- [ ] x86_64: `selenium/standalone-chrome`
- [ ] Podman: `host.containers.internal` instead of localhost
- [ ] ChromeOptions: `--no-sandbox`, `--disable-gpu`, `--disable-dev-shm-usage`
- [ ] Allure-Selenide: screenshots on failure
- [ ] Page Objects for reuse

**Maven plugins:**
- [ ] Properties: `<argLine></argLine>` (empty — JaCoCo will fill it in)
- [ ] JaCoCo: prepare-agent (writes to argLine)
- [ ] Surefire/Failsafe argLine: `${argLine} -XX:+EnableDynamicAgentLoading -javaagent:"${settings.localRepository}/org/aspectj/aspectjweaver/${aspectj.version}/aspectjweaver-${aspectj.version}.jar"`
- [ ] Failsafe in profile `integration-tests`
- [ ] Allure: allure-maven for reports

**Coverage:**
- [ ] LINE ≥ 80%
- [ ] BRANCH ≥ 80%
- [ ] JaCoCo check on merged.exec

<!-- /section:maven -->
