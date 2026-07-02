# Python Code Standards

Modern Python (3.12+) — strict, typed, immutable by default. If a codebase doesn't follow this — it's outdated, not "pythonic".

**Testing:** see `.claude/refs/python-testing.md`

<!-- section:layout -->

# Part 1: Project Layout & Toolchain

## 1. src/ Layout — mandatory

`src/` layout is the `src/main/java` for Python. Importing `myservice` only works after `pip install -e .`, which catches "works locally, fails in prod" bugs.

```
project/
├── pyproject.toml           # single config (pom.xml/build.gradle)
├── uv.lock                  # lock file (uv) or poetry.lock
├── .pre-commit-config.yaml
├── src/
│   └── myservice/
│       ├── __init__.py
│       ├── domain/          # value objects, entities (no I/O!)
│       ├── application/     # use cases, services
│       ├── infrastructure/  # DB, HTTP, queues — Protocol implementations
│       ├── api/             # FastAPI/Litestar handlers
│       └── config.py
└── tests/
    ├── unit/
    ├── integration/
    ├── e2e/
    ├── conftest.py
    └── fixtures/
```

**Forbidden:**
- `setup.py` — legacy, removed from modern templates
- `requirements.txt` without a lock file
- top-level package in repository root
- Mixing `domain/` and `infrastructure/` imports (clean domain knows nothing about DB)

## 2. pyproject.toml — single config

One file for everything: dependencies, linters, tests, coverage. No `setup.cfg`, `.flake8`, `tox.ini`, `mypy.ini`.

```toml
[project]
name = "myservice"
version = "0.1.0"
requires-python = ">=3.12"
dependencies = [
    "fastapi>=0.115",
    "pydantic>=2.9",
    "pydantic-settings>=2.6",
    "structlog>=24.4",
    "sqlalchemy[asyncio]>=2.0",
    "asyncpg>=0.30",
    "httpx>=0.28",
]

[project.optional-dependencies]
dev = [
    "ruff>=0.8",
    "pyright>=1.1.390",
    "bandit>=1.8",
    "pip-audit>=2.7",
    "pre-commit>=4.0",
]
test = [
    "pytest>=8.3",
    "pytest-asyncio>=0.24",
    "pytest-mock>=3.14",
    "pytest-randomly>=3.16",
    "pytest-xdist>=3.6",
    "pytest-timeout>=2.3",
    "coverage[toml]>=7.6",
    "hypothesis>=6.122",
    "testcontainers>=4.9",
    "respx>=0.22",
    "freezegun>=1.5",
    "polyfactory>=2.18",
    "syrupy>=4.7",
]

[tool.ruff]
line-length = 100
target-version = "py312"
src = ["src", "tests"]

[tool.ruff.lint]
select = ["ALL"]
ignore = [
    "D203",    # one-blank-line-before-class (conflicts with D211)
    "D213",    # multi-line-summary-second-line (conflicts with D212)
    "COM812",  # missing-trailing-comma (conflicts with formatter)
    "ISC001",  # implicit-string-concat (conflicts with formatter)
]

[tool.ruff.lint.per-file-ignores]
"tests/**" = ["S101", "PLR2004", "ANN", "D"]  # assert/magic numbers/annotations OK in tests

[tool.pyright]
include = ["src", "tests"]
strict = ["src"]
pythonVersion = "3.12"
reportMissingTypeStubs = "error"
reportImplicitOverride = "error"
reportUnknownParameterType = "error"
reportUnknownMemberType = "warning"

[tool.pytest.ini_options]
addopts = "-ra --strict-markers --strict-config"
testpaths = ["tests"]
asyncio_mode = "auto"
xfail_strict = true
markers = [
    "unit: fast tests without I/O",
    "integration: tests with real dependencies (DB, HTTP)",
    "e2e: end-to-end tests",
    "slow: tests running > 5 seconds",
]

[tool.coverage.run]
branch = true
source = ["src"]
omit = ["src/*/migrations/*", "src/*/__main__.py"]

[tool.coverage.report]
fail_under = 80
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

## 3. Toolchain Stack

| Goal | Tool | Java equivalent |
|---|---|---|
| Dependency manager | **uv** (or Poetry/PDM) | Maven/Gradle |
| Linter + formatter | **Ruff** | Checkstyle + Spotless |
| Static analyzer | **Pyright** (strict) | SpotBugs / ErrorProne |
| Security | **Bandit** + **pip-audit** | OWASP Dependency-Check |
| Tests | **pytest** | JUnit 5 |
| Coverage | **coverage.py** | JaCoCo |
| Property-based | **Hypothesis** | jqwik |
| Containers in tests | **testcontainers-python** | Testcontainers |
| Pre-commit gate | **pre-commit** | Git hooks + Husky |

**Ruff replaced**: flake8, isort, pylint, pyupgrade, autoflake, pep8-naming, pydocstyle, eradicate, bugbear. One binary, ~50× faster.

## 4. Pre-commit Configuration

```yaml
# .pre-commit-config.yaml
repos:
  - repo: https://github.com/astral-sh/ruff-pre-commit
    rev: v0.8.4
    hooks:
      - id: ruff
        args: [--fix, --exit-non-zero-on-fix]
      - id: ruff-format

  - repo: https://github.com/RobertCraigie/pyright-python
    rev: v1.1.390
    hooks:
      - id: pyright

  - repo: https://github.com/PyCQA/bandit
    rev: 1.8.0
    hooks:
      - id: bandit
        args: [-c, pyproject.toml]
        additional_dependencies: ["bandit[toml]"]
```

<!-- /section:layout -->

---

<!-- section:typing -->

# Part 2: Type System

Type hints — not an option, but a contract. `pyright --strict` in CI works like `javac -Werror`.

## 5. Type Hints Everywhere

All functions, module variables, parameters, return types — with annotations. No exceptions.

```python
# BAD: No types — contract is invisible
def process_order(order, items):
    total = 0
    for item in items:
        total += item["price"] * item["qty"]
    return {"order_id": order["id"], "total": total}


# GOOD: Explicit types
from decimal import Decimal

def process_order(order: Order, items: list[OrderItem]) -> OrderResult:
    total: Decimal = sum(
        (item.price * item.quantity for item in items),
        start=Decimal("0"),  # IMPORTANT: without start it will be int 0 → TypeError on Decimal
    )
    return OrderResult(order_id=order.id, total=total)
```

**Rules:**
```python
from __future__ import annotations  # deferred type evaluation — in every file

# Collections — built-in generics (Python 3.9+)
names: list[str] = []
config: dict[str, int] = {}
unique_ids: set[int] = set()

# Optional — via union (PEP 604, Python 3.10+)
description: str | None = None

# Callable
handler: Callable[[Request], Response]

# Return type — ALWAYS (including None)
def get_name() -> str: ...
def save(item: Item) -> None: ...
async def fetch(url: str) -> bytes: ...
```

## 6. Any is banned in production code

`Any` disables type checking. If unavoidable — `# type: ignore[reason]` with explanation.

```python
# BAD: Any eats through the contract
def parse(data: Any) -> dict[str, Any]:
    return json.loads(data)

# GOOD: Concrete types or TypedDict
def parse(data: bytes) -> UserPayload:
    raw: dict[str, object] = json.loads(data)
    return UserPayload.model_validate(raw)
```

## 7. Self, override, Final, Literal, NewType

Modern Python type system is Java `final`, `@Override`, sealed types, type-safe IDs.

```python
from typing import Final, Literal, NewType, Self, override

# === Final — Java `final` for attributes and constants ===
class Config:
    """Application config, fields are immutable after init."""
    api_url: Final[str]
    timeout_seconds: Final[int]

    def __init__(self, api_url: str, timeout: int) -> None:
        self.api_url = api_url
        self.timeout_seconds = timeout

MAX_RETRY_ATTEMPTS: Final = 3  # type inferred as Literal[3]

# === Self — for fluent API and factory methods (PEP 673, 3.11+) ===
class QueryBuilder:
    """Fluent SQL builder."""
    def __init__(self) -> None:
        self._where: list[str] = []

    def where(self, condition: str) -> Self:  # returns correct subclass
        self._where.append(condition)
        return self

    @classmethod
    def for_table(cls, table: str) -> Self:
        return cls()

# === @override — Java @Override (PEP 698, 3.12+) ===
class BaseRepository:
    def find_by_id(self, entity_id: int) -> object | None: ...

class UserRepository(BaseRepository):
    @override
    def find_by_id(self, entity_id: int) -> User | None:
        # compiler will verify the method actually exists in the parent
        ...

# === Literal — type-safe string constants ===
LogLevel = Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"]

def set_level(level: LogLevel) -> None:  # mypy/pyright will catch typos
    ...

set_level("INFO")     # OK
set_level("invalid")  # ❌ type error

# === NewType — type-safe ID types instead of bare int ===
UserId = NewType("UserId", int)
OrderId = NewType("OrderId", int)

def transfer(from_user: UserId, to_user: UserId, order: OrderId) -> None: ...

user_id = UserId(42)
order_id = OrderId(100)
transfer(user_id, order_id, user_id)  # ❌ type error: OrderId passed instead of UserId
```

## 8. Protocol — structural typing

`Protocol` (PEP 544) = Java interface, but without mandatory `implements`. Any class with a matching signature is an implementation.

```python
from typing import Protocol, runtime_checkable

class NotificationSender(Protocol):
    """Contract for sending notifications."""
    def send(self, recipient: str, message: str) -> bool: ...


# Any class with a matching method — automatically an implementation
class EmailSender:
    """Send email via SMTP."""
    def send(self, recipient: str, message: str) -> bool:
        return True

class SmsSender:
    """Send SMS via gateway."""
    def send(self, recipient: str, message: str) -> bool:
        return True


def notify(sender: NotificationSender, recipient: str, message: str) -> bool:
    """Accepts any class implementing the Protocol."""
    return sender.send(recipient, message)


notify(EmailSender(), "user@test.com", "Hello")  # OK
notify(SmsSender(), "+79001234567", "Hello")     # OK
```

**ABC vs Protocol:**

```python
# Protocol — duck typing with type checking (for DI)
class Repository(Protocol):
    def find_by_id(self, entity_id: int) -> Entity | None: ...
    def save(self, entity: Entity) -> Entity: ...

# ABC — when shared logic is needed (template method)
from abc import ABC, abstractmethod

class BaseRepository[E: Entity](ABC):
    """Base repository with shared logic."""

    @abstractmethod
    def find_by_id(self, entity_id: int) -> E | None: ...

    @abstractmethod
    def save(self, entity: E) -> E: ...

    def find_or_raise(self, entity_id: int) -> E:
        """Shared implementation — find or raise NotFoundError."""
        result = self.find_by_id(entity_id)
        if result is None:
            raise NotFoundError(self.__class__.__name__, entity_id)
        return result
```

## 9. assert_never — exhaustive checks

`typing.assert_never` = Java `sealed switch` exhaustiveness check. If you forget a case — pyright won't let it pass.

```python
from typing import assert_never, Literal

OrderStatus = Literal["pending", "shipped", "delivered", "cancelled"]

def describe_status(status: OrderStatus) -> str:
    """Pyright checks coverage of all values."""
    match status:
        case "pending":
            return "Awaiting processing"
        case "shipped":
            return "Shipped"
        case "delivered":
            return "Delivered"
        case "cancelled":
            return "Cancelled"
        case _:
            assert_never(status)  # ❌ compiler will complain if a case is missed


# Same for discriminated unions
@dataclass(frozen=True, slots=True)
class Success:
    kind: Literal["success"] = "success"
    value: int

@dataclass(frozen=True, slots=True)
class Failure:
    kind: Literal["failure"] = "failure"
    error: str

Result = Success | Failure

def handle(result: Result) -> str:
    match result:
        case Success(value=v):
            return f"Got {v}"
        case Failure(error=e):
            return f"Error: {e}"
        case _:
            assert_never(result)  # exhaustive check
```

## 10. TYPE_CHECKING for breaking import cycles

```python
from __future__ import annotations
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    # Import needed ONLY for type checker, not for runtime
    from myservice.domain.user import User
    from myservice.domain.order import Order

class OrderService:
    def for_user(self, user: User) -> list[Order]:  # types as strings until runtime
        ...
```

<!-- /section:typing -->

---

<!-- section:data -->

# Part 3: Data Modeling

## 11. Immutability by default

All data containers — `frozen=True, slots=True, kw_only=True`. This is Java `record`.

```python
# BAD: Mutable, positional arguments, no slots
@dataclass
class Money:
    amount: Decimal
    currency: str
    # Can mutate, extra attributes, positional confusion

money = Money(Decimal("100"), "USD")
money.amount = Decimal("999")  # ❌ Mutating silently


# GOOD: Fully protected dataclass
@dataclass(frozen=True, slots=True, kw_only=True)
class Money:
    """Money — immutable value object."""
    amount: Decimal
    currency: str

money = Money(amount=Decimal("100"), currency="USD")  # explicit names
money.amount = Decimal("999")  # ❌ FrozenInstanceError
money.extra = "foo"            # ❌ AttributeError (slots blocks extras)
```

**What each flag provides:**
- `frozen=True` — immutability, hashable, equivalent to Java `final` fields.
- `slots=True` — fixed schema, ~30% memory savings, blocks extra attributes.
- `kw_only=True` — enforces keyword arguments, no hell of 7 positionals.

## 12. dataclass vs Pydantic vs NamedTuple

| Type | When to use |
|---|---|
| **`@dataclass`** | Internal value objects, DTO between layers, domain entities |
| **Pydantic BaseModel** | System boundaries — HTTP request/response, JSON, queues |
| **`NamedTuple`** | Lightweight immutable tuples (points, coordinates, pairs) |
| **`TypedDict`** | JSON shape without runtime validation (e.g., API response) |

```python
# === dataclass: domain layer ===
@dataclass(frozen=True, slots=True, kw_only=True)
class OrderCalculation:
    """Result of order cost calculation."""
    subtotal: Decimal
    tax: Decimal
    total: Decimal

# === Pydantic: API boundary ===
from pydantic import BaseModel, Field

class OrderResponse(BaseModel):
    """HTTP response for order creation."""
    id: int
    total: Decimal = Field(..., gt=0)
    created_at: datetime

# === NamedTuple: lightweight tuples ===
class Point(NamedTuple):
    x: float
    y: float

# === TypedDict: shape for non-validated data ===
class GitHubUser(TypedDict):
    login: str
    id: int
    avatar_url: str
```

## 13. Enum — no magic strings

```python
# BAD: Magic strings — typo = runtime bug
def set_status(order: Order, status: str) -> None:
    order.status = status  # "active"? "Active"? "ACTIVE"?

set_status(order, "actve")  # Typo passed — will fail an hour later


# GOOD: StrEnum (Python 3.11+) — serializes as string automatically
from enum import StrEnum, IntEnum, auto

class OrderStatus(StrEnum):
    """Order statuses in the lifecycle."""
    PENDING = "pending"
    CONFIRMED = "confirmed"
    SHIPPED = "shipped"
    DELIVERED = "delivered"
    CANCELLED = "cancelled"

def set_status(order: Order, status: OrderStatus) -> None:
    order.status = status

set_status(order, OrderStatus.CONFIRMED)
print(OrderStatus.CONFIRMED)         # "confirmed"
json.dumps({"s": OrderStatus.CONFIRMED})  # {"s": "confirmed"}


# IntEnum — for numeric constants
class Priority(IntEnum):
    LOW = 1
    MEDIUM = 2
    HIGH = 3
```

**When `Literal`, when `Enum`:**
- `Enum` — has behavior/methods, needs value iteration, identity matters.
- `Literal["a", "b"]` — lightweight enumeration, no methods, purely type-level.

<!-- /section:data -->

---

<!-- section:errors -->

# Part 4: Error Handling

## 14. Custom Exception Hierarchy

Exception hierarchy like Java: base application class, domain subclasses.

```python
# BAD: Bare Exception without context
def get_user(user_id: int):
    user = db.find(user_id)
    if not user:
        raise Exception("not found")  # Which user? What to do?


# GOOD: Hierarchy with context
class AppError(Exception):
    """Base application exception. Never inherits from BaseException."""

    def __init__(self, message: str, code: str = "UNKNOWN") -> None:
        self.message = message
        self.code = code
        super().__init__(message)


class DomainError(AppError):
    """Business logic errors (distinct from technical errors)."""


class InfrastructureError(AppError):
    """Infrastructure errors (DB, network, files)."""


class NotFoundError(DomainError):
    """Entity not found."""

    def __init__(self, entity: str, entity_id: str | int) -> None:
        super().__init__(
            message=f"{entity} with id={entity_id} not found",
            code="NOT_FOUND",
        )
        self.entity = entity
        self.entity_id = entity_id


class ValidationError(DomainError):
    """Input data validation error."""

    def __init__(self, field: str, reason: str) -> None:
        super().__init__(
            message=f"Field '{field}': {reason}",
            code="VALIDATION_ERROR",
        )
        self.field = field
        self.reason = reason


# Usage — informative
def get_user(user_id: int) -> User:
    user = db.find(user_id)
    if user is None:
        raise NotFoundError("User", user_id)
    return user
```

## 15. raise from — preserving the cause

Always use `raise X from original` — preserves the chain, like Java `caused by`.

```python
# BAD: Losing the original error
def fetch_user(user_id: int) -> User:
    try:
        return db.find_by_id(user_id)
    except SQLAlchemyError:
        raise InfrastructureError("DB error")  # ❌ lost stack trace


# GOOD: from preserves the cause
def fetch_user(user_id: int) -> User:
    try:
        return db.find_by_id(user_id)
    except SQLAlchemyError as exc:
        raise InfrastructureError("DB error") from exc  # ✅ full chain


# GOOD: from None — if intentionally hiding (security, abstraction)
def authenticate(token: str) -> User:
    try:
        return decode_jwt(token)
    except (jwt.InvalidSignatureError, jwt.ExpiredSignatureError) as exc:
        # not showing details of why exactly the token is invalid
        raise AuthError("Invalid credentials") from None
```

## 16. ExceptionGroup — multiple errors at once

Python 3.11+: you can throw a batch of errors (e.g., all validation errors).

```python
def validate_order(order: OrderInput) -> None:
    """Collects ALL validation errors, does not fail on first."""
    errors: list[ValidationError] = []

    if not order.customer_id:
        errors.append(ValidationError("customer_id", "required field"))
    if order.total < 0:
        errors.append(ValidationError("total", "cannot be negative"))
    if not order.items:
        errors.append(ValidationError("items", "at least one item required"))

    if errors:
        raise ExceptionGroup("Order validation errors", errors)


# Handling via except*
try:
    validate_order(order)
except* ValidationError as eg:
    for err in eg.exceptions:
        logger.warning("validation_failed", field=err.field, reason=err.reason)
```

## 17. Never `except:` or `except Exception:` without logic

```python
# BAD: Swallows everything, including SystemExit, KeyboardInterrupt
try:
    do_work()
except:
    pass  # ❌ hides bugs forever


# BAD: Swallows Exception without logging
try:
    do_work()
except Exception:
    pass  # ❌ worse than crashing — invisible error


# GOOD: Log + re-raise or explicit intent
try:
    do_work()
except SpecificError as exc:
    logger.exception("operation_failed", operation="do_work")
    raise  # re-raise

# GOOD: Only if we can TRULY recover
try:
    cache.set(key, value)
except CacheError as exc:
    # cache unavailable — continue without it, but log
    logger.warning("cache_unavailable", error=str(exc))
```

## 18. No bare assert in production

`assert` is disabled by the `python -O` flag. Use `raise` to check invariants.

```python
# BAD: assert in production code
def withdraw(account: Account, amount: Decimal) -> None:
    assert amount > 0, "amount must be positive"  # ❌ disappears under -O
    account.balance -= amount


# GOOD: explicit exception
def withdraw(account: Account, amount: Decimal) -> None:
    if amount <= 0:
        raise ValidationError("amount", "must be positive")
    account.balance -= amount
```

`assert` OK **only** in tests and for type narrowing inside a function.

<!-- /section:errors -->

---

<!-- section:logging -->

# Part 5: Logging

## 19. structlog — structured logs

`print()` is forbidden in any code except CLI. Structured logs are mandatory.

```python
# BAD: print is invisible in prod, no levels, no context
def process_payment(order_id: str, amount: float):
    print(f"Processing payment for {order_id}")
    print(f"Amount: {amount}")
    print("Done!")


# GOOD: structlog — JSON logs, bound context
import structlog

logger = structlog.get_logger()

def process_payment(order_id: str, amount: Decimal) -> PaymentResult:
    log = logger.bind(order_id=order_id, amount=str(amount))
    log.info("payment_processing_started")

    try:
        result = gateway.charge(amount)
        log.info("payment_completed", transaction_id=result.tx_id)
        return result
    except GatewayError as exc:
        log.exception("payment_failed", gateway=gateway.name)
        raise
```

**Event name conventions:**
- snake_case in past tense or present continuous: `payment_completed`, `user_created`, `request_started`
- NOT sentences: `"Payment was completed"` — that's for message, not for event

## 20. Stdlib logging — fallback

If structlog is unavailable — stdlib `logging` with lazy formatting.

```python
import logging

logger = logging.getLogger(__name__)

# GOOD: lazy format — does not waste CPU if DEBUG level is off
logger.info("Processing payment: order=%s, amount=%s", order_id, amount)

# BAD: f-string executes ALWAYS, even if log is filtered out
logger.info(f"Processing payment: order={order_id}, amount={amount}")  # ❌
```

<!-- /section:logging -->

---

<!-- section:io -->

# Part 6: I/O & Resources

## 21. pathlib over os.path

`pathlib.Path` — the only API for files and paths.

```python
# BAD: os.path — string operations, hard to read
import os

config_path = os.path.join(os.path.dirname(__file__), "..", "config", "app.yaml")
if os.path.exists(config_path):
    with open(config_path, "r") as f:
        data = f.read()


# GOOD: pathlib — object API, cross-platform
from pathlib import Path

CONFIG_DIR: Final[Path] = Path(__file__).parent.parent / "config"

def load_config(name: str = "app.yaml") -> str:
    config_path = CONFIG_DIR / name
    if not config_path.exists():
        raise FileNotFoundError(f"Config not found: {config_path}")
    return config_path.read_text(encoding="utf-8")
```

**Useful methods:**
```python
path = Path("/data/reports/2024")

path.mkdir(parents=True, exist_ok=True)  # Create with parents
path.iterdir()                           # Iterate over contents
path.glob("*.csv")                       # Find files
path.rglob("**/*.py")                    # Recursive search
path.suffix                              # ".csv"
path.stem                                # "report"
path.with_suffix(".json")                # Replace extension
path.read_text(encoding="utf-8")
path.write_text(data, encoding="utf-8")
path.read_bytes()
path.write_bytes(data)
```

## 22. Context Managers — required for resources

```python
# BAD: Manual management — easy to forget to close
def export_data(data: list[dict], path: Path) -> None:
    f = open(path, "w")
    try:
        json.dump(data, f)
    finally:
        f.close()  # If close() raises, the file may remain open


# GOOD: with — automatic closing
def export_data(data: list[dict[str, object]], path: Path) -> None:
    with path.open("w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
```

**Custom context manager via contextlib:**
```python
from contextlib import contextmanager, asynccontextmanager
from collections.abc import Iterator, AsyncIterator
import time

@contextmanager
def measure_time(operation: str) -> Iterator[None]:
    """Measures execution time of an operation."""
    start = time.perf_counter()
    try:
        yield
    finally:
        elapsed = time.perf_counter() - start
        logger.info("operation_completed", operation=operation, elapsed_ms=elapsed * 1000)


@asynccontextmanager
async def db_transaction(session: AsyncSession) -> AsyncIterator[AsyncSession]:
    """Transaction with automatic rollback on error."""
    try:
        yield session
        await session.commit()
    except Exception:
        await session.rollback()
        raise
```

<!-- /section:io -->

---

<!-- section:idiom -->

# Part 7: Functional Idioms

## 23. Comprehensions — readable, not nested

One level. Nested comprehensions are forbidden — break into functions.

```python
# BAD: Nested comprehension — hard to read
result = [
    transform(item)
    for group in data
    if group.is_active
    for item in group.items
    if item.price > 0 and item.category in allowed_categories
]


# GOOD: Break into steps
def is_eligible(item: Item, allowed: set[str]) -> bool:
    """Checks whether the item qualifies by price and category."""
    return item.price > 0 and item.category in allowed

active_items: list[Item] = [
    item
    for group in data if group.is_active
    for item in group.items
]

result: list[TransformedItem] = [
    transform(item)
    for item in active_items
    if is_eligible(item, allowed_categories)
]
```

```python
# dict / set comprehensions
users_by_id: dict[int, User] = {user.id: user for user in users}
unique_emails: set[str] = {user.email.lower() for user in users}

# generator expression — for large collections (does not materialise the list)
total = sum(item.price for item in items)
```

## 24. match/case — pattern matching

Python 3.10+ pattern matching — more powerful than Java switch for recursive structures.

```python
# Simple match on literals
def http_status_message(code: int) -> str:
    match code:
        case 200 | 201 | 204:
            return "Success"
        case 400 | 422:
            return "Bad Request"
        case 401 | 403:
            return "Unauthorized"
        case 404:
            return "Not Found"
        case 500 | 502 | 503:
            return "Server Error"
        case _:
            return f"Unknown status: {code}"


# Pattern matching on structures — destructuring
@dataclass(frozen=True, slots=True)
class Point:
    x: float
    y: float

@dataclass(frozen=True, slots=True)
class Circle:
    center: Point
    radius: float

@dataclass(frozen=True, slots=True)
class Rectangle:
    top_left: Point
    bottom_right: Point

Shape = Circle | Rectangle

def area(shape: Shape) -> float:
    match shape:
        case Circle(radius=r):
            return 3.14159 * r * r
        case Rectangle(top_left=Point(x=x1, y=y1), bottom_right=Point(x=x2, y=y2)):
            return abs(x2 - x1) * abs(y2 - y1)
        case _:
            assert_never(shape)


# Pattern matching on dict (for JSON parsing)
def parse_event(event: dict[str, object]) -> Event:
    match event:
        case {"type": "user_created", "id": int(uid), "name": str(name)}:
            return UserCreated(user_id=uid, name=name)
        case {"type": "order_placed", "order_id": int(oid), "items": list(items)}:
            return OrderPlaced(order_id=oid, items=items)
        case {"type": event_type}:
            raise ValueError(f"Unknown event type: {event_type}")
        case _:
            raise ValueError("Invalid event format")
```

## 25. functools — caching and multimethods

```python
from functools import cache, lru_cache, cached_property, singledispatch

# === @cache — no limit, for pure functions ===
@cache
def fibonacci(n: int) -> int:
    if n < 2:
        return n
    return fibonacci(n - 1) + fibonacci(n - 2)

# === @lru_cache(maxsize=N) — with memory limit ===
@lru_cache(maxsize=1024)
def get_user_settings(user_id: int) -> UserSettings:
    """Cache for 1024 users."""
    return db.fetch_settings(user_id)

# === @cached_property — cache on instance ===
class Order:
    """Order. items_count is computed once."""
    def __init__(self, items: list[OrderItem]) -> None:
        self.items = items

    @cached_property
    def items_count(self) -> int:
        """Expensive computation — cached on instance."""
        return sum(item.quantity for item in self.items)

# === @singledispatch — multimethods (like @overload in Java) ===
@singledispatch
def serialize(value: object) -> str:
    raise NotImplementedError(f"Cannot serialize {type(value).__name__}")

@serialize.register
def _(value: int) -> str:
    return str(value)

@serialize.register
def _(value: datetime) -> str:
    return value.isoformat()

@serialize.register
def _(value: Decimal) -> str:
    return f"{value:.2f}"
```

## 26. Async/Await — only for I/O

`async` for real I/O only. Not for CPU-bound work (use `concurrent.futures.ProcessPoolExecutor` there).

```python
# BAD: async without reason — adds overhead
async def calculate_tax(amount: Decimal) -> Decimal:
    return amount * Decimal("0.20")  # Pure calculation, async not needed


# GOOD: async for real I/O
async def fetch_user(user_id: int, session: AsyncSession) -> User | None:
    """Load user from the database."""
    result = await session.execute(
        select(UserModel).where(UserModel.id == user_id)
    )
    return result.scalar_one_or_none()


# GOOD: sync for computations
def calculate_tax(amount: Decimal) -> Decimal:
    """Tax calculation — pure function, no I/O."""
    return amount * Decimal("0.20")
```

**Parallel I/O — gather/TaskGroup:**
```python
# BAD: Sequential requests (slow)
async def get_dashboard(user_id: int) -> Dashboard:
    user = await fetch_user(user_id)
    orders = await fetch_orders(user_id)    # Waits for user!
    balance = await fetch_balance(user_id)  # Waits for orders!


# GOOD: asyncio.gather — parallel
async def get_dashboard(user_id: int) -> Dashboard:
    user, orders, balance = await asyncio.gather(
        fetch_user(user_id),
        fetch_orders(user_id),
        fetch_balance(user_id),
    )
    return Dashboard(user=user, orders=orders, balance=balance)


# GOOD: TaskGroup (Python 3.11+) — structured concurrency
async def get_dashboard(user_id: int) -> Dashboard:
    async with asyncio.TaskGroup() as tg:
        user_task = tg.create_task(fetch_user(user_id))
        orders_task = tg.create_task(fetch_orders(user_id))
        balance_task = tg.create_task(fetch_balance(user_id))
    # If any task fails — all others are cancelled automatically
    return Dashboard(
        user=user_task.result(),
        orders=orders_task.result(),
        balance=balance_task.result(),
    )
```

<!-- /section:idiom -->

---

<!-- section:fastapi -->

# Part 8: FastAPI Patterns

## 27. APIRouter — modular structure

All endpoints go into routers. `main.py` — assembly only.

```python
# BAD: Everything in main.py — 500 lines of endpoints
app = FastAPI()

@app.get("/users/{user_id}")
async def get_user(user_id: int): ...

@app.post("/users")
async def create_user(user: UserCreate): ...
# ... 50 more endpoints


# GOOD: Routers per domain
# src/myservice/api/users.py
from fastapi import APIRouter, Depends, status

router = APIRouter(prefix="/users", tags=["users"])

@router.get("/{user_id}", response_model=UserResponse)
async def get_user(
    user_id: int,
    service: Annotated[UserService, Depends(get_user_service)],
) -> UserResponse:
    """Get user by ID."""
    return await service.get_by_id(user_id)


# src/myservice/api/__init__.py
from fastapi import FastAPI
from myservice.api import users, orders, payments

def create_app() -> FastAPI:
    app = FastAPI(title="My Service", lifespan=lifespan)
    app.include_router(users.router)
    app.include_router(orders.router)
    app.include_router(payments.router)
    return app
```

## 28. Pydantic v2 — validation at the boundary

```python
from pydantic import BaseModel, Field, field_validator, model_validator, computed_field
from typing import Self

class OrderItemCreate(BaseModel):
    """Order line item."""
    product_id: int = Field(..., gt=0)
    quantity: int = Field(..., gt=0, le=10000)
    price: Decimal = Field(..., gt=0, decimal_places=2)


class OrderCreate(BaseModel):
    """Data for creating an order."""
    customer_id: int = Field(..., gt=0, description="Customer ID")
    items: list[OrderItemCreate] = Field(..., min_length=1)
    discount_percent: float = Field(default=0, ge=0, le=100)
    comment: str | None = Field(default=None, max_length=500)

    @field_validator("items")
    @classmethod
    def validate_unique_products(cls, v: list[OrderItemCreate]) -> list[OrderItemCreate]:
        """Uniqueness of products in the order."""
        product_ids = [item.product_id for item in v]
        if len(product_ids) != len(set(product_ids)):
            raise ValueError("Duplicate products in the order")
        return v

    @model_validator(mode="after")
    def validate_total_limit(self) -> Self:
        """Order cannot exceed the limit."""
        total = sum(item.price * item.quantity for item in self.items)
        if total > Decimal("1000000"):
            raise ValueError("Order exceeds the 1M limit")
        return self

    @computed_field
    @property
    def total_items(self) -> int:
        """Total number of line items."""
        return len(self.items)
```

## 29. Depends() — DI in FastAPI

```python
from typing import Annotated
from fastapi import Depends
from sqlalchemy.ext.asyncio import AsyncSession

async def get_db() -> AsyncIterator[AsyncSession]:
    """DB session with automatic closing."""
    async with async_session_factory() as session:
        yield session

# Type alias for reuse
DbSession = Annotated[AsyncSession, Depends(get_db)]


async def get_user_service(db: DbSession) -> UserService:
    """User service with injected session."""
    return UserService(repo=UserRepository(db))

UserServiceDep = Annotated[UserService, Depends(get_user_service)]


@router.get("/{user_id}", response_model=UserResponse)
async def get_user(user_id: int, service: UserServiceDep) -> UserResponse:
    """Dependencies are injected automatically."""
    return await service.get_by_id(user_id)
```

## 30. Response Models, Status Codes, Validation

```python
from fastapi import status, Query, Path

class UserResponse(BaseModel):
    """Response with user data."""
    id: int
    email: str
    display_name: str
    created_at: datetime
    model_config = ConfigDict(from_attributes=True)  # allows ORM objects

class UserListResponse(BaseModel):
    """Paginated list."""
    items: list[UserResponse]
    total: int
    page: int
    page_size: int


@router.post(
    "/",
    response_model=UserResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create user",
)
async def create_user(body: UserCreate, service: UserServiceDep) -> UserResponse:
    """Creates a new user and returns their data."""
    return await service.create(body)


@router.get("/", response_model=UserListResponse)
async def list_users(
    page: Annotated[int, Query(ge=1, le=1000)] = 1,
    page_size: Annotated[int, Query(ge=1, le=100)] = 20,
    *,
    service: UserServiceDep,
) -> UserListResponse:
    """Paginated user list."""
    return await service.list(page=page, page_size=page_size)


@router.get("/{user_id}", response_model=UserResponse)
async def get_user(
    user_id: Annotated[int, Path(gt=0, description="User ID")],
    service: UserServiceDep,
) -> UserResponse:
    """Get user by ID."""
    return await service.get_by_id(user_id)
```

## 31. Global Exception Handler

Unified error response format.

```python
from fastapi import Request
from fastapi.responses import JSONResponse


class AppError(Exception):
    """Base application error with HTTP status."""
    def __init__(self, message: str, code: str, status_code: int = 400) -> None:
        self.message = message
        self.code = code
        self.status_code = status_code

class NotFoundError(AppError):
    def __init__(self, entity: str, entity_id: int | str) -> None:
        super().__init__(
            message=f"{entity} with id={entity_id} not found",
            code="NOT_FOUND",
            status_code=404,
        )

class ConflictError(AppError):
    def __init__(self, message: str) -> None:
        super().__init__(message=message, code="CONFLICT", status_code=409)


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(AppError)
    async def app_error_handler(request: Request, exc: AppError) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status_code,
            content={"code": exc.code, "message": exc.message},
        )


# Service raises typed exceptions
async def get_user(self, user_id: int) -> User:
    user = await self.repo.find_by_id(user_id)
    if user is None:
        raise NotFoundError("User", user_id)
    return user
```

## 32. Lifespan for startup/shutdown

`lifespan` context manager. `on_event` is deprecated.

```python
from contextlib import asynccontextmanager
from collections.abc import AsyncIterator

@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Application lifecycle."""
    # Startup
    engine = create_async_engine(settings.database_url)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    app.state.db_engine = engine
    app.state.session_factory = session_factory
    logger.info("app_started", database=settings.database_url)

    yield  # application is running

    # Shutdown
    await engine.dispose()
    logger.info("app_stopped")


app = FastAPI(title="My Service", lifespan=lifespan)
```

## 33. BackgroundTasks — lightweight background tasks

```python
from fastapi import BackgroundTasks

@router.post("/orders", response_model=OrderResponse, status_code=status.HTTP_201_CREATED)
async def create_order(
    body: OrderCreate,
    background_tasks: BackgroundTasks,
    service: Annotated[OrderService, Depends(get_order_service)],
) -> OrderResponse:
    """Create order. Email is sent in the background after the response."""
    order = await service.create(body)
    background_tasks.add_task(send_confirmation_email, order.id, order.customer_email)
    return order


async def send_confirmation_email(order_id: int, email: str) -> None:
    """Background send. Do NOT re-raise — background must not break the main flow."""
    try:
        await email_service.send(to=email, template="order_confirmation", order_id=order_id)
    except Exception:
        logger.exception("email_send_failed", order_id=order_id, email=email)
```

⚠️ For heavy tasks (minutes/hours) — Celery, RQ, Dramatiq. BackgroundTasks for second-scale operations.

## 34. Middleware — cross-endpoint logic

CORS, logging, authorization — via middleware, not in every handler.

```python
from fastapi.middleware.cors import CORSMiddleware
from starlette.middleware.base import BaseHTTPMiddleware

# Standard CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class RequestLoggingMiddleware(BaseHTTPMiddleware):
    """Log all HTTP requests with execution time."""

    async def dispatch(self, request: Request, call_next):
        start = time.perf_counter()
        response = await call_next(request)
        elapsed_ms = (time.perf_counter() - start) * 1000

        logger.info(
            "http_request",
            method=request.method,
            path=request.url.path,
            status=response.status_code,
            elapsed_ms=round(elapsed_ms, 2),
        )
        return response


app.add_middleware(RequestLoggingMiddleware)
```

## 35. Settings via pydantic-settings

```python
from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import Field, SecretStr

class Settings(BaseSettings):
    """Application configuration. Read from env + .env file."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
    )

    # Required
    database_url: str = Field(..., description="Database connection URL")
    secret_key: SecretStr = Field(..., description="Secret key for JWT")

    # With defaults
    debug: bool = Field(default=False)
    app_name: str = Field(default="My Service")
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"] = "INFO"

    redis_url: str = Field(default="redis://localhost:6379/0")
    cors_origins: list[str] = Field(default_factory=lambda: ["http://localhost:3000"])


from functools import cache

@cache
def get_settings() -> Settings:
    """Settings singleton."""
    return Settings()


@router.get("/health")
async def health(settings: Annotated[Settings, Depends(get_settings)]) -> dict[str, object]:
    return {"app": settings.app_name, "debug": settings.debug}
```

<!-- /section:fastapi -->

---

<!-- section:concurrency -->

# Part 9: Async Concurrency & FastAPI Performance

Critical rules for production FastAPI. Most prod bugs are not in business logic but in incorrect handling of the event loop, threadpool, connection pools, and cancellation.

## 36. Event Loop Fundamentals

FastAPI/uvicorn = **one process = one event loop = one thread**. Any blocking operation inside `async def` halts **all** request processing until it completes.

```
Thread 1 (Event Loop):
  ├─ request 1: await db.fetch()       ← await yields the loop
  ├─ request 2: await http.get()       ← can run in parallel
  ├─ request 3: time.sleep(5)          ← ❌ BLOCKS ALL others for 5 seconds!
  └─ request 4: waiting... waiting... waiting...
```

**What blocks the loop:**
- `time.sleep()` instead of `asyncio.sleep()`
- `requests.get()` instead of `httpx.AsyncClient.get()`
- `psycopg2` (sync) instead of `asyncpg` or async SQLAlchemy
- `redis-py` sync API instead of `redis.asyncio`
- `boto3` (sync) instead of `aioboto3`
- `pymongo` (sync) instead of `motor`
- File reads/writes via `open()` without `aiofiles` or `asyncio.to_thread`
- CPU-bound > 10ms (regex on large strings, gigabyte JSON, ML inference)
- `subprocess.run()` instead of `asyncio.create_subprocess_exec`
- Heavy `json.dumps()` / `json.loads()` on large structures

**Detecting blockage:**
```python
# Event loop lag metric in production
async def measure_loop_lag() -> None:
    """If > 100ms — there is a blockage."""
    while True:
        start = time.perf_counter()
        await asyncio.sleep(0)
        lag_ms = (time.perf_counter() - start) * 1000
        if lag_ms > 100:
            logger.warning("event_loop_lag_high", lag_ms=lag_ms)
        await asyncio.sleep(1)


# uvloop — replacement for the built-in event loop, 2-4x faster
# uvicorn --loop uvloop main:app
```

## 37. Sync vs Async Endpoints

FastAPI runs them **differently**:

| Endpoint | Where it runs | When to use |
|---|---|---|
| `async def` | On the event loop | Async I/O (asyncpg, httpx, redis.asyncio) |
| `def` (sync) | In anyio threadpool (40 threads default) | Sync libraries that would otherwise block the loop |

```python
# GOOD: async def with async libraries
@router.get("/users/{user_id}")
async def get_user(user_id: int, db: AsyncSession = Depends(get_db)) -> UserResponse:
    user = await db.get(User, user_id)
    return UserResponse.model_validate(user)


# GOOD: sync def when the dependency is sync
@router.get("/legacy/users/{user_id}")
def get_user_legacy(user_id: int, db: Session = Depends(get_sync_db)) -> UserResponse:
    """psycopg2 sync — let FastAPI run it in the threadpool."""
    user = db.query(User).filter_by(id=user_id).one()
    return UserResponse.model_validate(user)


# CRITICAL BAD: sync lib inside async def — blocks the event loop!
@router.get("/users/{user_id}")
async def get_user_bad(user_id: int) -> UserResponse:
    user = sync_db.query(User).filter_by(id=user_id).one()  # ❌ BLOCKS the entire loop!
    return UserResponse.model_validate(user)
```

**Rule:** if an endpoint has **even one blocking call**, either make the whole endpoint `def`, or wrap blocking pieces in `asyncio.to_thread()`.

## 38. Threadpool Capacity

AnyIO threadpool default = **40 threads**. Each sync endpoint occupies 1 thread for its duration. If all 40 are busy — sync requests queue up.

```python
from anyio import to_thread

@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    # Increase limit if there are many sync endpoints / DB sessions
    limiter = to_thread.current_default_thread_limiter()
    limiter.total_tokens = 100  # default 40
    yield
```

**How many to set:**
- Pure async service → leave as-is (40 is plenty).
- Mixed sync/async → `max(40, average concurrent sync operations × 2)`.
- If sync endpoint holds a DB connection → `total_tokens >= db_pool.max_size`.

⚠️ Too high a limit → more memory for thread stacks (1MB × N) and context switches.

## 39. Async Libraries — replacement map

| Sync (do NOT use inside async def) | Async replacement |
|---|---|
| `requests` | **`httpx.AsyncClient`** |
| `urllib.request` | **`httpx.AsyncClient`** or `aiohttp` |
| `psycopg2` / `psycopg` (sync) | **`asyncpg`** or `psycopg[async]` |
| SQLAlchemy 1.x sync | **SQLAlchemy 2.x async** + asyncpg |
| `redis-py` sync API | **`redis.asyncio`** (same package) |
| `pymongo` | **`motor`** or `pymongo` async (3.13+) |
| `boto3` | **`aioboto3`** or `aiobotocore` |
| `kafka-python` | **`aiokafka`** |
| `pika` (RabbitMQ) | **`aio-pika`** |
| `elasticsearch` (sync) | **`elasticsearch[async]`** |
| File I/O (`open()`) | **`aiofiles`** or `asyncio.to_thread(path.read_bytes)` |
| `subprocess.run` | **`asyncio.create_subprocess_exec`** |
| `time.sleep` | **`asyncio.sleep`** |
| `socket` | **`asyncio.open_connection`** |
| `imaplib` / `smtplib` | **`aioimaplib`** / `aiosmtplib` |

## 40. asyncio.to_thread — wrapper for sync

If there is no async replacement — wrap in threadpool.

```python
import asyncio

# GOOD: asyncio.to_thread (Python 3.9+) — simple case
async def get_user_credit_score(user_id: int) -> int:
    """Sync lib credit_check_sdk has no async version."""
    return await asyncio.to_thread(credit_check_sdk.get_score, user_id)


# Type-safe wrapper
from typing import ParamSpec, TypeVar
from collections.abc import Callable

P = ParamSpec("P")
T = TypeVar("T")

async def run_blocking(func: Callable[P, T], *args: P.args, **kwargs: P.kwargs) -> T:
    """Runs a blocking function in the threadpool."""
    return await asyncio.to_thread(func, *args, **kwargs)
```

**When `to_thread` does NOT help:**
- CPU-bound on pure Python — GIL is not released, still blocks.
- Extremely fast calls (< 1ms) — switch overhead outweighs the benefit.

## 41. CPU-bound — ProcessPoolExecutor

```python
from concurrent.futures import ProcessPoolExecutor

@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    app.state.cpu_pool = ProcessPoolExecutor(max_workers=4)
    yield
    app.state.cpu_pool.shutdown(wait=True)


@router.post("/render-pdf")
async def render_pdf(data: RenderRequest, request: Request) -> bytes:
    """PDF rendering — CPU-bound, separate process."""
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(
        request.app.state.cpu_pool,
        render_pdf_sync,
        data.template_id,
        data.payload,
    )
```

⚠️ Arguments must be picklable. Heavy computations lasting more than a second are better off in Celery or a separate microservice.

## 42. Singleton Clients via Lifespan

Do not create a client on every request. That means an HTTPS handshake each time and no connection reuse.

```python
# BAD: new client on every request
@router.get("/external-data")
async def get_data() -> dict:
    async with httpx.AsyncClient() as client:  # ❌
        response = await client.get("https://api.example.com/data")
        return response.json()


# GOOD: singleton via lifespan
@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    app.state.http_client = httpx.AsyncClient(
        timeout=httpx.Timeout(connect=5.0, read=30.0, write=10.0, pool=5.0),
        limits=httpx.Limits(
            max_connections=100,
            max_keepalive_connections=20,
            keepalive_expiry=30.0,
        ),
    )
    app.state.db_pool = await asyncpg.create_pool(
        dsn=settings.database_url,
        min_size=10,
        max_size=50,
        max_inactive_connection_lifetime=300,
        command_timeout=30,
    )
    app.state.redis = redis.asyncio.from_url(
        settings.redis_url,
        max_connections=50,
        decode_responses=True,
    )

    yield

    # Graceful shutdown — reverse order
    await app.state.http_client.aclose()
    await app.state.redis.aclose()
    await app.state.db_pool.close()


def get_http_client(request: Request) -> httpx.AsyncClient:
    return request.app.state.http_client

HttpClient = Annotated[httpx.AsyncClient, Depends(get_http_client)]


@router.get("/external-data")
async def get_data(client: HttpClient) -> dict:
    response = await client.get("https://api.example.com/data")
    return response.json()
```

**Multiple clients for different services:**
```python
# Different timeouts/limits for different backends
app.state.payment_client = httpx.AsyncClient(
    base_url="https://gateway.example.com",
    timeout=httpx.Timeout(60.0, connect=10.0),  # payment gateways are slow
    limits=httpx.Limits(max_connections=20),
)

app.state.notification_client = httpx.AsyncClient(
    base_url="https://notify.internal",
    timeout=httpx.Timeout(5.0),
    limits=httpx.Limits(max_connections=50),
)
```

## 43. Timeouts — on everything

Any network operation without a timeout = potentially hanging forever. Hanging requests exhaust the connection pool, then the threadpool, then the entire service.

```python
# BAD: no timeout — can hang for hours
async with httpx.AsyncClient() as client:
    response = await client.get("https://slow-api.example.com")  # ❌


# GOOD: explicit timeout with phase breakdown
async with httpx.AsyncClient(
    timeout=httpx.Timeout(
        connect=5.0,    # TCP+TLS handshake
        read=30.0,      # reading after connect
        write=10.0,     # writing (for POST)
        pool=5.0,       # waiting for a free connection in the pool
    ),
) as client:
    response = await client.get("https://api.example.com")


# asyncio.timeout() — Python 3.11+, global timeout for a block
async def fetch_with_budget(url: str, budget_seconds: float = 10.0) -> str:
    try:
        async with asyncio.timeout(budget_seconds):
            response = await client.get(url)
            return response.text
    except TimeoutError:
        logger.warning("fetch_timeout", url=url, budget=budget_seconds)
        raise


# Before 3.11 — asyncio.wait_for
result = await asyncio.wait_for(slow_operation(), timeout=10.0)
```

**DB query timeouts** — mandatory. One heavy query without a timeout will kill the entire pool.

```python
# asyncpg
pool = await asyncpg.create_pool(
    dsn=settings.database_url,
    command_timeout=30,
)

# SQLAlchemy 2.x
engine = create_async_engine(
    settings.database_url,
    connect_args={
        "command_timeout": 30,
        "server_settings": {"statement_timeout": "30000"},  # PG ms
    },
)
```

**Default budgets:**

| Operation | Connect | Read | Total |
|---|---|---|---|
| Internal LAN service | 1s | 5s | 10s |
| External API | 5s | 10s | 30s |
| Payment gateway | 10s | 30s | 60s |
| LLM API | 5s | 60s | 120s |
| DB query (OLTP) | 1s | 5s | 10s |
| DB query (report) | 1s | 30s | 60s |
| Redis | 0.5s | 1s | 2s |

An endpoint's **total budget** must be less than the sum of its internal timeouts.

## 44. Connection Pools

Pool size **must not** equal the number of CPUs. In async — pool size = expected number of concurrent operations.

```python
# Database (asyncpg)
pool = await asyncpg.create_pool(
    dsn=DATABASE_URL,
    min_size=10,
    max_size=50,
    max_inactive_connection_lifetime=300,
    max_queries=50000,
    command_timeout=30,
)


# SQLAlchemy 2.x async
engine = create_async_engine(
    DATABASE_URL,
    pool_size=20,
    max_overflow=10,
    pool_timeout=30,
    pool_recycle=3600,
    pool_pre_ping=True,
)


# Redis
redis_client = redis.asyncio.Redis(
    host=REDIS_HOST,
    max_connections=50,
    socket_timeout=2.0,
    socket_connect_timeout=2.0,
    health_check_interval=30,
    retry_on_timeout=True,
)


# httpx
client = httpx.AsyncClient(
    limits=httpx.Limits(
        max_connections=100,
        max_keepalive_connections=20,
        keepalive_expiry=30.0,
    ),
)
```

**Pool size formula:**
```
DB pool size = (peak concurrent requests × queries per request) / target query duration

Example: 200 RPS, 10ms query, 2 queries per request → 4 on average → 5x headroom → pool_size = 20
```

**Metric:** `pool.in_use / pool.size`. Consistently > 80% — increase. < 10% — decrease.

## 45. asyncio.gather vs TaskGroup

```python
# gather — independent tasks, partial failures OK
async def get_dashboard(user_id: int) -> Dashboard:
    user, orders, balance = await asyncio.gather(
        fetch_user(user_id),
        fetch_orders(user_id),
        fetch_balance(user_id),
    )
    return Dashboard(user=user, orders=orders, balance=balance)


# Resilience to partial failures
async def aggregate_stats(user_ids: list[int]) -> list[UserStats | None]:
    """If one user-service call fails — other results are preserved."""
    results = await asyncio.gather(
        *(fetch_stats(uid) for uid in user_ids),
        return_exceptions=True,
    )
    return [r if not isinstance(r, Exception) else None for r in results]


# TaskGroup (3.11+) — structured concurrency
# If any task fails — all others are cancelled
async def get_dashboard(user_id: int) -> Dashboard:
    async with asyncio.TaskGroup() as tg:
        user_task = tg.create_task(fetch_user(user_id))
        orders_task = tg.create_task(fetch_orders(user_id))
        balance_task = tg.create_task(fetch_balance(user_id))

    return Dashboard(
        user=user_task.result(),
        orders=orders_task.result(),
        balance=balance_task.result(),
    )


# Handling group errors — except*
try:
    dashboard = await get_dashboard(user_id)
except* DBError as eg:
    for err in eg.exceptions:
        logger.exception("db_error", error=str(err))
    raise
except* HTTPError as eg:
    logger.warning("upstream_failures", count=len(eg.exceptions))
    raise
```

**Choosing rule:**
- `gather` — independent tasks, partial failures acceptable.
- `TaskGroup` — related tasks, all-or-nothing semantics.
- `as_completed` — process results in order of completion.

⚠️ Without `return_exceptions=True` in gather: the first exception interrupts gather, **but other tasks keep running in the background** (may leave resources open).

## 46. Semaphore — concurrency limiting

```python
# Limit parallel calls to an external API
class RateLimitedClient:
    def __init__(self, client: httpx.AsyncClient, max_concurrent: int = 10) -> None:
        self._client = client
        self._semaphore = asyncio.Semaphore(max_concurrent)

    async def fetch(self, url: str) -> dict:
        async with self._semaphore:
            response = await self._client.get(url)
            return response.json()


# Parallel batch processing with a limit
async def process_batch(items: list[Item], max_concurrent: int = 20) -> list[Result]:
    """No more than 20 concurrent operations at a time."""
    semaphore = asyncio.Semaphore(max_concurrent)

    async def process_one(item: Item) -> Result:
        async with semaphore:
            return await heavy_processing(item)

    return await asyncio.gather(*(process_one(item) for item in items))
```

## 47. Cancellation — CancelledError

In asyncio, when a client closes a connection, FastAPI cancels the task → `asyncio.CancelledError`.

```python
# CRITICAL BAD: swallows CancelledError → task is not cancelled
async def process_request():
    try:
        await long_operation()
    except Exception:  # ❌ catches CancelledError too
        logger.exception("error")
        return {"status": "error"}


# GOOD: explicitly re-raise CancelledError
async def process_request():
    try:
        await long_operation()
    except asyncio.CancelledError:
        logger.info("request_cancelled")
        raise  # REQUIRED — otherwise the task will not stop
    except SomeBusinessError:
        logger.exception("business_error")
        return {"status": "error"}


# GOOD: cleanup on cancellation via try/finally
async def process_with_cleanup():
    resource = await acquire_resource()
    try:
        return await use_resource(resource)
    finally:
        await resource.release()  # runs even on CancelledError


# asyncio.shield() — protection from cancellation for critical operations
async def transfer_money(from_id: int, to_id: int, amount: Decimal) -> None:
    """Money transfer — cannot cancel once the commit has started."""
    async with db_pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("UPDATE accounts SET balance = balance - $1 WHERE id = $2",
                               amount, from_id)
            await conn.execute("UPDATE accounts SET balance = balance + $1 WHERE id = $2",
                               amount, to_id)
            # client disconnected — must not cancel commit
            await asyncio.shield(record_audit_log(from_id, to_id, amount))
```

## 48. Fire-and-forget tasks done right

`asyncio.create_task()` without holding a reference → task can be garbage collected.

```python
# BAD: task can be lost
async def notify():
    asyncio.create_task(send_email())  # ❌ no reference → possible GC


# GOOD: TaskManager for fire-and-forget
class TaskManager:
    """Manager for fire-and-forget tasks — guaranteed lifecycle."""

    def __init__(self) -> None:
        self._tasks: set[asyncio.Task] = set()

    def spawn(self, coro: Awaitable[None]) -> asyncio.Task:
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return task

    async def shutdown(self, timeout: float = 30.0) -> None:
        if not self._tasks:
            return
        try:
            async with asyncio.timeout(timeout):
                await asyncio.gather(*self._tasks, return_exceptions=True)
        except TimeoutError:
            for task in self._tasks:
                if not task.done():
                    task.cancel()


# Usage
@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    app.state.tasks = TaskManager()
    yield
    await app.state.tasks.shutdown()


@router.post("/orders")
async def create_order(body: OrderCreate, request: Request) -> OrderResponse:
    order = await order_service.create(body)
    request.app.state.tasks.spawn(send_confirmation_email(order))
    return order
```

⚠️ **BackgroundTasks vs fire-and-forget vs Celery:**
- `BackgroundTasks` — after the response, in the same process. **If the process dies — the task is lost**.
- `create_task` + TaskManager — same, but not tied to a request.
- **Celery / Dramatiq / Arq / Taskiq** — for reliability (persistent queue, retries). Use for critical tasks (confirmation emails, billing).

## 49. Database in Async — correct usage

```python
# session.py
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

engine = create_async_engine(
    settings.database_url,
    pool_size=20,
    max_overflow=10,
    pool_pre_ping=True,
    pool_recycle=3600,
)

session_factory = async_sessionmaker(
    bind=engine,
    expire_on_commit=False,  # critical for async
    autoflush=False,
)


async def get_db() -> AsyncIterator[AsyncSession]:
    async with session_factory() as session:
        try:
            yield session
        except Exception:
            await session.rollback()
            raise
```

**Lazy loading is forbidden** — causes `MissingGreenlet` or hidden blocking.

```python
# BAD: lazy loading in async
user = await session.get(User, user_id)
print(user.orders)  # ❌ implicit IO in async context


# GOOD: eager loading via selectinload (separate SELECT)
from sqlalchemy.orm import selectinload, joinedload

user = await session.scalar(
    select(User)
    .options(selectinload(User.orders))
    .where(User.id == user_id),
)


# joinedload — JOIN for one-to-one
user = await session.scalar(
    select(User)
    .options(joinedload(User.profile))
    .where(User.id == user_id),
)
```

**Eager loading strategies:**
- `selectinload` — separate `SELECT WHERE id IN (...)`. Good for one-to-many.
- `joinedload` — JOIN. For one-to-one and small one-to-many.

**Transactions:**
```python
async def transfer(session: AsyncSession, from_id: int, to_id: int, amount: Decimal) -> None:
    async with session.begin():
        from_account = await session.get(Account, from_id, with_for_update=True)
        to_account = await session.get(Account, to_id, with_for_update=True)

        if from_account.balance < amount:
            raise InsufficientFundsError()

        from_account.balance -= amount
        to_account.balance += amount
    # commit on exit, rollback on exception
```

**Bulk operations:**
```python
# 10K records
await session.execute(insert(OrderItem), [{...} for p in products])
await session.commit()


# Upsert (PostgreSQL)
from sqlalchemy.dialects.postgresql import insert as pg_insert

stmt = pg_insert(User).values(rows).on_conflict_do_update(
    index_elements=["email"],
    set_={"updated_at": func.now()},
)
await session.execute(stmt)
```

## 50. Race Conditions — async ≠ no race

Async — concurrent, not parallel (single thread). Switches can occur between `await` points → race conditions.

```python
# BAD: classic check-then-act race
class CounterService:
    def __init__(self) -> None:
        self._count = 0

    async def increment_if_below(self, limit: int) -> None:
        if self._count < limit:        # ← switch possible here
            await asyncio.sleep(0)     # ← between check and act
            self._count += 1           # ← race! may exceed limit


# GOOD: asyncio.Lock for critical section
class CounterService:
    def __init__(self) -> None:
        self._count = 0
        self._lock = asyncio.Lock()

    async def increment_if_below(self, limit: int) -> bool:
        async with self._lock:
            if self._count >= limit:
                return False
            await asyncio.sleep(0)
            self._count += 1
            return True
```

**Distributed locks** — for multi-pod via Redis:
```python
async def acquire_lock(redis: Redis, key: str, ttl: int = 30) -> str | None:
    """Returns token if lock was acquired."""
    token = secrets.token_hex(16)
    acquired = await redis.set(key, token, nx=True, ex=ttl)
    return token if acquired else None


async def release_lock(redis: Redis, key: str, token: str) -> None:
    """Release only if token matches (protection against foreign lock)."""
    lua = """
    if redis.call('GET', KEYS[1]) == ARGV[1] then
        return redis.call('DEL', KEYS[1])
    else
        return 0
    end
    """
    await redis.eval(lua, 1, key, token)
```

**Pessimistic vs Optimistic vs Idempotency keys:**

| Pattern | When |
|---|---|
| Pessimistic (`SELECT FOR UPDATE`) | High conflict frequency, short transactions |
| Optimistic (version column) | Rare conflicts, don't block readers |
| Idempotency keys | External APIs, safe re-try |

```python
# Idempotency via request key
@router.post("/payments")
async def create_payment(
    body: PaymentCreate,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
    db: DbSession,
    redis: RedisDep,
) -> PaymentResponse:
    """If key already processed — return previous result."""
    cache_key = f"idempotency:payment:{idempotency_key}"
    if cached := await redis.get(cache_key):
        return PaymentResponse.model_validate_json(cached)

    payment = await payment_service.create(db, body)
    response = PaymentResponse.model_validate(payment)
    await redis.set(cache_key, response.model_dump_json(), ex=86400)
    return response
```

## 51. Context Propagation — contextvars

`threading.local` does not work in async — one thread handles many coroutines.

```python
# BAD: threading.local — all coroutines see the same value
import threading
_request_context = threading.local()


# GOOD: contextvars — propagates through await, isolated per-task
from contextvars import ContextVar

request_id_var: ContextVar[str | None] = ContextVar("request_id", default=None)
user_id_var: ContextVar[int | None] = ContextVar("user_id", default=None)
tenant_id_var: ContextVar[str | None] = ContextVar("tenant_id", default=None)


# Middleware sets request_id for the entire request
class RequestIdMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        request_id = request.headers.get("X-Request-ID") or str(uuid4())
        token = request_id_var.set(request_id)
        try:
            response = await call_next(request)
            response.headers["X-Request-ID"] = request_id
            return response
        finally:
            request_id_var.reset(token)


# structlog with contextvars — automatically added to every log entry
import structlog

structlog.configure(
    processors=[
        structlog.contextvars.merge_contextvars,  # pulls in all ContextVars
        structlog.processors.add_log_level,
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.JSONRenderer(),
    ],
    cache_logger_on_first_use=True,  # significant perf bonus
)


# Bind context in middleware
async def dispatch(self, request: Request, call_next):
    structlog.contextvars.clear_contextvars()
    structlog.contextvars.bind_contextvars(
        request_id=request.headers.get("X-Request-ID") or str(uuid4()),
        path=request.url.path,
        method=request.method,
    )
    # all logs in this request automatically get request_id, path, method
```

## 52. Logging without blocking

`FileHandler.emit()` makes a sync `write()`. On busy disks / network filesystems — blocks the event loop.

```python
# BAD: writing to file blocks the loop
logging.basicConfig(handlers=[logging.FileHandler("app.log")])


# GOOD: QueueHandler + QueueListener — asynchronous writing
from logging.handlers import QueueHandler, QueueListener
from queue import Queue

log_queue: Queue = Queue(maxsize=10000)
queue_handler = QueueHandler(log_queue)

file_handler = logging.FileHandler("app.log")
listener = QueueListener(log_queue, file_handler, respect_handler_level=True)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    listener.start()
    yield
    listener.stop()


root_logger = logging.getLogger()
root_logger.addHandler(queue_handler)
```

⚠️ In Docker/k8s **stdout** is usually sufficient. FileHandler is not needed — the orchestrator handles it.

## 53. Streaming Responses

`return huge_dict` loads 1GB into memory. For large data — use `StreamingResponse`.

```python
# BAD: materialises 1M records in memory
@router.get("/export/orders.csv")
async def export_csv(db: DbSession) -> Response:
    orders = await db.execute(select(Order))  # ❌ everything in memory
    csv_text = render_csv(orders.scalars().all())
    return Response(csv_text, media_type="text/csv")


# GOOD: streaming via async generator
from fastapi.responses import StreamingResponse

async def stream_orders_csv(db: AsyncSession) -> AsyncIterator[str]:
    """Streams CSV rows as they are fetched from the DB."""
    yield "id,customer_id,total,created_at\n"

    stmt = select(Order).execution_options(yield_per=1000)
    async for order in await db.stream_scalars(stmt):
        yield f"{order.id},{order.customer_id},{order.total},{order.created_at.isoformat()}\n"


@router.get("/export/orders.csv")
async def export_csv(db: DbSession) -> StreamingResponse:
    return StreamingResponse(
        stream_orders_csv(db),
        media_type="text/csv",
        headers={"Content-Disposition": "attachment; filename=orders.csv"},
    )


# Upload large files — in chunks
import aiofiles

@router.post("/upload")
async def upload(file: UploadFile) -> dict:
    """Save file in streaming fashion."""
    target = Path(f"/uploads/{file.filename}")
    async with aiofiles.open(target, "wb") as out:
        while chunk := await file.read(64 * 1024):  # 64KB
            await out.write(chunk)
    return {"filename": file.filename, "size": target.stat().st_size}
```

## 54. Workers & Deployment

| Profile | Workers | Reasoning |
|---|---|---|
| **I/O-bound** (90% async) | 1-2 per pod, scale via replicas | Async scales within a single worker |
| **Mix sync/async** | `cpu_count` | Sync endpoints in threadpool block the worker |
| **CPU-bound** | `cpu_count + 1` | Use ProcessPool internally or Celery |

```bash
# Production: gunicorn + uvicorn workers
gunicorn myservice.api:app \
    --worker-class uvicorn.workers.UvicornWorker \
    --workers 4 \
    --bind 0.0.0.0:8000 \
    --timeout 60 \
    --graceful-timeout 30 \
    --keep-alive 5 \
    --max-requests 10000 \
    --max-requests-jitter 1000


# Direct uvicorn (container-based)
uvicorn myservice.api:app \
    --host 0.0.0.0 \
    --port 8000 \
    --workers 2 \
    --loop uvloop \
    --http httptools \
    --limit-concurrency 1000 \
    --timeout-keep-alive 5
```

`--max-requests` is mandatory — recreate the worker after N requests to avoid memory leaks.

**Graceful shutdown** — on `SIGTERM`:
1. Stop accepting new connections.
2. Wait for in-flight requests (with a limit).
3. Close pools in reverse order.

```python
@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    # Startup
    app.state.http_client = httpx.AsyncClient(...)
    app.state.db_pool = await asyncpg.create_pool(...)
    app.state.tasks = TaskManager()
    logger.info("app_started")

    yield

    # Shutdown — reverse order
    logger.info("app_shutting_down")
    await app.state.tasks.shutdown(timeout=30)        # 1. wait for background tasks
    await app.state.http_client.aclose()              # 2. close HTTP
    await app.state.db_pool.close()                   # 3. close DB
    logger.info("app_stopped")
```

⚠️ In Kubernetes: `terminationGracePeriodSeconds` must be > graceful timeout.

**Health checks done right:**
```python
@router.get("/health/liveness", include_in_schema=False)
async def liveness() -> dict[str, str]:
    """Is the process alive. WITHOUT dependency checks.
    If it fails — k8s restarts the pod."""
    return {"status": "ok"}


@router.get("/health/readiness", include_in_schema=False)
async def readiness(db: DbSession, redis: RedisDep) -> dict[str, object]:
    """Ready to accept traffic. Checks dependencies.
    If it fails — k8s removes pod from service endpoints."""
    checks: dict[str, str] = {}
    overall_ok = True

    try:
        async with asyncio.timeout(2.0):
            await db.execute(text("SELECT 1"))
            checks["database"] = "ok"
    except Exception as exc:
        checks["database"] = f"error: {exc}"
        overall_ok = False

    try:
        async with asyncio.timeout(1.0):
            await redis.ping()
            checks["redis"] = "ok"
    except Exception as exc:
        checks["redis"] = f"error: {exc}"
        overall_ok = False

    if not overall_ok:
        raise HTTPException(status_code=503, detail=checks)
    return {"status": "ok", "checks": checks}
```

⚠️ **Do not make heavy checks in liveness** — causes cascading restarts when a dependency flaps.

## 55. Backpressure & Resilience

```bash
# Concurrency limit for the server
uvicorn myservice.api:app --limit-concurrency 1000
# With >1000 concurrent connections — rejects with 503
# Fast failure is better than slow thrash
```

**Rate limiting** via fastapi-limiter:
```python
from fastapi_limiter import FastAPILimiter
from fastapi_limiter.depends import RateLimiter

@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    redis_client = redis.asyncio.from_url(settings.redis_url, decode_responses=True)
    await FastAPILimiter.init(redis_client)
    yield
    await FastAPILimiter.close()


@router.post(
    "/expensive-operation",
    dependencies=[Depends(RateLimiter(times=10, seconds=60))],  # 10 req/min
)
async def expensive_op() -> dict: ...
```

**Circuit breaker** for unstable upstreams:
```python
# pip install pybreaker
import pybreaker

payment_breaker = pybreaker.CircuitBreaker(
    fail_max=5,
    reset_timeout=30,
)

@payment_breaker
async def call_payment_gateway(amount: Decimal) -> PaymentResult:
    """After 5 consecutive failures the breaker opens for 30s — fast rejections."""
    return await payment_client.charge(amount)
```

Async alternatives: `aiocircuitbreaker`, `purgatory`.

**Retries with exponential backoff:**
```python
# pip install tenacity
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type

@retry(
    stop=stop_after_attempt(3),
    wait=wait_exponential(multiplier=1, min=1, max=10),
    retry=retry_if_exception_type(httpx.HTTPError),
    reraise=True,
)
async def fetch_with_retry(url: str) -> dict:
    response = await client.get(url)
    response.raise_for_status()
    return response.json()
```

## 56. Anti-patterns Cheatsheet

```python
# ─── Sync client in async ───────────────────────────────────────
# ❌ NEVER:
async def handler():
    response = requests.get(url)            # blocks event loop

# ✅ ALWAYS:
async def handler(client: HttpClient):
    response = await client.get(url)


# ─── Creating client in handler ─────────────────────────────────
# ❌ NEVER:
async def handler():
    async with httpx.AsyncClient() as c:    # new pool every time
        ...

# ✅ ALWAYS: singleton via lifespan


# ─── Requests without timeout ───────────────────────────────────
# ❌ NEVER:
async with httpx.AsyncClient() as c:
    await c.get(url)

# ✅ ALWAYS:
async with httpx.AsyncClient(timeout=httpx.Timeout(30, connect=5)) as c:
    await c.get(url)


# ─── time.sleep ─────────────────────────────────────────────────
# ❌ NEVER:
async def retry():
    time.sleep(1)                           # blocks event loop

# ✅ ALWAYS:
async def retry():
    await asyncio.sleep(1)


# ─── except Exception without re-raising CancelledError ──────
# ❌ DANGEROUS:
try:
    await long()
except Exception:
    pass

# ✅ ALWAYS:
try:
    await long()
except asyncio.CancelledError:
    raise
except Exception:
    handle()


# ─── Lazy loading in async SQLAlchemy ───────────────────────────
# ❌ NEVER:
user = await session.get(User, uid)
print(user.orders)                          # MissingGreenlet

# ✅ ALWAYS:
user = await session.scalar(
    select(User).options(selectinload(User.orders)).where(User.id == uid)
)


# ─── threading.local in async ───────────────────────────────────
# ❌ NEVER:
_ctx = threading.local()
_ctx.user = current_user

# ✅ ALWAYS:
user_var: ContextVar[User] = ContextVar("user")
user_var.set(current_user)


# ─── fire-and-forget without holding reference ──────────────────
# ❌ NEVER:
asyncio.create_task(send_email())           # GC can kill it

# ✅ ALWAYS: TaskManager with _tasks: set[Task]


# ─── Global mutable state without Lock ──────────────────────────
# ❌ NEVER:
_counter = 0
async def inc():
    global _counter
    _counter += 1                           # race between awaits

# ✅ ALWAYS: asyncio.Lock or Redis INCR


# ─── Unbounded gather ───────────────────────────────────────────
# ❌ NEVER:
await asyncio.gather(*(fetch(u) for u in 100_000_users))  # 100K sockets

# ✅ ALWAYS: Semaphore or batch


# ─── sync session in async endpoint ─────────────────────────────
# ❌ NEVER:
@router.get("/users")
async def get_users(db: Session = Depends(get_sync_db)):
    users = db.query(User).all()            # sync IO in async!

# ✅ ALWAYS:
async def get_users(db: AsyncSession = Depends(get_async_db)):
    result = await db.execute(select(User))
    return result.scalars().all()


# ─── BackgroundTasks for critical operations ─────────────────────
# ❌ NEVER:
@router.post("/payment")
async def pay(bg: BackgroundTasks):
    bg.add_task(charge_card, ...)           # lost if the pod dies

# ✅ ALWAYS: Celery/Dramatiq/Arq with persistent queue
```

<!-- /section:concurrency -->

---

# Quick Checklist

Before submitting Python code:

**Project & Toolchain (rules 1–4):**
- [ ] `src/` layout, `pyproject.toml` single config
- [ ] Ruff (`select = ["ALL"]`), Pyright `strict=true`
- [ ] pre-commit gate configured

**Type System (rules 5–10):**
- [ ] `from __future__ import annotations` in every file
- [ ] All functions/parameters/returns are typed
- [ ] `Any` is forbidden — only with `# type: ignore[reason]`
- [ ] `Final`, `Literal`, `NewType`, `Self`, `@override` where appropriate
- [ ] `Protocol` for DI, `ABC` only for template method
- [ ] `assert_never` for exhaustive `match`
- [ ] `TYPE_CHECKING` for breaking import cycles

**Data Modeling (rules 11–13):**
- [ ] `@dataclass(frozen=True, slots=True, kw_only=True)` by default
- [ ] Pydantic — at API boundary, dataclass — internally
- [ ] StrEnum/IntEnum, no magic strings

**Errors (rules 14–18):**
- [ ] Hierarchy `AppError → DomainError/InfrastructureError → ...`
- [ ] `raise ... from exc` always — preserve the cause
- [ ] `ExceptionGroup` for a batch of errors (Python 3.11+)
- [ ] No bare `except:` without `raise` or explicit recovery
- [ ] `assert` is forbidden in production (tests only)

**Logging & I/O (rules 19–22):**
- [ ] `structlog` or stdlib `logging` with `%s` format
- [ ] `print()` — only in CLI entry points
- [ ] `pathlib.Path`, never `os.path`
- [ ] `with` for all resources, custom ones via `@contextmanager`

**Idioms (rules 23–26):**
- [ ] Comprehensions one level, nested → functions
- [ ] `match/case` for discriminated unions
- [ ] `@cache`/`@lru_cache`/`@cached_property`/`@singledispatch` where appropriate
- [ ] `async` only for real I/O, `gather`/`TaskGroup` for parallelism

**FastAPI (rules 27–35):**
- [ ] APIRouter per domain, `main.py` assembly only
- [ ] Pydantic v2: `field_validator`, `model_validator`, `computed_field`
- [ ] `Depends()` + `Annotated` type aliases for DI
- [ ] `response_model` and `status_code` explicitly
- [ ] Global `exception_handler` for unified error format
- [ ] `lifespan` for startup/shutdown (NOT `on_event`)
- [ ] `BackgroundTasks` for lightweight background tasks, Celery — for heavy ones
- [ ] CORS/logging via middleware
- [ ] `pydantic-settings` BaseSettings, not `os.getenv`

**Async Concurrency (rules 36–56):**

*Event loop integrity:*
- [ ] No sync clients (`requests`, `psycopg2`, `boto3`, `redis-py` sync) inside `async def`
- [ ] Sync libraries wrapped in `asyncio.to_thread()` or endpoint declared `def`
- [ ] CPU-bound offloaded to ProcessPool / Celery
- [ ] `time.sleep()` → `asyncio.sleep()` everywhere
- [ ] uvloop enabled in production

*Clients & Pools:*
- [ ] All clients (httpx, db, redis) — singleton via `lifespan`
- [ ] httpx.Limits configured (max_connections, keepalive)
- [ ] DB pool: `pool_size`, `max_overflow`, `pool_pre_ping=True`, `pool_recycle`
- [ ] AnyIO threadpool increased if sync endpoints are used

*Timeouts:*
- [ ] httpx.Timeout with connect/read/write/pool breakdown
- [ ] DB `command_timeout` or PG `statement_timeout`
- [ ] `asyncio.timeout()` on critical blocks
- [ ] Endpoint budget < sum of internal timeouts

*Concurrency:*
- [ ] `asyncio.gather` for independent tasks, `TaskGroup` for related
- [ ] Semaphore to limit concurrent upstream calls
- [ ] Fire-and-forget via TaskManager (with reference holding)
- [ ] `asyncio.Lock` for critical sections of in-memory state
- [ ] Distributed locks via Redis for multi-pod

*Cancellation:*
- [ ] `except asyncio.CancelledError: raise` explicitly
- [ ] `try/finally` for cleanup
- [ ] `asyncio.shield()` for atomic operations
- [ ] Never swallow `CancelledError`

*Database:*
- [ ] SQLAlchemy 2.x async + `expire_on_commit=False`
- [ ] `selectinload`/`joinedload` instead of lazy loading
- [ ] Transactions via `session.begin()` context
- [ ] Optimistic locking (version column) or idempotency keys

*Context & Logging:*
- [ ] `contextvars` for request_id, user_id, tenant_id (NOT `threading.local`)
- [ ] `structlog.contextvars.merge_contextvars` in processors
- [ ] QueueHandler when writing to file; stdout-only in Docker/k8s

*Streaming:*
- [ ] `StreamingResponse` for large responses
- [ ] `UploadFile.read(chunk_size)` for large uploads
- [ ] `aiofiles` or `to_thread` for file I/O

*Deployment:*
- [ ] `--workers` tuned to profile (I/O = fewer, CPU = more)
- [ ] `--max-requests` for worker recreation
- [ ] Graceful shutdown via lifespan (closing pools in reverse order)
- [ ] `terminationGracePeriodSeconds` in k8s > graceful timeout
- [ ] `/health/liveness` without dependencies, `/health/readiness` with checks
- [ ] `--limit-concurrency` for backpressure

*Resilience:*
- [ ] Rate limiting on heavy endpoints (fastapi-limiter)
- [ ] Circuit breaker for unstable upstreams
- [ ] Retries with exponential backoff (tenacity)
- [ ] Idempotency keys on write endpoints
