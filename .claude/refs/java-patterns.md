# Java Code Standards

<!-- section:basics -->

## 1. No-Nest Rule

Maximum ONE level of nesting inside a function. Extract nested logic to separate methods.

```java
// BAD: Multiple nesting levels
public void process(List<Order> orders) {
    for (Order order : orders) {
        if (order.isValid()) {
            for (Item item : order.getItems()) {
                if (item.getPrice() > 0) {
                    // deep nesting = hard to read
                }
            }
        }
    }
}

// GOOD: Flat, extracted methods
public void process(List<Order> orders) {
    orders.stream()
        .filter(Order::isValid)
        .forEach(this::processOrder);
}

private void processOrder(Order order) {
    order.getItems().stream()
        .filter(item -> item.getPrice() > 0)
        .forEach(this::processItem);
}
```

## 2. Atomic & Readable Code

Each method does ONE thing. Name describes WHAT it does.

```java
// BAD: Does multiple things
public void handleUser(User user) {
    // validates, saves, sends email, logs... 100 lines
}

// GOOD: Single responsibility
public void registerUser(User user) {
    validateUser(user);
    User saved = userRepository.save(user);
    notificationService.sendWelcomeEmail(saved);
}
```

## 3. Fail-Fast Validation

Check inputs FIRST. Use Spring Assert. No deep validation inside logic.

```java
// BAD: Validation mixed with logic
public Order createOrder(String userId, List<Item> items) {
    Order order = new Order();
    if (userId != null && !userId.isEmpty()) {
        order.setUserId(userId);
        if (items != null && !items.isEmpty()) {
            // deep nesting...
        }
    }
    return order;
}

// BAD: Manual if/throw
public Order createOrder(String userId, List<Item> items) {
    if (userId == null || userId.isBlank()) {
        throw new IllegalArgumentException("userId cannot be blank");
    }
    if (items == null || items.isEmpty()) {
        throw new IllegalArgumentException("items cannot be empty");
    }
    return Order.builder().build();
}

// GOOD: Spring Assert — clean and readable
import org.springframework.util.Assert;

public Order createOrder(final String userId, final List<Item> items) {
    Assert.hasText(userId, "userId cannot be blank");
    Assert.notEmpty(items, "items cannot be empty");

    return Order.builder()
        .userId(userId)
        .items(items)
        .build();
}
```

**Spring Assert methods:**
```java
Assert.notNull(obj, "obj cannot be null");           // != null
Assert.hasText(str, "str cannot be blank");          // not null, not empty, not whitespace
Assert.hasLength(str, "str cannot be empty");        // not null, not empty
Assert.notEmpty(collection, "list cannot be empty"); // not null, not empty
Assert.notEmpty(map, "map cannot be empty");         // not null, not empty
Assert.notEmpty(array, "array cannot be empty");     // not null, not empty
Assert.isTrue(condition, "condition must be true");  // boolean check
Assert.state(condition, "invalid state");            // IllegalStateException
```

## 4. No `var` - Explicit Types

Java is not Python. Types improve readability and IDE support.

```java
// BAD: var hides type
var result = service.process(data);
var items = getItems();

// GOOD: Explicit types
ProcessResult result = service.process(data);
List<OrderItem> items = getItems();
```

## 4.1. Always `final` — Immutable by Default

If a variable is not reassigned — it MUST be `final`. Including method parameters.

```java
// BAD: Unclear whether the variable is reassigned later in the code
public String processOrder(Order order, String userId) {
    Order saved = orderRepository.save(order);
    String notification = buildNotification(saved);
    notificationService.send(notification);
    return saved.getId();
    // What if somewhere below: order = modifyOrder(order); ???
}

// GOOD: final clearly shows — the variable does not change
public String processOrder(final Order order, final String userId) {
    final Order saved = orderRepository.save(order);
    final String notification = buildNotification(saved);
    notificationService.send(notification);
    return saved.getId();
}

// GOOD: Lambda parameters — effectively final (compiler will verify)
orders.stream()
    .filter(order -> order.isValid())  // order effectively final
    .map(order -> order.getId())
    .toList();
```

**Rule:** Everything `final` by default. If you think you need a non-final — refactor the code.

```java
// BAD: Mutable variables, break, access to global constant
public void processWithRetry(final Request request) {
    Response response = null;  // ❌ Mutable
    int attempt = 0;           // ❌ Mutable

    while (attempt < MAX_RETRY_ATTEMPTS) {  // ❌ Access to outer variable
        attempt++;
        response = client.send(request);
        if (response.isSuccess()) {
            break;  // ❌ break — hard to read
        }
    }
    handleResponse(response);
}

// GOOD: Method simply returns a result without handling it
/** Executes the request with retries. */
public Optional<Response> executeWithRetry(final Request request, final int maxRetries) {
    for (int attempt = 1; attempt <= maxRetries; attempt++) {
        final Response response = client.send(request);
        if (response.isSuccess()) {
            return Optional.of(response);
        }
        log.warn("Attempt {} of {} failed", attempt, maxRetries);
    }
    return Optional.empty();
}

// Calling code decides what to do with the result:
final Optional<Response> response = service.executeWithRetry(request, 3);
if (response.isEmpty()) {
    throw new RetryExhaustedException("Failed to execute request");
}
processResponse(response.get());
```

**Method parameters — ALWAYS final:**
```java
// BAD: Parameter can be accidentally reassigned
public void process(Order order) {
    order = enrichOrder(order);  // Dangerous! Modified the input parameter
    save(order);
}

// GOOD: Compiler prevents reassignment
public void process(final Order order) {
    order = enrichOrder(order);  // ❌ Compilation error!

    final Order enrichedOrder = enrichOrder(order);  // ✅ New variable
    save(enrichedOrder);
}
```

## 5. Lombok Annotations

Use Lombok to reduce boilerplate. More business logic, less noise.

```java
// BAD: Manual boilerplate
public class User {
    private String id;
    private String name;

    public User() {}
    public User(String id, String name) { ... }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    // ... 50 more lines
}

// GOOD: Lombok
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private String id;
    private String name;
}

// Common Lombok annotations:
@Data           // @Getter + @Setter + @ToString + @EqualsAndHashCode
@Builder        // Builder pattern
@Slf4j          // private static final Logger log = ...
@RequiredArgsConstructor // Constructor for final fields
@Value          // Immutable class (@Getter + final fields + @AllArgsConstructor)
```

## 6. Comments: Concise & Purposeful

No essays. State: WHY, WHERE used, WHAT for. **Javadoc should be written in English.**

**Comments are required for:**
- Classes
- Public methods
- **All class fields** (including private)

```java
// BAD: Wall of text
/**
 * This method is responsible for processing the user data that comes
 * from the external API. It was created because we needed to handle
 * the transformation of data from the legacy format to the new format...
 */
public User processUser(UserDTO dto) { ... }

// BAD: Fields without comments
@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orderRepository;
    private final NotificationService notificationService;
    private final MeterRegistry meterRegistry;
}

// GOOD: Concise and to the point
/** Converts UserDTO from an external API into the domain entity User. */
public User processUser(UserDTO dto) { ... }

/**
 * Order processing service.
 * Used in OrderController for the REST API.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    /** Repository for order persistence. */
    private final OrderRepository orderRepository;

    /** Notification service (email, push). */
    private final NotificationService notificationService;

    /** Metrics for monitoring (Prometheus/Grafana). */
    private final MeterRegistry meterRegistry;

    /** Maximum number of order processing attempts. */
    private static final int MAX_RETRY_ATTEMPTS = 3;

    /** Delay between attempts. */
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
}
```

**For Lombok @Data/@Value classes — same rules apply:**
```java
/** User data for the REST API. */
@Data
@Builder
public class UserDto {

    /** Unique user identifier (UUID). */
    private String id;

    /** Email for authentication and notifications. */
    private String email;

    /** Display name in the UI. */
    private String displayName;

    /** User roles for RBAC. */
    private List<String> roles;
}
```

### Spring Boot Auto-Configuration Comments

For `@ConfigurationProperties` classes, include YAML example with ALL properties:

```java
/**
 * GigaChat API configuration.
 *
 * Example application.yml:
 * ```yaml
 * gigachat:
 *   api:
 *     base-url: https://gigachat.devices.sberbank.ru/api/v1
 *     auth-url: https://ngw.devices.sberbank.ru:9443/api/v2/oauth
 *     scope: GIGACHAT_API_PERS
 *     model: GigaChat
 *     timeout: 30s
 *     max-retries: 3
 *   credentials:
 *     client-id: ${GIGACHAT_CLIENT_ID}
 *     client-secret: ${GIGACHAT_CLIENT_SECRET}
 * ```
 */
@Data
@ConfigurationProperties(prefix = "gigachat")
public class GigaChatProperties {
    private Api api = new Api();
    private Credentials credentials = new Credentials();

    @Data
    public static class Api {
        private String baseUrl = "https://gigachat.devices.sberbank.ru/api/v1";
        private String authUrl = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth";
        private String scope = "GIGACHAT_API_PERS";
        private String model = "GigaChat";
        private Duration timeout = Duration.ofSeconds(30);
        private int maxRetries = 3;
    }

    @Data
    public static class Credentials {
        private String clientId;
        private String clientSecret;
    }
}
```

## 7. No Global State

Static logger is OK. Mutable static state is NOT OK.

```java
// OK: Static logger (immutable)
@Slf4j
public class OrderService {
    // Lombok adds: private static final Logger log = LoggerFactory.getLogger(OrderService.class);
}

// BAD: Mutable global state
public class OrderService {
    private static AtomicInteger counter = new AtomicInteger(0);
    private static List<Order> cache = new ArrayList<>();  // Shared mutable state!

    public void process() {
        counter.incrementAndGet();  // Side effect!
        cache.add(order);           // Mutating global state!
    }
}

// GOOD: Instance state, injected dependencies
@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orderRepository;  // Injected
    private final MeterRegistry meterRegistry;      // For metrics

    public void process(Order order) {
        orderRepository.save(order);
        meterRegistry.counter("orders.processed").increment();
    }
}
```

## 8. Code Search with Serena

Before implementing, search existing code patterns using Serena MCP.

```
# If Serena is available, use it for semantic code search:
mcp__serena__search_symbols("OrderService")
mcp__serena__find_references("processOrder")
mcp__serena__get_hover_info("/path/to/File.java", line, column)

# If Serena is disabled:
1. Check MCP server status
2. Enable Serena in settings
3. Retry search

# Fallback to Grep if Serena unavailable:
Grep("class.*Service", type="java")
Grep("@RestController", type="java")
```

<!-- /section:basics -->

---

<!-- section:java17 -->

# Modern Java Features (17+)

## 9. Records — Immutable Data Classes

Use records for DTOs and value objects. Replaces Lombok @Value.

```java
// GOOD: Record for DTO
/** Order data for the REST API. */
public record OrderDto(
    String id,
    String customerId,
    List<ItemDto> items,
    BigDecimal total
) {
    // Compact constructor for validation (fail-fast!)
    public OrderDto {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id cannot be blank");
        }
        if (items == null) {
            items = List.of();
        }
    }
}

// Usage:
OrderDto order = new OrderDto("123", "customer-1", items, total);
String id = order.id();  // Accessor without get-prefix
```

## 10. Pattern Matching for instanceof

Eliminates explicit cast after instanceof.

```java
// BAD: Old style with cast
public String describe(Object obj) {
    if (obj instanceof String) {
        String str = (String) obj;  // Redundant cast
        return "String length: " + str.length();
    }
    return "Unknown";
}

// GOOD: Pattern matching (Java 16+)
public String describe(Object obj) {
    if (obj instanceof String str) {
        return "String length: " + str.length();
    }
    if (obj instanceof List<?> list) {
        return "List size: " + list.size();
    }
    return "Unknown: " + obj.getClass().getSimpleName();
}
```

## 11. Switch Expressions

Expression instead of statement. Returns a value.

```java
// BAD: Switch statement with break
public String getDayType(DayOfWeek day) {
    String result;
    switch (day) {
        case MONDAY:
        case TUESDAY:
        case WEDNESDAY:
        case THURSDAY:
        case FRIDAY:
            result = "Workday";
            break;
        case SATURDAY:
        case SUNDAY:
            result = "Weekend";
            break;
        default:
            result = "Unknown";
    }
    return result;
}

// GOOD: Switch expression (Java 14+)
public String getDayType(DayOfWeek day) {
    return switch (day) {
        case MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY -> "Workday";
        case SATURDAY, SUNDAY -> "Weekend";
    };  // Compiler checks exhaustiveness for enums!
}
```

## 12. Text Blocks

For multiline strings: SQL, JSON, YAML.

```java
// BAD: String concatenation
String sql = "SELECT u.id, u.name " +
    "FROM users u " +
    "WHERE u.status = 'ACTIVE' " +
    "ORDER BY u.name";

// GOOD: Text block (Java 15+)
String sql = """
    SELECT u.id, u.name
    FROM users u
    WHERE u.status = 'ACTIVE'
    ORDER BY u.name
    """;

// JSON template
String json = """
    {
        "userId": "%s",
        "action": "%s",
        "timestamp": "%s"
    }
    """.formatted(userId, action, Instant.now());
```

<!-- /section:java17 -->

<!-- section:errors -->

## 13. Error Handling in Spring Boot

**Sealed classes / Result pattern are NOT needed.** Spring Boot handles exceptions automatically.

### Custom exceptions with `@ResponseStatus`:

```java
/** Entity not found → 404. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotFoundException extends RuntimeException {
    public NotFoundException(final String message) {
        super(message);
    }
}

/** Business logic conflict → 409. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ConflictException extends RuntimeException {
    public ConflictException(final String message) {
        super(message);
    }
}
```

### `@ControllerAdvice` for global handling:

```java
/** Global exception handler. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Assert.xxx() throws IllegalArgumentException → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(final IllegalArgumentException e) {
        log.warn("Validation error: {}", e.getMessage());
        return ResponseEntity.badRequest()
            .body(new ErrorResponse("VALIDATION_ERROR", e.getMessage()));
    }

    /** Bean Validation errors → 400. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(final MethodArgumentNotValidException e) {
        final String message = e.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
            .body(new ErrorResponse("VALIDATION_ERROR", message));
    }
}

/** Error response. */
public record ErrorResponse(String code, String message) {}
```

### Service — simply throws exceptions:

```java
/** Finds an order by ID. */
public Order findById(final String id) {
    Assert.hasText(id, "id cannot be blank");  // → 400 via @ControllerAdvice

    return orderRepository.findById(id)
        .orElseThrow(() -> new NotFoundException("Order not found: " + id));  // → 404
}

/** Cancels an order. */
public Order cancel(final String id) {
    final Order order = findById(id);

    if (order.getStatus() == OrderStatus.SHIPPED) {
        throw new ConflictException("Cannot cancel a shipped order");  // → 409
    }

    order.setStatus(OrderStatus.CANCELLED);
    return orderRepository.save(order);
}
```

### Controller — clean, without try/catch:

```java
@PostMapping
public Order create(@Valid @RequestBody final OrderDto dto) {
    return orderService.createOrder(dto);  // @Valid → 400 if invalid
}

@GetMapping("/{id}")
public Order getById(@PathVariable final String id) {
    return orderService.findById(id);  // NotFoundException → 404
}
```

<!-- /section:errors -->

<!-- section:java17 -->

## 14. Named Constants

No magic numbers/strings.

```java
// BAD: Magic values
if (retryCount > 3) { ... }
Thread.sleep(5000);
if (status.equals("ACTIVE")) { ... }

// GOOD: Named constants
private static final int MAX_RETRY_ATTEMPTS = 3;
private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

public static final String STATUS_ACTIVE = "ACTIVE";
public static final String STATUS_INACTIVE = "INACTIVE";

if (retryCount > MAX_RETRY_ATTEMPTS) { ... }
Thread.sleep(RETRY_DELAY.toMillis());
if (STATUS_ACTIVE.equals(status)) { ... }  // null-safe!
```

## 15. Try-with-resources

Automatic resource closing. Always use for I/O.

```java
// BAD: Manual closing
public List<String> readLines(Path path) {
    BufferedReader reader = null;
    try {
        reader = Files.newBufferedReader(path);
        return reader.lines().toList();
    } finally {
        if (reader != null) {
            reader.close();  // Can throw an exception!
        }
    }
}

// GOOD: Try-with-resources
public List<String> readLines(Path path) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(path)) {
        return reader.lines().toList();
    }
}

// Multiple resources
public void copyData(Path source, Path target) throws IOException {
    try (InputStream in = Files.newInputStream(source);
         OutputStream out = Files.newOutputStream(target)) {
        in.transferTo(out);
    }
}
```

<!-- /section:java17 -->

---

<!-- section:java21 -->

# Java 21+ Features (⚠️ Requires Java 21)

## 16. Pattern Matching in Switch ⚠️ Java 21

Full pattern matching with exhaustiveness check.

```java
// Java 21: Pattern matching + record patterns
public String handleResult(Result<Order> result) {
    return switch (result) {
        case Success<Order>(Order order) -> "Order created: " + order.id();
        case Failure<Order>(String msg, Throwable cause) -> {
            log.error("Error: {}", msg, cause);
            yield "Error: " + msg;
        }
    };  // Compiler checks ALL cases!
}

// Guard clauses in switch
public String describeNumber(Object obj) {
    return switch (obj) {
        case Integer i when i > 0 -> "Positive: " + i;
        case Integer i when i < 0 -> "Negative: " + i;
        case Integer i -> "Zero";
        case null -> "null";
        default -> "Not a number";
    };
}
```

## 17. Virtual Threads ⚠️ Java 21

Lightweight threads for I/O-bound tasks.

```java
// Spring Boot 3.2+: application.yml
// spring.threads.virtual.enabled: true

// Programmatic usage
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

List<CompletableFuture<Response>> futures = requests.stream()
    .map(request -> CompletableFuture.supplyAsync(
        () -> httpClient.send(request),
        executor
    ))
    .toList();

List<Response> responses = futures.stream()
    .map(CompletableFuture::join)
    .toList();
```

## 18. Sequenced Collections ⚠️ Java 21

Unified API for first/last elements.

```java
// Java 21: New methods for all collections
List<String> list = new ArrayList<>(List.of("a", "b", "c"));

String first = list.getFirst();      // "a"
String last = list.getLast();        // "c"
list.addFirst("start");              // ["start", "a", "b", "c"]
list.addLast("end");                 // ["start", "a", "b", "c", "end"]

List<String> reversed = list.reversed();  // View, not a copy
```

<!-- /section:java21 -->

---

# Quick Checklist

Before submitting Java code:

**Required (all versions):**
- [ ] No more than 1 nesting level per method
- [ ] Each method does ONE thing
- [ ] Fail-fast: Spring Assert at method entry
- [ ] No `var` — explicit types everywhere
- [ ] `final` everywhere — variables and method parameters
- [ ] Lombok annotations for boilerplate
- [ ] Comments: classes, methods, ALL fields (Russian OK)
- [ ] No mutable static state
- [ ] Named constants, no magic values
- [ ] Try-with-resources for I/O
- [ ] Searched existing patterns with Serena/Grep

**Java 17+:**
- [ ] Records for DTOs and value objects
- [ ] Pattern matching for instanceof
- [ ] Switch expressions (no pattern matching)
- [ ] Text blocks for SQL/JSON
- [ ] Spring Boot error handling: @ResponseStatus + @ControllerAdvice

**Java 21+ (if JAVA_VERSION >= 21):**
- [ ] Pattern matching in switch
- [ ] Virtual threads for I/O-bound
- [ ] Sequenced collections API
