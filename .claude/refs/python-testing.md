# Python Testing Standards

<!-- section:philosophy -->

## Testing Philosophy

```
┌─────────────────────────────────────────────────────────────────┐
│           REAL INTEGRATION TESTS                                │
│      (HTTP via httpx, Postgres/Kafka via testcontainers)        │
│                                                                 │
│    → Main basket for core scenarios                             │
│    → Maximum stability in agentic development                   │
│    → Catch REAL bugs (migrations, schemas, serialization)       │
└─────────────────────────────────────────────────────────────────┘
                            +
┌─────────────────────────────────────────────────────────────────┐
│           UNIT TESTS WITH PROTOCOL-FAKES / pytest-mock          │
│              (edge cases, validation branches)                  │
│                                                                 │
│    → Push coverage to 80/80 (line + branch)                     │
│    → Edge cases: concurrent updates, retries, partial failures  │
│    → Fast feedback loop for domain logic                        │
└─────────────────────────────────────────────────────────────────┘
                            +
┌─────────────────────────────────────────────────────────────────┐
│              PROPERTY-BASED TESTS (Hypothesis)                  │
│                                                                 │
│    → Invariants: round-trip serialization, idempotency          │
│    → Automatically search for the minimal counterexample        │
│    → One such test catches what 50 example-based tests miss     │
└─────────────────────────────────────────────────────────────────┘
```

## Test Writing Rules

### 1. Never Mock DB, Queues, HTTP Servers

`testcontainers-python` spins up a real Postgres/Redis/Kafka in Docker. Mock DB =
test passes, migration fails in production.

```
Infrastructure (DB, queues, HTTP API) → testcontainers / respx
Internal services (your code's classes) → Protocol-fake or pytest-mock
Time → freezegun / time-machine
External HTTP APIs (payment gateways, gigachat, etc) → respx (httpx) or responses (requests)
```

### 2. Scenario Priority

```
┌─────────────────────────────────────────────────────────────────┐
│  1. POSITIVE SCENARIOS (first!)                                 │
│     → Happy path: valid request → successful response           │
│     → Core business flow works end-to-end                       │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│  2. CRITICAL NEGATIVES                                          │
│     → 404: resource not found                                   │
│     → 422: Pydantic validation errors                           │
│     → 409: business logic conflict                              │
│     → 401/403: unauthorized/forbidden                           │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│  3. EDGE CASES (unit + property-based)                          │
│     → Boundary values via Hypothesis                            │
│     → Concurrent updates, race conditions                       │
│     → Retry/timeout logic                                       │
└─────────────────────────────────────────────────────────────────┘
```

### 3. DO NOT Write in Tests

```python
# ❌ DO NOT write performance benchmarks
def test_create_order_performance():
    start = time.perf_counter()
    for _ in range(1000):
        order_service.create(request)
    duration = time.perf_counter() - start
    assert duration < 5.0  # ❌ Flaky! Depends on CI hardware

# ❌ DO NOT test throughput / latency
async def test_api_handles_100_rps(): ...  # ❌ Use locust/k6, not pytest

# ❌ DO NOT mock your own business logic
def test_create_order():
    mock_service = Mock()
    mock_service.create.return_value = Mock(id=1)
    result = mock_service.create(Mock())  # ❌ Testing a mock of a mock
    assert result.id == 1  # ❌ Meaningless

# ✅ Test actual functionality
async def test_create_order_with_valid_request_returns_201(client):
    request = build_valid_order_request()

    response = await client.post("/api/orders", json=request)

    assert response.status_code == 201
    assert response.json()["total"] == "300.00"
```

<!-- /section:philosophy -->

---

<!-- section:structure -->

# Part 1: Basic Patterns

## 1. Naming Convention

Format: `test_<unit>_<scenario>_<expected>`. English for names and docstrings.

```python
# Format: test_method_condition_expectedResult
def test_create_order_with_valid_items_returns_order_with_correct_total(): ...

def test_create_order_with_empty_items_raises_validation_error(): ...

def test_find_by_id_when_order_not_found_raises_not_found_error(): ...

def test_cancel_when_already_shipped_raises_conflict_error(): ...
```

**Files:**
- Test files mirror `src/`: `tests/unit/services/test_order_service.py` for `src/myservice/services/order_service.py`.
- `*_test.py` or `test_*.py` (pytest auto-discovery).

## 2. Arrange-Act-Assert Structure

AAA blocks separated by blank lines. Not by `# arrange` comments — by structure.

```python
# BAD: A wall of code — hard to see what's where
def test_create_order():
    customer_id = 1
    items = [OrderItem(product_id=1, quantity=2, price=Decimal("100"))]
    request = OrderCreate(customer_id=customer_id, items=items)
    result = order_service.create(request)
    assert result.id > 0
    assert result.total == Decimal("200")


# GOOD: Clear AAA blocks
def test_create_order_with_valid_items_calculates_correct_total():
    """Order creation calculates total correctly."""
    # Arrange
    request = OrderCreate(
        customer_id=1,
        items=[OrderItem(product_id=1, quantity=2, price=Decimal("100"))],
    )

    # Act
    result = order_service.create(request)

    # Assert
    assert result.id > 0
    assert result.total == Decimal("200")
```

**Alternative — given/when/then via docstring:**
```python
def test_cancel_shipped_order_raises_conflict():
    """
    Given: order in SHIPPED status
    When: attempting to cancel
    Then: ConflictError is raised
    """
    order = build_order(status=OrderStatus.SHIPPED)

    with pytest.raises(ConflictError, match="shipped"):
        order_service.cancel(order.id)
```

## 3. pytest assertions with introspection

Plain `assert`. pytest injects details on failure automatically — no `assertEqual` needed.

```python
# BAD: unittest-style
self.assertEqual(result.total, Decimal("300"))
self.assertIn("error", response.text)
self.assertTrue(order.is_valid())


# GOOD: pytest plain assert + introspection
assert result.total == Decimal("300")
assert "error" in response.text
assert order.is_valid()


# Collection checks
assert len(orders) == 3
assert all(o.status == OrderStatus.PENDING for o in orders)
assert orders[0].id == 1


# Object field checks — chaining not allowed → separate asserts
assert result is not None
assert result.id > 0
assert result.status == OrderStatus.PENDING


# Full structure check via ==
assert result.model_dump() == {
    "id": 1,
    "status": "pending",
    "total": "300.00",
    "items_count": 2,
}
```

**Assert messages:**
```python
# Add context for complex checks
assert result.id > 0, f"Invalid order ID: {result.id}"
assert response.status_code == 201, (
    f"Expected 201, got {response.status_code}. Body: {response.text}"
)
```

## 4. pytest.raises — exception checks

```python
# Simple type check
def test_withdraw_negative_amount_raises():
    with pytest.raises(ValidationError):
        account.withdraw(Decimal("-100"))


# Type + message check via regex
def test_withdraw_negative_amount_raises_with_message():
    with pytest.raises(ValidationError, match=r"must be positive"):
        account.withdraw(Decimal("-100"))


# Access the exception itself to check attributes
def test_not_found_error_has_entity_info():
    with pytest.raises(NotFoundError) as exc_info:
        order_service.find_by_id(999)

    assert exc_info.value.entity == "Order"
    assert exc_info.value.entity_id == 999
    assert exc_info.value.code == "NOT_FOUND"


# ExceptionGroup — Python 3.11+
def test_validate_order_collects_all_errors():
    with pytest.raises(ExceptionGroup) as exc_info:
        validate_order(invalid_order)

    errors = exc_info.value.exceptions
    assert len(errors) == 3
    assert all(isinstance(e, ValidationError) for e in errors)
```

## 5. pytest.approx — floating-point numbers

```python
# BAD: float equality — flaky
assert result == 0.1 + 0.2  # ❌ 0.30000000000000004 != 0.3


# GOOD: pytest.approx
def test_calculate_discount():
    result = calculate_discount(price=99.99, percent=15)
    assert result == pytest.approx(84.99, abs=0.01)


# For collections
assert results == pytest.approx([1.1, 2.2, 3.3], rel=1e-3)


# For Decimal use exact comparison, approx not needed
assert order.total == Decimal("84.99")
```

## 6. Grouping via Classes

Tests for one method/endpoint — in one class. Analogue of `@Nested` in Java.

```python
class TestOrderService:
    """Tests for OrderService."""

    class TestCreate:
        """create() — create an order."""

        def test_with_valid_items_returns_order(self, order_service: OrderService) -> None:
            """Creating with valid data returns an order."""
            request = build_valid_order_request()

            result = order_service.create(request)

            assert result.id > 0
            assert result.status == OrderStatus.PENDING

        def test_with_empty_items_raises_validation_error(
            self, order_service: OrderService
        ) -> None:
            """Empty items list raises ValidationError."""
            request = OrderCreate(customer_id=1, items=[])

            with pytest.raises(ValidationError, match="items"):
                order_service.create(request)

    class TestCancel:
        """cancel() — cancel an order."""

        def test_pending_order_cancels_successfully(
            self, order_service: OrderService, pending_order: Order
        ) -> None:
            """Cancelling a pending order succeeds."""
            cancelled = order_service.cancel(pending_order.id)

            assert cancelled.status == OrderStatus.CANCELLED

        def test_shipped_order_raises_conflict(
            self, order_service: OrderService, shipped_order: Order
        ) -> None:
            """Cannot cancel a shipped order."""
            with pytest.raises(ConflictError, match="shipped"):
                order_service.cancel(shipped_order.id)
```

⚠️ Classes in pytest **must not have** `__init__`. Fixtures are passed as method parameters.

<!-- /section:structure -->

---

<!-- section:config -->

# Part 2: pytest Configuration

## 7. pyproject.toml — pytest settings

```toml
[tool.pytest.ini_options]
addopts = [
    "-ra",                  # show reasons for skip/xfail
    "--strict-markers",     # typo in @pytest.mark.unti = error, not skip
    "--strict-config",      # typo in config = error
    "--showlocals",         # show local variables on failure
    "-p", "no:cacheprovider",
]
testpaths = ["tests"]
asyncio_mode = "auto"        # async tests without @pytest.mark.asyncio
xfail_strict = true          # xfail that unexpectedly passes = error
log_cli = true
log_cli_level = "WARNING"
markers = [
    "unit: fast tests without I/O",
    "integration: tests with real dependencies (DB, HTTP)",
    "e2e: end-to-end tests",
    "slow: tests running > 5 seconds",
]
filterwarnings = [
    "error",                                            # warning → error
    "ignore::DeprecationWarning:pkg_resources.*",       # third-party libs
]
```

**What each flag does:**
- `--strict-markers` — `@pytest.mark.untegration` (typo) will error out, not silently skip.
- `--strict-config` — unknown option in `pyproject.toml` will error out.
- `xfail_strict=true` — `xfail` test that suddenly passes = error. Fixes "forgotten" xfails.
- `filterwarnings=["error"]` — DeprecationWarning breaks the test. Prevents code rot.

## 8. conftest.py — hierarchy

One conftest.py per level. NOT one giant file.

```
tests/
├── conftest.py             # shared: settings, app, client
├── unit/
│   ├── conftest.py         # service mocks
│   └── services/
│       └── test_*.py
├── integration/
│   ├── conftest.py         # DB fixtures via testcontainers
│   ├── api/
│   │   └── test_*.py
│   └── repositories/
│       └── test_*.py
└── e2e/
    └── conftest.py         # full stack
```

```python
# tests/conftest.py — root
import pytest
from collections.abc import AsyncIterator
from httpx import ASGITransport, AsyncClient

from myservice.api import create_app
from myservice.config import Settings


@pytest.fixture(scope="session")
def settings() -> Settings:
    """Settings for tests."""
    return Settings(
        database_url="sqlite+aiosqlite:///:memory:",
        secret_key="test-secret",
        debug=True,
    )


@pytest.fixture
async def client() -> AsyncIterator[AsyncClient]:
    """HTTP client for API testing."""
    app = create_app()
    async with AsyncClient(
        transport=ASGITransport(app=app),
        base_url="http://test",
    ) as ac:
        yield ac


# tests/integration/conftest.py — fixtures with real DB
@pytest.fixture(scope="session")
def postgres_container():
    """PostgreSQL via testcontainers — one instance for the whole session."""
    from testcontainers.postgres import PostgresContainer
    with PostgresContainer("postgres:16-alpine") as container:
        yield container


# tests/integration/api/conftest.py — fixtures for API tests
@pytest.fixture
async def auth_headers(client: AsyncClient) -> dict[str, str]:
    """Authorization headers for protected endpoints."""
    response = await client.post(
        "/auth/login",
        json={"email": "test@test.com", "password": "secret"},
    )
    token = response.json()["access_token"]
    return {"Authorization": f"Bearer {token}"}
```

<!-- /section:config -->

---

<!-- section:fixtures -->

# Part 3: Fixtures

## 9. Fixture Scopes — Performance

Correct scope = fast tests. Heavy resources are created **once**.

```python
# BAD: Heavy fixture recreated on every test (10 minutes instead of 30 seconds)
@pytest.fixture
def db_engine():
    engine = create_engine(TEST_DB_URL)  # 500ms each time × 1000 tests!
    Base.metadata.create_all(engine)
    yield engine
    engine.dispose()


# GOOD: Heavy resource — session scope, isolation via transactions
@pytest.fixture(scope="session")
def db_engine():
    """DB engine — one instance for the entire test session."""
    engine = create_engine(TEST_DB_URL)
    Base.metadata.create_all(engine)
    yield engine
    Base.metadata.drop_all(engine)
    engine.dispose()


@pytest.fixture
def db_session(db_engine) -> Iterator[Session]:
    """DB session — new for each test, with transaction rollback."""
    connection = db_engine.connect()
    transaction = connection.begin()
    session = Session(bind=connection, join_transaction_mode="create_savepoint")

    yield session

    session.close()
    transaction.rollback()  # Isolation between tests
    connection.close()
```

**Scope reference:**

| Scope | When |
|---|---|
| `session` | Once per pytest run. Docker containers, DB engines. |
| `module` | Once per test file. Test data within the file. |
| `class` | Once per test class. Rarely needed. |
| `function` | Per test (default). DB sessions, mocks, mutable data. |

## 10. Async Fixtures

```python
# pytest-asyncio with asyncio_mode="auto" — async fixtures work without decorator

@pytest.fixture
async def async_session(async_engine) -> AsyncIterator[AsyncSession]:
    """Async DB session with rollback."""
    async with async_engine.connect() as connection:
        async with connection.begin() as transaction:
            session = AsyncSession(
                bind=connection,
                join_transaction_mode="create_savepoint",
                expire_on_commit=False,
            )
            try:
                yield session
            finally:
                await session.close()
                await transaction.rollback()


@pytest.fixture
async def populated_db(async_session: AsyncSession) -> AsyncSession:
    """DB with test data."""
    users = [
        UserModel(name=f"User {i}", email=f"user{i}@test.com")
        for i in range(1, 6)
    ]
    async_session.add_all(users)
    await async_session.commit()
    return async_session
```

## 11. Factory Fixtures

Factories instead of a separate fixture for each combination.

```python
# BAD: A separate fixture for each case — 20 fixtures for 20 variants
@pytest.fixture
def active_user():
    return User(name="Ivan", status="active")

@pytest.fixture
def inactive_user():
    return User(name="Petr", status="inactive")

@pytest.fixture
def admin_user():
    return User(name="Admin", status="active", role="admin")
# ... 17 more fixtures


# GOOD: Factory fixture — flexible, compact
from collections.abc import Callable

@pytest.fixture
def make_user() -> Callable[..., User]:
    """User factory with defaults."""
    counter = 0

    def _make_user(
        *,
        name: str = "Test User",
        email: str | None = None,
        status: UserStatus = UserStatus.ACTIVE,
        role: UserRole = UserRole.USER,
    ) -> User:
        nonlocal counter
        counter += 1
        return User(
            id=counter,
            name=name,
            email=email or f"user{counter}@test.com",
            status=status,
            role=role,
        )

    return _make_user


# Usage — compact
def test_admin_can_delete_user(make_user) -> None:
    admin = make_user(role=UserRole.ADMIN)
    target = make_user(name="To Delete")

    assert admin.can_delete(target) is True


def test_regular_user_cannot_delete(make_user) -> None:
    user = make_user(role=UserRole.USER)
    target = make_user()

    assert user.can_delete(target) is False
```

## 12. Polyfactory — Factories for Pydantic

For Pydantic / dataclass / SQLAlchemy models, use `polyfactory` instead of manual factories.

```python
from polyfactory.factories.pydantic_factory import ModelFactory

class UserFactory(ModelFactory[User]):
    """Automatic factory, generates all User fields."""
    __model__ = User

    # Custom values for specific fields
    role = UserRole.USER
    status = UserStatus.ACTIVE


def test_create_user():
    user = UserFactory.build()  # all fields filled with valid values

    assert user.id is not None
    assert "@" in user.email


def test_admin_permissions():
    admin = UserFactory.build(role=UserRole.ADMIN)
    target = UserFactory.build()

    assert admin.can_delete(target)


def test_batch():
    users = UserFactory.batch(size=10)  # 10 users in one command
    assert len(users) == 10
```

## 13. Cleanup via yield

```python
# GOOD: yield-based cleanup
@pytest.fixture
def temp_dir() -> Iterator[Path]:
    """Temporary directory with auto-cleanup."""
    import tempfile, shutil
    path = Path(tempfile.mkdtemp())
    try:
        yield path
    finally:
        shutil.rmtree(path)


# GOOD: Async cleanup
@pytest.fixture
async def kafka_consumer(kafka_bootstrap: str) -> AsyncIterator[AIOKafkaConsumer]:
    """Kafka consumer with auto-close."""
    consumer = AIOKafkaConsumer(
        "orders.events",
        bootstrap_servers=kafka_bootstrap,
        group_id="test-group",
    )
    await consumer.start()
    try:
        yield consumer
    finally:
        await consumer.stop()
```

<!-- /section:fixtures -->

---

<!-- section:parametrize -->

# Part 4: Parametrization

## 14. parametrize Instead of Copy-Paste

One test, many data sets. Analogue of JUnit `@ParameterizedTest`.

```python
# BAD: 10 identical tests
def test_validate_email_valid_standard():
    assert validate_email("user@example.com") is True

def test_validate_email_valid_short():
    assert validate_email("a@b.co") is True

def test_validate_email_missing_at():
    assert validate_email("userexample.com") is False
# ... 7 more


# GOOD: parametrize with meaningful ids
@pytest.mark.parametrize(
    ("email", "expected"),
    [
        pytest.param("user@example.com", True, id="standard"),
        pytest.param("a@b.co", True, id="minimal-valid"),
        pytest.param("user+tag@example.com", True, id="with-plus-tag"),
        pytest.param("userexample.com", False, id="missing-at"),
        pytest.param("@example.com", False, id="missing-local-part"),
        pytest.param("user@", False, id="missing-domain"),
        pytest.param("", False, id="empty"),
        pytest.param(" user@example.com ", False, id="with-whitespace"),
    ],
)
def test_validate_email(email: str, expected: bool) -> None:
    """Email validation for different inputs."""
    assert validate_email(email) is expected
```

⚠️ **id is mandatory** — in CI reports without id you'll see `test_validate_email[user@example.com-True]`,
with id — `test_validate_email[standard]`. Much more readable.

## 15. parametrize with marks

```python
@pytest.mark.parametrize(
    ("amount", "balance", "expected"),
    [
        pytest.param(Decimal("10"), Decimal("100"), Decimal("90"), id="happy_path"),
        pytest.param(Decimal("0"), Decimal("100"), Decimal("100"), id="zero_amount"),
        pytest.param(
            Decimal("-5"),
            Decimal("100"),
            None,
            id="negative_rejected",
            marks=pytest.mark.xfail(reason="negative amount validation TBD", strict=True),
        ),
        pytest.param(
            Decimal("1000000"),
            Decimal("100"),
            None,
            id="insufficient_funds",
            marks=pytest.mark.skip(reason="requires limit-checker"),
        ),
    ],
)
def test_withdraw(amount, balance, expected): ...
```

## 16. Parametrize Multiple Arguments

Each `@pytest.mark.parametrize` multiplies:

```python
# 3 × 2 = 6 tests automatically
@pytest.mark.parametrize("currency", ["USD", "EUR", "RUB"])
@pytest.mark.parametrize("amount", [Decimal("10"), Decimal("100")])
def test_money_construction(currency: str, amount: Decimal) -> None:
    money = Money(amount=amount, currency=currency)
    assert money.amount == amount
    assert money.currency == currency
```

## 17. Indirect parametrize — Parametrized Fixtures

```python
@pytest.fixture
def order_in_status(request) -> Order:
    """Fixture that creates an order in the given status."""
    status: OrderStatus = request.param
    return Order(id=1, status=status, items=[], total=Decimal("0"))


@pytest.mark.parametrize(
    "order_in_status",
    [OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.SHIPPED],
    indirect=True,
    ids=["pending", "confirmed", "shipped"],
)
def test_order_id_present(order_in_status: Order) -> None:
    assert order_in_status.id == 1
```

<!-- /section:parametrize -->

---

<!-- section:integration -->

# Part 5: Integration Tests

## 18. testcontainers-python — Real Dependencies

```python
# BAD: SQLite instead of Postgres — will miss PG-specific feature bugs
@pytest.fixture(scope="session")
def db():
    return create_engine("sqlite:///:memory:")  # No JSON, no ARRAY, no GIN indexes


# GOOD: Real Postgres via testcontainers
import pytest
from testcontainers.postgres import PostgresContainer
from sqlalchemy.ext.asyncio import create_async_engine, AsyncEngine


@pytest.fixture(scope="session")
def postgres_container() -> Iterator[PostgresContainer]:
    """PostgreSQL container — one per session (~3 seconds startup)."""
    with PostgresContainer("postgres:16-alpine", driver="psycopg") as container:
        yield container


@pytest.fixture(scope="session")
async def async_engine(postgres_container: PostgresContainer) -> AsyncIterator[AsyncEngine]:
    """Async engine, schema created once."""
    url = postgres_container.get_connection_url(driver="asyncpg")
    engine = create_async_engine(url, pool_pre_ping=True)

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    yield engine

    await engine.dispose()


@pytest.fixture
async def db_session(async_engine: AsyncEngine) -> AsyncIterator[AsyncSession]:
    """Session with rollback — isolation between tests via savepoint."""
    async with async_engine.connect() as connection:
        await connection.begin()
        async with AsyncSession(
            bind=connection,
            join_transaction_mode="create_savepoint",
            expire_on_commit=False,
        ) as session:
            yield session
        await connection.rollback()
```

**Other containers:**
```python
from testcontainers.redis import RedisContainer
from testcontainers.kafka import KafkaContainer
from testcontainers.minio import MinioContainer
from testcontainers.localstack import LocalStackContainer  # AWS

@pytest.fixture(scope="session")
def redis_container():
    with RedisContainer("redis:7-alpine") as container:
        yield container

@pytest.fixture(scope="session")
def kafka_container():
    with KafkaContainer("confluentinc/cp-kafka:7.5.0") as container:
        yield container
```

## 19. httpx AsyncClient — FastAPI in-process

`httpx.AsyncClient` via `ASGITransport` — NOT requests, NOT an external server.

```python
# BAD: requests + running server
import requests

def test_get_user():
    resp = requests.get("http://localhost:8000/users/1")  # ❌ requires a server
    assert resp.status_code == 200


# GOOD: httpx AsyncClient — in-process
import pytest
from httpx import ASGITransport, AsyncClient
from collections.abc import AsyncIterator

from myservice.api import create_app


@pytest.fixture
async def client() -> AsyncIterator[AsyncClient]:
    """HTTP client, works directly with FastAPI without the network stack."""
    app = create_app()
    async with AsyncClient(
        transport=ASGITransport(app=app),
        base_url="http://test",
    ) as ac:
        yield ac


async def test_get_user_returns_user_data(client: AsyncClient, populated_db) -> None:
    """Fetching a user returns correct fields."""
    response = await client.get("/users/1")

    assert response.status_code == 200
    data = response.json()
    assert data["id"] == 1
    assert data["email"] == "user1@test.com"


async def test_create_user_returns_201(client: AsyncClient) -> None:
    """Creating a user returns 201."""
    payload = {"name": "Ivan", "email": "ivan@test.com"}

    response = await client.post("/users", json=payload)

    assert response.status_code == 201
    data = response.json()
    assert data["name"] == "Ivan"
    assert data["email"] == "ivan@test.com"
    assert "id" in data
```

## 20. HTTP Integration Tests

```python
class TestOrdersAPI:
    """Tests for /api/orders."""

    class TestCreateOrder:
        """POST /api/orders."""

        async def test_with_valid_request_returns_201_and_order(
            self, client: AsyncClient
        ) -> None:
            """Valid request — order created, 201 response, body is correct."""
            request = {
                "customer_id": 1,
                "items": [
                    {"product_id": 1, "quantity": 2, "price": "100.00"},
                    {"product_id": 2, "quantity": 1, "price": "200.00"},
                ],
            }

            response = await client.post("/api/orders", json=request)

            assert response.status_code == 201
            data = response.json()
            assert data["id"] > 0
            assert Decimal(data["total"]) == Decimal("400.00")
            assert data["status"] == "pending"

        async def test_with_empty_items_returns_422(self, client: AsyncClient) -> None:
            """Empty items list — 422 Unprocessable Entity."""
            request = {"customer_id": 1, "items": []}

            response = await client.post("/api/orders", json=request)

            assert response.status_code == 422
            error = response.json()
            assert any("items" in err["loc"] for err in error["detail"])

        async def test_persisted_in_db(
            self, client: AsyncClient, db_session: AsyncSession
        ) -> None:
            """After POST the order is visible in DB."""
            response = await client.post("/api/orders", json=build_valid_request())
            order_id = response.json()["id"]

            from_db = await db_session.get(OrderModel, order_id)

            assert from_db is not None
            assert from_db.id == order_id

    class TestGetOrder:
        """GET /api/orders/{id}."""

        async def test_existing_order_returns_200(
            self, client: AsyncClient, existing_order: Order
        ) -> None:
            response = await client.get(f"/api/orders/{existing_order.id}")

            assert response.status_code == 200
            assert response.json()["id"] == existing_order.id

        async def test_missing_order_returns_404(self, client: AsyncClient) -> None:
            response = await client.get("/api/orders/999999")

            assert response.status_code == 404
            assert response.json()["code"] == "NOT_FOUND"
```

## 21. respx — Mocking External HTTP APIs

External APIs (payment gateways, GigaChat, etc) — `respx` for httpx, `responses` for requests.

```python
# BAD: real HTTP to payment gateway in tests — flaky, slow, may cost money
async def test_process_payment(client):
    response = await client.post("/api/orders/1/pay")  # ❌ real request!


# GOOD: respx for httpx
import respx
from httpx import Response

@respx.mock
async def test_process_payment_when_gateway_returns_success(
    client: AsyncClient, existing_order: Order
) -> None:
    """Successful payment via payment gateway."""
    payment_route = respx.post("https://gateway.example.com/api/payments").mock(
        return_value=Response(
            200,
            json={"transaction_id": "txn-123", "status": "SUCCESS"},
        )
    )

    response = await client.post(f"/api/orders/{existing_order.id}/pay")

    assert response.status_code == 200
    assert response.json()["status"] == "paid"

    # verify the request was made with the correct body
    assert payment_route.called
    request = payment_route.calls.last.request
    assert json.loads(request.content)["amount"] == "300.00"


@respx.mock
async def test_process_payment_when_gateway_fails_returns_502(
    client: AsyncClient, existing_order: Order
) -> None:
    """Payment gateway error — 502 Bad Gateway."""
    respx.post("https://gateway.example.com/api/payments").mock(
        return_value=Response(500, text="Internal Server Error")
    )

    response = await client.post(f"/api/orders/{existing_order.id}/pay")

    assert response.status_code == 502


@respx.mock
async def test_process_payment_when_gateway_timeout_returns_504(
    client: AsyncClient, existing_order: Order
) -> None:
    """Payment gateway timeout — 504 Gateway Timeout."""
    import httpx
    respx.post("https://gateway.example.com/api/payments").mock(
        side_effect=httpx.TimeoutException("timeout")
    )

    response = await client.post(f"/api/orders/{existing_order.id}/pay")

    assert response.status_code == 504
```

## 22. Database Integration Tests

```python
class TestOrderRepository:
    """Integration of OrderRepository with real Postgres."""

    async def test_save_and_find_by_id(self, db_session: AsyncSession) -> None:
        """Save and find by ID."""
        repo = OrderRepository(db_session)
        order = build_order(customer_id=1)

        saved = await repo.save(order)

        found = await repo.find_by_id(saved.id)
        assert found is not None
        assert found.id == saved.id

    async def test_find_by_status_returns_only_matching(
        self, db_session: AsyncSession
    ) -> None:
        """Status filter returns only matching records."""
        repo = OrderRepository(db_session)
        await repo.save(build_order(status=OrderStatus.PENDING))
        await repo.save(build_order(status=OrderStatus.PENDING))
        await repo.save(build_order(status=OrderStatus.SHIPPED))

        pending = await repo.find_by_status(OrderStatus.PENDING)

        assert len(pending) == 2
        assert all(o.status == OrderStatus.PENDING for o in pending)

    async def test_calculate_total_by_customer(self, db_session: AsyncSession) -> None:
        """Aggregation by customer."""
        repo = OrderRepository(db_session)
        await repo.save(build_order(customer_id=1, total=Decimal("100")))
        await repo.save(build_order(customer_id=1, total=Decimal("200")))
        await repo.save(build_order(customer_id=2, total=Decimal("500")))

        total = await repo.calculate_total_by_customer(customer_id=1)

        assert total == Decimal("300")
```

<!-- /section:integration -->

---

<!-- section:unit -->

# Part 6: Unit Tests with Mocks

## 23. pytest-mock — mocker fixture

`pytest-mock` provides the `mocker` fixture with auto-cleanup. Better than `unittest.mock` directly.

```python
# BAD: unittest.mock with manual management
from unittest.mock import patch

def test_send_notification():
    with patch("myservice.services.email.send") as mock_send:
        notification_service.notify_user(user)
        mock_send.assert_called_once()


# GOOD: pytest-mock — auto-cleanup, cleaner
def test_send_notification(mocker: MockerFixture) -> None:
    mock_send = mocker.patch("myservice.services.email.send")

    notification_service.notify_user(user)

    mock_send.assert_called_once()
```

## 24. autospec — Mandatory

`Mock(spec=Class)` or `mocker.create_autospec(Class)` — otherwise the test won't fail when you refactor a signature.

```python
# BAD: bare Mock — rename a method and the test stays "green"
def test_create_order():
    repo = Mock()
    repo.save_order_lol_typo.return_value = order  # ❌ Mock swallows any name!
    service = OrderService(repo)
    service.create(request)
    repo.save_order_lol_typo.assert_called()  # ❌ green, but will fail in production


# GOOD: autospec — Mock verifies the method actually exists
def test_create_order(mocker: MockerFixture) -> None:
    repo = mocker.create_autospec(OrderRepository, spec_set=True)
    repo.save.return_value = order

    service = OrderService(repo)
    service.create(request)

    repo.save.assert_called_once()
    repo.save_order_lol_typo  # ❌ AttributeError — method does not exist
```

## 25. Protocol-based fakes — Better than Mocks

It's often simpler to write a plain fake class via Protocol than to configure a Mock.

```python
# Protocol defines the contract
class OrderRepository(Protocol):
    async def save(self, order: Order) -> Order: ...
    async def find_by_id(self, order_id: int) -> Order | None: ...
    async def find_by_status(self, status: OrderStatus) -> list[Order]: ...


# Simple in-memory fake
class FakeOrderRepository:
    """In-memory fake for tests — more readable than mocks."""

    def __init__(self) -> None:
        self._storage: dict[int, Order] = {}
        self._next_id = 1

    async def save(self, order: Order) -> Order:
        if order.id == 0:
            order = replace(order, id=self._next_id)
            self._next_id += 1
        self._storage[order.id] = order
        return order

    async def find_by_id(self, order_id: int) -> Order | None:
        return self._storage.get(order_id)

    async def find_by_status(self, status: OrderStatus) -> list[Order]:
        return [o for o in self._storage.values() if o.status == status]


# Test with fake — reads like regular code
async def test_create_order_persists_with_pending_status() -> None:
    repo = FakeOrderRepository()
    service = OrderService(repo=repo)

    result = await service.create(build_request())

    assert result.id > 0
    assert result.status == OrderStatus.PENDING
    assert (await repo.find_by_id(result.id)) == result
```

**When fake, when mock:**
- **Fake** — if the dependency has 2-5 methods and simple logic (CRUD).
- **Mock** — if you need to verify *how exactly* it was called (assert_called_with).

## 26. Edge Cases — What Mocks Are For

```python
class TestEdgeCases:
    """Edge cases for reaching coverage targets."""

    async def test_cancel_when_concurrent_update_retries_and_succeeds(
        self, mocker: MockerFixture
    ) -> None:
        """Concurrent update — retry, succeeds on second attempt."""
        repo = mocker.create_autospec(OrderRepository, spec_set=True)
        order = build_order(status=OrderStatus.PENDING)
        repo.find_by_id.return_value = order
        repo.save.side_effect = [
            OptimisticLockError("Concurrent update"),
            replace(order, status=OrderStatus.CANCELLED),
        ]

        service = OrderService(repo=repo)
        result = await service.cancel(order.id)

        assert result.status == OrderStatus.CANCELLED
        assert repo.save.call_count == 2

    async def test_create_order_when_notification_fails_still_saves_order(
        self, mocker: MockerFixture
    ) -> None:
        """Notification failure does not break order creation."""
        repo = FakeOrderRepository()
        notifier = mocker.create_autospec(NotificationService, spec_set=True)
        notifier.send_order_created.side_effect = NotifierError("email service down")

        service = OrderService(repo=repo, notifier=notifier)
        result = await service.create(build_request())

        assert result is not None  # order saved despite notifier failure
        assert (await repo.find_by_id(result.id)) is not None

    async def test_partial_payment_updates_remaining_amount(
        self, mocker: MockerFixture
    ) -> None:
        """Partial payment updates remaining_amount."""
        repo = FakeOrderRepository()
        order = await repo.save(build_order(total=Decimal("1000")))
        gateway = mocker.create_autospec(PaymentGateway, spec_set=True)
        gateway.charge.return_value = PaymentResult(
            charged=Decimal("500"),
            status=PaymentStatus.PARTIAL,
        )

        service = OrderService(repo=repo, gateway=gateway)
        await service.process_payment(order.id)

        updated = await repo.find_by_id(order.id)
        assert updated.remaining_amount == Decimal("500")
```

## 27. freezegun — Freezing Time

```python
from freezegun import freeze_time

@freeze_time("2026-01-15 10:00:00")
def test_create_order_sets_created_at_to_now():
    """created_at = current time."""
    order = order_service.create(request)
    assert order.created_at == datetime(2026, 1, 15, 10, 0, 0)


def test_token_expires_after_one_hour():
    """JWT token expires after one hour."""
    with freeze_time("2026-01-15 10:00:00"):
        token = auth_service.create_token(user_id=1)

    with freeze_time("2026-01-15 11:00:01"):
        with pytest.raises(TokenExpiredError):
            auth_service.verify(token)


# Time shift within a test
def test_billing_cycle():
    with freeze_time("2026-01-01") as frozen:
        subscription = create_subscription(user_id=1)

        frozen.tick(timedelta(days=30))  # +30 days
        billing_service.process_renewal(subscription.id)

        assert subscription.next_billing == datetime(2026, 3, 2)
```

⚠️ Alternative — `time-machine`. Faster on large projects, same API.

<!-- /section:unit -->

---

<!-- section:property -->

# Part 7: Property-Based Testing (Hypothesis)

## 28. Why Property-Based Testing

Hypothesis generates thousands of inputs, finds the minimal counterexample, saves regression cases in `.hypothesis/`. One such test catches what 50 example-based tests miss.

**Use for:**
- Round-trip serialization (`from_json(to_json(x)) == x`)
- Idempotency (`f(f(x)) == f(x)`)
- Monotonicity (`a < b → f(a) <= f(b)`)
- Symmetry (`merge(a, b) == merge(b, a)`)
- Parsing (`parse(stringify(x)) == x`)
- Business logic invariants

```python
from hypothesis import given, strategies as st

# Round-trip
@given(st.decimals(min_value=0, max_value=Decimal("1_000_000"), places=2))
def test_money_serialization_round_trip(amount: Decimal) -> None:
    """Money → JSON → Money preserves the value."""
    money = Money(amount=amount, currency="USD")

    serialized = money.to_json()
    restored = Money.from_json(serialized)

    assert restored == money


# Idempotency
@given(st.text())
def test_normalize_email_is_idempotent(email: str) -> None:
    """normalize(normalize(x)) == normalize(x)."""
    once = normalize_email(email)
    twice = normalize_email(once)

    assert once == twice


# Invariant
@given(
    items=st.lists(
        st.builds(
            OrderItem,
            quantity=st.integers(min_value=1, max_value=100),
            price=st.decimals(min_value=Decimal("0.01"), max_value=Decimal("10000"), places=2),
        ),
        min_size=1,
        max_size=20,
    ),
)
def test_order_total_equals_sum_of_items(items: list[OrderItem]) -> None:
    """Total always == sum of items × prices."""
    order = Order(items=items)

    expected = sum((item.price * item.quantity for item in items), Decimal("0"))
    assert order.calculate_total() == expected
```

## 29. Strategies — Data Generators

```python
from hypothesis import strategies as st

# Basic
st.integers(min_value=0, max_value=100)
st.floats(allow_nan=False, allow_infinity=False, min_value=0, max_value=1e9)
st.decimals(places=2)
st.text(min_size=1, max_size=100, alphabet=st.characters(min_codepoint=32, max_codepoint=126))
st.booleans()
st.dates(min_value=date(2020, 1, 1), max_value=date(2030, 12, 31))
st.datetimes(timezones=st.just(timezone.utc))
st.uuids()
st.emails()
st.ip_addresses(v=4)
st.from_regex(r"^[A-Z]{3}-\d{4}$", fullmatch=True)

# Composition
st.lists(st.integers(), min_size=1, max_size=10)
st.dictionaries(keys=st.text(), values=st.integers(), min_size=1)
st.sets(st.integers())
st.tuples(st.text(), st.integers())

# st.builds — generating dataclass/Pydantic
order_strategy = st.builds(
    Order,
    customer_id=st.integers(min_value=1),
    items=st.lists(item_strategy, min_size=1, max_size=20),
)

# Custom composite strategies
@st.composite
def valid_email_strategy(draw):
    """Custom strategy for valid emails."""
    local = draw(st.text(alphabet="abcdefghijklmnopqrstuvwxyz0123456789", min_size=1, max_size=20))
    domain = draw(st.sampled_from(["example.com", "test.io", "domain.org"]))
    return f"{local}@{domain}"
```

## 30. Settings and Regression Cases

```python
from hypothesis import given, settings, example

@given(st.decimals(min_value=0, max_value=Decimal("1_000_000"), places=2))
@settings(max_examples=500, deadline=None)  # more examples, no deadline
@example(amount=Decimal("0"))                # must check zero
@example(amount=Decimal("0.01"))             # minimum value
@example(amount=Decimal("999999.99"))        # boundary
def test_money_serialization(amount: Decimal) -> None:
    money = Money(amount=amount, currency="USD")
    assert Money.from_json(money.to_json()) == money
```

Hypothesis **automatically saves** failed cases in `.hypothesis/examples/` — the next run checks them first. Commit this directory.

<!-- /section:property -->

---

<!-- section:snapshot -->

# Part 8: Snapshot Testing

For serializers, parsers, text/JSON generation — `syrupy` or `inline-snapshot`. Diff is readable, update with one command. Better than 200 lines of manual `assert`.

## 31. syrupy

```python
def test_order_serializes_correctly(snapshot):
    """OrderResponse.model_dump() matches snapshot."""
    order = build_order(id=1, customer_id=42)

    response = OrderResponse.model_validate(order)

    assert response.model_dump() == snapshot


# Update snapshots:
# pytest --snapshot-update
```

Snapshots are stored in `__snapshots__/` next to the test. Changes are committed with the code — the reviewer sees the delta.

## 32. inline-snapshot

Snapshot directly in the test code:

```python
from inline_snapshot import snapshot

def test_order_response():
    order = build_order(id=1, customer_id=42, total=Decimal("300"))

    response = OrderResponse.model_validate(order).model_dump()

    assert response == snapshot({
        "id": 1,
        "customer_id": 42,
        "total": "300.00",
        "status": "pending",
        "items": [],
    })


# Update: pytest --inline-snapshot=update
```

Useful when the snapshot is small — no need to switch between files.

<!-- /section:snapshot -->

---

<!-- section:async -->

# Part 9: Async Tests

## 33. pytest-asyncio with asyncio_mode="auto"

```toml
[tool.pytest.ini_options]
asyncio_mode = "auto"  # all async tests run automatically
```

```python
# With asyncio_mode = "auto" — no decorators needed
async def test_fetch_user_returns_user(db_session: AsyncSession) -> None:
    """Fetching a user from DB."""
    repo = UserRepository(db_session)
    await repo.save(User(name="Ivan", email="ivan@test.com"))

    user = await repo.find_by_email("ivan@test.com")

    assert user is not None
    assert user.name == "Ivan"
```

## 34. Parallel Scenarios — TaskGroup

```python
async def test_concurrent_requests_handled_correctly(client: AsyncClient) -> None:
    """API correctly handles 5 concurrent requests."""
    async with asyncio.TaskGroup() as tg:
        tasks = [
            tg.create_task(client.get(f"/users/{uid}"))
            for uid in range(1, 6)
        ]

    statuses = [t.result().status_code for t in tasks]
    assert all(s == 200 for s in statuses)
```

## 35. anyio for backend-agnostic tests

```python
# If code uses anyio (FastAPI, asyncpg) — test via anyio
@pytest.mark.anyio
async def test_works_on_both_asyncio_and_trio():
    """Test works on both asyncio and trio backends."""
    result = await my_anyio_function()
    assert result == "ok"


@pytest.fixture
def anyio_backend():
    return "asyncio"
```

<!-- /section:async -->

---

<!-- section:test-data -->

# Part 10: Test Data Builders

## 36. Full-Featured Builders

Analogue of Java TestDataBuilders — a separate module for reuse.

```python
# tests/fixtures/builders.py
from decimal import Decimal
from datetime import datetime
from uuid import uuid4

from myservice.domain.order import Order, OrderItem, OrderStatus

# ─────────────────────────────────────────────────────────────────
# Orders
# ─────────────────────────────────────────────────────────────────

def build_order(
    *,
    id: int = 0,
    customer_id: int = 1,
    status: OrderStatus = OrderStatus.PENDING,
    items: list[OrderItem] | None = None,
    total: Decimal = Decimal("300"),
    created_at: datetime | None = None,
) -> Order:
    """Creates a test Order with overridable fields."""
    return Order(
        id=id or 0,
        customer_id=customer_id,
        status=status,
        items=items or [build_order_item()],
        total=total,
        created_at=created_at or datetime(2026, 1, 1, 12, 0),
    )


def build_order_item(
    *,
    product_id: int | None = None,
    quantity: int = 1,
    price: Decimal = Decimal("100"),
) -> OrderItem:
    """Creates an OrderItem with defaults."""
    return OrderItem(
        product_id=product_id or hash(uuid4()) % 10000,
        quantity=quantity,
        price=price,
    )


def build_pending_order() -> Order:
    return build_order(status=OrderStatus.PENDING)


def build_shipped_order() -> Order:
    return build_order(status=OrderStatus.SHIPPED)


# ─────────────────────────────────────────────────────────────────
# Requests (for API tests)
# ─────────────────────────────────────────────────────────────────

def build_valid_order_request() -> dict[str, object]:
    """Valid request body for POST /api/orders."""
    return {
        "customer_id": 1,
        "items": [
            {"product_id": 1, "quantity": 2, "price": "100.00"},
            {"product_id": 2, "quantity": 1, "price": "100.00"},
        ],
    }
```

Usage:
```python
from tests.fixtures.builders import build_order, build_pending_order

async def test_cancel_pending_order(order_service: OrderService, db_session) -> None:
    saved = await db_session.save(build_pending_order())

    result = await order_service.cancel(saved.id)

    assert result.status == OrderStatus.CANCELLED
```

## 37. Faker — Realistic Data

```python
from faker import Faker

@pytest.fixture(scope="session")
def faker() -> Faker:
    """Faker with a fixed seed for reproducibility."""
    fake = Faker("ru_RU")
    Faker.seed(42)
    return fake


def test_create_user_with_realistic_data(faker: Faker, user_service):
    """Creating a user with realistic data."""
    user_data = {
        "email": faker.email(),
        "name": faker.name(),
        "phone": faker.phone_number(),
    }

    user = user_service.create(user_data)

    assert user.email == user_data["email"]
```

<!-- /section:test-data -->

---

<!-- section:ci -->

# Part 11: CI & Quality Gates

## 38. Coverage with branch

```toml
[tool.coverage.run]
branch = true                              # branch coverage, not just line
source = ["src"]
omit = [
    "src/*/migrations/*",
    "src/*/__main__.py",
]

[tool.coverage.report]
fail_under = 80                            # CI fails if < 80%
show_missing = true
skip_covered = true
exclude_lines = [
    "pragma: no cover",
    "if TYPE_CHECKING:",
    "if __name__ == .__main__.:",
    "@abstractmethod",
    "raise NotImplementedError",
    "\\.\\.\\.",
]
```

```bash
# Local run with report
pytest --cov --cov-report=term-missing

# HTML report
pytest --cov --cov-report=html
open htmlcov/index.html

# CI gate
pytest --cov --cov-fail-under=80
```

## 39. diff-cover — coverage on PR delta

100% coverage for legacy code — unrealistic. But new code must be covered.

```bash
# Coverage only for lines changed in the PR
diff-cover coverage.xml --compare-branch=main --fail-under=90
```

In CI: 80% overall + 90% on changed lines.

## 40. Parallel Run — pytest-xdist

```bash
# Parallel by CPU count
pytest -n auto

# Specific number of workers
pytest -n 4
```

⚠️ If tests fail in parallel — you have shared state. Fix it, don't disable xdist. This catches test interdependencies.

## 41. pytest-randomly — Random Order

Runs tests in random order. Catches tests that depend on execution order.

```toml
[project.optional-dependencies]
test = ["pytest-randomly>=3.16"]
```

```bash
# Seed is shown in output — to reproduce a failed run
pytest -p randomly --randomly-seed=12345
```

## 42. pytest-timeout — Hang Protection

```toml
[tool.pytest.ini_options]
timeout = 60                # global timeout 60 seconds
timeout_method = "thread"
```

```python
# Locally for a specific test
@pytest.mark.timeout(5)
async def test_quick_response():
    ...

# Disable timeout (e.g. for slow integration tests)
@pytest.mark.timeout(0)
async def test_long_migration():
    ...
```

## 43. Markers — filtering

```python
@pytest.mark.unit
def test_calculate_tax(): ...

@pytest.mark.integration
async def test_full_order_flow(): ...

@pytest.mark.slow
@pytest.mark.integration
async def test_database_migration(): ...

@pytest.mark.e2e
async def test_browser_checkout_flow(): ...
```

```bash
# Run by markers
pytest -m unit                          # fast only
pytest -m "integration and not slow"    # integration without slow
pytest -m "not e2e"                     # everything except e2e
pytest -m "unit or integration"         # unit + integration
```

## 44. CI gate — Mandatory Minimum

In every PR (`.github/workflows/ci.yml` or equivalent):

```yaml
- name: Lint
  run: ruff check --no-fix

- name: Format
  run: ruff format --check

- name: Type check
  run: pyright

- name: Security
  run: |
    bandit -r src/ -ll
    pip-audit

- name: Unit tests
  run: pytest -m "not integration and not e2e" --cov --cov-fail-under=80

- name: Integration tests
  run: pytest -m integration

- name: Coverage diff
  run: diff-cover coverage.xml --compare-branch=origin/main --fail-under=90
```

<!-- /section:ci -->

---

<!-- section:dependencies -->

# Part 12: Test Dependencies

```toml
[project.optional-dependencies]
test = [
    # Core
    "pytest>=8.3",
    "pytest-asyncio>=0.24",
    "pytest-mock>=3.14",

    # Run quality
    "pytest-randomly>=3.16",
    "pytest-xdist>=3.6",
    "pytest-timeout>=2.3",

    # HTTP / FastAPI
    "httpx>=0.28",
    "respx>=0.22",                # httpx mocks
    # "responses>=0.25",          # requests mocks (if using requests)

    # Real dependencies
    "testcontainers[postgres,redis,kafka]>=4.9",

    # Time
    "freezegun>=1.5",
    # "time-machine>=2.16",       # alternative to freezegun, faster

    # Property-based
    "hypothesis>=6.122",

    # Snapshot
    "syrupy>=4.7",
    # "inline-snapshot>=0.18",    # alternative to syrupy

    # Test data
    "polyfactory>=2.18",          # factories for Pydantic / dataclass
    "faker>=33.1",                # realistic data

    # Coverage
    "coverage[toml]>=7.6",
    "diff-cover>=9.2",
]
```

<!-- /section:dependencies -->

---

# Quick Checklist

**Test structure:**
- [ ] Naming: `test_<unit>_<scenario>_<expected>`
- [ ] AAA blocks separated by blank lines
- [ ] pytest plain `assert` (not unittest assertEqual)
- [ ] `pytest.raises` with `match=` for message checks
- [ ] `pytest.approx` for float (not for Decimal)
- [ ] Grouping via `class TestXxx`

**Configuration:**
- [ ] `--strict-markers` and `--strict-config` in `addopts`
- [ ] `xfail_strict = true`
- [ ] `asyncio_mode = "auto"` in `pyproject.toml`
- [ ] `filterwarnings = ["error"]`
- [ ] conftest.py split by levels, not one root file

**Fixtures:**
- [ ] Correct scopes: session for DB/containers, function for data
- [ ] Async fixtures via `AsyncIterator`
- [ ] Factory fixtures instead of a separate fixture for each case
- [ ] Cleanup via `yield` + `finally`

**Parametrize:**
- [ ] `pytest.param(..., id="...")` for all cases — id is mandatory
- [ ] `marks=pytest.mark.xfail(strict=True)` for known bugs

**Integration:**
- [ ] testcontainers for Postgres/Redis/Kafka — NOT SQLite
- [ ] httpx AsyncClient + ASGITransport — NOT requests
- [ ] respx for external HTTP APIs (no real requests)
- [ ] Transaction-rollback fixture for DB test isolation

**Unit:**
- [ ] `mocker` from pytest-mock with `create_autospec(Class, spec_set=True)`
- [ ] Protocol-based fakes for simple dependencies (CRUD)
- [ ] Mock only external dependencies — DB/HTTP/email
- [ ] freezegun/time-machine for time checks

**Property-based:**
- [ ] Hypothesis for round-trip, idempotency, invariants
- [ ] `@example(...)` for critical cases
- [ ] `.hypothesis/examples/` committed to the repository

**CI Gate:**
- [ ] Coverage 80% overall, 90% on diff (diff-cover)
- [ ] `branch = true` in coverage
- [ ] pytest-randomly enabled — order is randomized
- [ ] pytest-xdist `-n auto` — parallel run without shared state
- [ ] pytest-timeout — global timeout per test
- [ ] Markers with filtering: unit / integration / e2e / slow

**Commands:**
```bash
pytest                                              # all tests
pytest -m "not integration"                         # without integration
pytest -n auto                                      # parallel
pytest --cov --cov-report=term-missing              # with coverage
pytest -k "test_create_order"                       # by name
pytest --lf                                         # last failed
pytest --ff                                         # failed first
pytest -x                                           # stop on first error
pytest --pdb                                        # pdb on failure
pytest -p randomly --randomly-seed=12345            # reproduce order
```
