"""
Tests for `validate_plan.py`.

Two layers:
- unit tests that import the module directly and exercise each check;
- CLI smoke tests that invoke the script through `uv run --script`.

Special focus: checks 8 and 10 after the Dev/Test scope split — Dev scope
writes UNIT tests only, so only `unit-tests` task / Unit Layer are mandatory;
integration & e2e are owned by Test scope and must NOT be required here.

Run from repo root:
    uv run --with pytest pytest .claude/hooks/validators/tests/
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

# Make the validator module importable.
VALIDATORS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(VALIDATORS_DIR))

from validate_plan import (  # noqa: E402  (intentional late import)
    check_acceptance_criteria,
    check_agent_types,
    check_circular_dependencies,
    check_dependency_refs,
    check_relevant_files_exist,
    check_test_infrastructure_fields,
    check_test_infrastructure_section,
    check_test_layer_tasks,
    check_unique_task_ids,
    parse_plan,
    validate_plan,
)

VALIDATOR_SCRIPT = VALIDATORS_DIR / "validate_plan.py"
REPO_ROOT = VALIDATORS_DIR.parent.parent.parent
TEAM_DIR = REPO_ROOT / ".claude" / "agents"

# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

# A plan that passes all 10 checks: files are New (so they need not exist),
# tasks reference real team agents, and only the Unit Layer is declared.
VALID_PLAN = """\
# Plan: cancel subscription endpoint

## Relevant Files

### New Files

- `src/app/subscriptions.py`
- `tests/unit/test_subscriptions.py`

## Step by Step Tasks

### 1. Implement cancel endpoint

**Task ID**: `impl-endpoint`
**Depends On**: none
**Agent Type**: `developer`
**Stack**: Python, FastAPI

Implement POST /subscriptions/{id}/cancel.

### 2. Unit tests for the endpoint

**Task ID**: `unit-tests`
**Depends On**: `impl-endpoint`
**Agent Type**: `unit-tester`
**Stack**: Python, pytest

Cover happy path and the forbidden case.

## Acceptance Criteria

1. Owner cancels an active subscription in one request.
2. A non-owner receives 403 without any status change.

## Test Infrastructure (User-Declared)

### Unit Layer (Python)

- **Status:** Active
- **Files glob:** `tests/unit/**/*.py`
- **Happy-path scenarios:**
  - `tests/unit/test_subscriptions.py::test_cancel_ok`
- **Runner command:** `pytest tests/unit`
- **Realism rationale:** Pure service logic, no external infra required.
"""


@pytest.fixture
def plan() -> dict:
    return parse_plan(VALID_PLAN)


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------


def test_parse_plan_extracts_structure(plan: dict) -> None:
    assert plan["new_files"] == [
        "src/app/subscriptions.py",
        "tests/unit/test_subscriptions.py",
    ]
    assert plan["relevant_files"] == []  # all listed under ### New Files

    ids = [t["id"] for t in plan["tasks"]]
    assert ids == ["impl-endpoint", "unit-tests"]

    unit_task = plan["tasks"][1]
    assert unit_task["depends_on"] == ["impl-endpoint"]
    assert unit_task["agent_type"] == "unit-tester"
    assert unit_task["stack"] == "Python, pytest"


def test_parse_plan_unit_layer_fields(plan: dict) -> None:
    layers = plan["test_infrastructure"]["layers"]
    assert plan["test_infrastructure"]["section_present"] is True
    assert len(layers) == 1
    unit = layers[0]
    assert unit["kind"] == "unit"
    assert unit["stack"] == "Python"
    assert unit["files_glob"] == "tests/unit/**/*.py"
    assert unit["runner_command"] == "pytest tests/unit"
    assert unit["realism_rationale"]
    assert unit["scenarios"] == [
        "tests/unit/test_subscriptions.py::test_cancel_ok"
    ]


# ---------------------------------------------------------------------------
# Happy path — full validation
# ---------------------------------------------------------------------------


def test_valid_plan_passes_all_checks(tmp_path: Path) -> None:
    f = tmp_path / "plan.md"
    f.write_text(VALID_PLAN, encoding="utf-8")
    ok, message = validate_plan(str(f), str(TEAM_DIR))
    assert ok, message
    assert "passed all 10 structural checks" in message


# ---------------------------------------------------------------------------
# Check 1: Relevant Files exist (New Files skipped)
# ---------------------------------------------------------------------------


def test_relevant_files_must_exist(tmp_path: Path) -> None:
    existing = tmp_path / "real.py"
    existing.write_text("x = 1\n", encoding="utf-8")
    missing = str(tmp_path / "ghost.py")
    errors = check_relevant_files_exist([str(existing), missing], new_files=[])
    assert len(errors) == 1
    assert "ghost.py" in errors[0]


def test_new_files_are_not_required_to_exist(tmp_path: Path) -> None:
    missing = str(tmp_path / "to_be_created.py")
    errors = check_relevant_files_exist([missing], new_files=[missing])
    assert errors == []


# ---------------------------------------------------------------------------
# Check 2/3/4: task graph integrity
# ---------------------------------------------------------------------------


def test_duplicate_task_ids_fail() -> None:
    tasks = [{"id": "a"}, {"id": "a"}]
    errors = check_unique_task_ids(tasks)
    assert len(errors) == 1
    assert "Duplicate Task ID: `a`" in errors[0]


def test_dangling_dependency_fails() -> None:
    tasks = [{"id": "a", "depends_on": ["ghost"]}]
    errors = check_dependency_refs(tasks)
    assert len(errors) == 1
    assert "ghost" in errors[0]


def test_circular_dependency_detected() -> None:
    tasks = [
        {"id": "a", "depends_on": ["b"]},
        {"id": "b", "depends_on": ["a"]},
    ]
    errors = check_circular_dependencies(tasks)
    assert len(errors) == 1
    assert "Circular dependency" in errors[0]


def test_acyclic_dependencies_pass() -> None:
    tasks = [
        {"id": "a", "depends_on": []},
        {"id": "b", "depends_on": ["a"]},
    ]
    assert check_circular_dependencies(tasks) == []


# ---------------------------------------------------------------------------
# Check 5: agent types
# ---------------------------------------------------------------------------


def test_unknown_agent_type_fails() -> None:
    tasks = [{"id": "a", "agent_type": "no-such-agent"}]
    errors = check_agent_types(tasks, str(TEAM_DIR))
    assert len(errors) == 1
    assert "no-such-agent" in errors[0]


def test_known_team_agent_passes() -> None:
    tasks = [{"id": "a", "agent_type": "developer"}]
    assert check_agent_types(tasks, str(TEAM_DIR)) == []


# ---------------------------------------------------------------------------
# Check 6: acceptance criteria
# ---------------------------------------------------------------------------


def test_empty_acceptance_criteria_fails() -> None:
    assert check_acceptance_criteria("") != []


def test_single_line_acceptance_criteria_fails() -> None:
    assert check_acceptance_criteria("1. only one line") != []


# ---------------------------------------------------------------------------
# Check 8: Dev scope test tasks — unit-tests mandatory, integration/e2e optional
# ---------------------------------------------------------------------------


def test_missing_unit_tests_task_fails() -> None:
    tasks = [{"id": "impl-endpoint"}]
    errors = check_test_layer_tasks(tasks, test_infra={})
    assert len(errors) == 1
    assert "unit-tests" in errors[0]


def test_unit_tests_task_present_passes() -> None:
    tasks = [{"id": "impl-endpoint"}, {"id": "unit-tests"}]
    assert check_test_layer_tasks(tasks, test_infra={}) == []


def test_legacy_write_tests_task_is_forbidden() -> None:
    # `write-tests` present AND `unit-tests` absent → two distinct errors.
    tasks = [{"id": "write-tests"}]
    errors = check_test_layer_tasks(tasks, test_infra={})
    assert any("write-tests" in e for e in errors)
    assert any("unit-tests" in e for e in errors)


def test_integration_e2e_tasks_not_required_for_dev_plan() -> None:
    """Dev scope must NOT demand integration-tests / e2e-tests tasks."""
    tasks = [{"id": "impl-endpoint"}, {"id": "unit-tests"}]
    errors = check_test_layer_tasks(tasks, test_infra={})
    assert errors == []
    joined = " ".join(errors)
    assert "integration-tests" not in joined
    assert "e2e-tests" not in joined


def test_integration_task_present_is_allowed() -> None:
    """An integration-tests task may appear (e.g. Test scope) without error."""
    tasks = [
        {"id": "impl-endpoint"},
        {"id": "unit-tests"},
        {"id": "integration-tests"},
    ]
    assert check_test_layer_tasks(tasks, test_infra={}) == []


# ---------------------------------------------------------------------------
# Check 9: Test Infrastructure section present
# ---------------------------------------------------------------------------


def test_missing_test_infra_section_fails() -> None:
    errors = check_test_infrastructure_section({"section_present": False})
    assert len(errors) == 1
    assert "Test Infrastructure (User-Declared)" in errors[0]


# ---------------------------------------------------------------------------
# Check 10: Unit Layer mandatory, integration/e2e optional & skippable
# ---------------------------------------------------------------------------


def _unit_layer(**overrides) -> dict:
    layer = {
        "kind": "unit",
        "stack": "Python",
        "status": "Active",
        "files_glob": "tests/unit/**/*.py",
        "infra_signature": None,
        "scenarios": ["tests/unit/test_x.py::test_ok"],
        "runner_command": "pytest tests/unit",
        "realism_rationale": "Pure logic.",
    }
    layer.update(overrides)
    return layer


def test_unit_layer_complete_passes() -> None:
    infra = {"section_present": True, "layers": [_unit_layer()]}
    assert check_test_infrastructure_fields(infra) == []


def test_missing_unit_layer_fails() -> None:
    """Only integration declared → Unit Layer absent is a failure."""
    integration = {
        "kind": "integration",
        "stack": "Python",
        "status": "Active",
        "files_glob": "tests/it/**/*.py",
        "infra_signature": "Testcontainers",
        "scenarios": ["tests/it/test_x.py::test_ok"],
        "runner_command": "pytest tests/it",
        "realism_rationale": "Real DB.",
    }
    infra = {"section_present": True, "layers": [integration]}
    errors = check_test_infrastructure_fields(infra)
    assert any("Unit Layer" in e for e in errors)


def test_integration_layer_may_be_skipped() -> None:
    """Integration is Test scope's job — skipping it in a Dev plan is fine."""
    skipped_integration = {
        "kind": "integration",
        "stack": "Python",
        "status": "Skipped — owned by Test scope",
        "files_glob": None,
        "infra_signature": None,
        "scenarios": [],
        "runner_command": None,
        "realism_rationale": None,
    }
    infra = {
        "section_present": True,
        "layers": [_unit_layer(), skipped_integration],
    }
    assert check_test_infrastructure_fields(infra) == []


def test_unit_layer_missing_field_fails() -> None:
    infra = {
        "section_present": True,
        "layers": [_unit_layer(runner_command=None)],
    }
    errors = check_test_infrastructure_fields(infra)
    assert any("Runner command" in e for e in errors)


def test_unit_layer_does_not_require_infra_signature() -> None:
    """Infra signature is optional for the Unit Layer."""
    infra = {
        "section_present": True,
        "layers": [_unit_layer(infra_signature=None)],
    }
    assert check_test_infrastructure_fields(infra) == []


def test_non_skipped_integration_requires_infra_signature() -> None:
    """If a Dev plan opts to include a live Integration Layer, it's validated."""
    integration = {
        "kind": "integration",
        "stack": "Python",
        "status": "Active",
        "files_glob": "tests/it/**/*.py",
        "infra_signature": None,  # missing
        "scenarios": ["tests/it/test_x.py::test_ok"],
        "runner_command": "pytest tests/it",
        "realism_rationale": "Real DB.",
    }
    infra = {"section_present": True, "layers": [_unit_layer(), integration]}
    errors = check_test_infrastructure_fields(infra)
    assert any("Infra signature" in e for e in errors)


# ---------------------------------------------------------------------------
# CLI smoke tests
# ---------------------------------------------------------------------------


def test_cli_smoke_returns_continue_for_valid_plan(tmp_path: Path) -> None:
    f = tmp_path / "plan.md"
    f.write_text(VALID_PLAN, encoding="utf-8")

    result = subprocess.run(
        [
            "uv", "run", "--script", str(VALIDATOR_SCRIPT),
            "--file", str(f),
            "--team-dir", str(TEAM_DIR),
        ],
        capture_output=True,
        text=True,
        timeout=60,
    )

    assert result.returncode == 0, result.stderr
    output = json.loads(result.stdout)
    assert output["result"] == "continue"


def test_cli_smoke_blocks_plan_without_unit_tests(tmp_path: Path) -> None:
    bad = VALID_PLAN.replace("**Task ID**: `unit-tests`", "**Task ID**: `write-tests`")
    f = tmp_path / "plan.md"
    f.write_text(bad, encoding="utf-8")

    result = subprocess.run(
        [
            "uv", "run", "--script", str(VALIDATOR_SCRIPT),
            "--file", str(f),
            "--team-dir", str(TEAM_DIR),
        ],
        capture_output=True,
        text=True,
        timeout=60,
    )

    assert result.returncode == 1
    output = json.loads(result.stdout)
    assert output["result"] == "block"
    assert "unit-tests" in output["reason"]


def test_cli_smoke_blocks_missing_file(tmp_path: Path) -> None:
    missing = tmp_path / "does_not_exist.md"

    result = subprocess.run(
        ["uv", "run", "--script", str(VALIDATOR_SCRIPT), "--file", str(missing)],
        capture_output=True,
        text=True,
        timeout=60,
    )

    assert result.returncode == 1
    output = json.loads(result.stdout)
    assert output["result"] == "block"
