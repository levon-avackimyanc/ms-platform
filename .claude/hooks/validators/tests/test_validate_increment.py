"""
Tests for `validate_increment.py`.

Two layers:
- unit tests that import the module directly and exercise each check;
- CLI smoke tests that invoke the script through `uv run --script`.

Run from repo root:
    uv run --with pytest pytest .claude/hooks/validators/tests/
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

import pytest

# Make the validator module importable.
VALIDATORS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(VALIDATORS_DIR))

# Imported here, after sys.path tweak.
from validate_increment import (  # noqa: E402  (intentional late import)
    DEFAULT_TEMPLATE,
    TemplateConfig,
    build_output,
    check_acceptance_criteria_format,
    check_has_required_sections,
    check_minimum_length_per_section,
    check_scenarios_parseable_as_gwt,
    load_template,
    run_all_checks,
)

VALIDATOR_SCRIPT = VALIDATORS_DIR / "validate_increment.py"
REPO_ROOT = VALIDATORS_DIR.parent.parent.parent
DEFAULT_CONFIG_PATH = REPO_ROOT / ".claude" / "config" / "increment_template.yaml"

# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture
def default_template() -> TemplateConfig:
    return DEFAULT_TEMPLATE


VALID_INCREMENT = """\
# Increment XYZ

## Increment Goal

The goal is to add a new endpoint for managing user subscriptions.
This is needed for billing integration and to simplify subscription cancellation by the client.

## Functional Requirements

1. Endpoint POST /subscriptions/{id}/cancel cancels the owner's active subscription.
2. Endpoint GET /subscriptions/{id} returns the current subscription status.
3. On cancellation, a notification must be sent to billing with user_id and subscription_id.

## Non-Functional Requirements

- Latency P99 < 200ms under 100 RPS load.
- JWT authorization — only the subscription owner or admin.
- Logging of all cancellation attempts with user_id and timestamp.

## Business-flow

1. The user clicks "Cancel subscription" in their account dashboard.
2. Frontend calls POST /subscriptions/{id}/cancel.
3. Backend checks permissions, cancels the subscription, sends an event to billing.
4. The user receives a confirmation and refund instructions.

## Usage Scenarios

1. Scenario "Owner cancels an active subscription".
   Given user U has an active subscription S.
   When U sends POST /subscriptions/S/cancel with a valid JWT.
   Then subscription S transitions to status CANCELLED and an event is sent to billing.

2. Scenario "Unauthorized user tries to cancel a subscription".
   Given user V has no rights to subscription S.
   When V sends POST /subscriptions/S/cancel with a valid JWT.
   Then response 403 Forbidden and the status of subscription S does not change.

## Acceptance criteria

1. On an active subscription, the owner's cancellation succeeds in a single request.
2. An unauthorized user receives 403 without changing the subscription status.
3. After cancellation, the subscription status is CANCELLED and an event was sent to billing.
"""


# ---------------------------------------------------------------------------
# Happy path
# ---------------------------------------------------------------------------


def test_valid_increment_passes_all_checks(default_template: TemplateConfig) -> None:
    checks = run_all_checks(VALID_INCREMENT, default_template)
    failures = [(c.name, c.details) for c in checks if not c.passed]
    assert not failures, failures


def test_build_output_emits_continue_on_ok(default_template: TemplateConfig) -> None:
    checks = run_all_checks(VALID_INCREMENT, default_template)
    code, output = build_output(Path("dummy/increment.md"), checks)
    assert code == 0
    assert output["status"] == "ok"
    assert output["result"] == "continue"
    assert "passed all" in output["message"]


# ---------------------------------------------------------------------------
# Regression: leading whitespace before `##` must not hide the H2.
# ---------------------------------------------------------------------------


def test_h2_with_leading_indent_is_recognized(default_template: TemplateConfig) -> None:
    """
    CommonMark allows up to 3 spaces of indent before `##`; our validator must
    not treat such headings as missing. (Regression: real-world copy-paste
    from chat into a heredoc reproducibly injected 2 leading spaces.)
    """
    indented = re.sub(r"(?m)^## ", "  ## ", VALID_INCREMENT)
    checks = run_all_checks(indented, default_template)
    failures = [(c.name, c.details) for c in checks if not c.passed]
    assert not failures, failures


# ---------------------------------------------------------------------------
# Config-driven behaviour
# ---------------------------------------------------------------------------


def test_default_template_used_when_no_config() -> None:
    """load_template(None) returns the in-code DEFAULT_TEMPLATE."""
    template = load_template(None)
    assert template == DEFAULT_TEMPLATE


def test_missing_config_file_falls_back_to_default(tmp_path: Path) -> None:
    """A path to a non-existent file falls back to DEFAULT_TEMPLATE silently."""
    template = load_template(tmp_path / "nonexistent.yaml")
    assert template == DEFAULT_TEMPLATE


def test_real_config_yaml_matches_default(default_template: TemplateConfig) -> None:
    """
    The shipped `.claude/config/increment_template.yaml` must round-trip to the
    same TemplateConfig as the in-code DEFAULT_TEMPLATE — otherwise the file
    silently drifts from what the validator promises.
    """
    assert DEFAULT_CONFIG_PATH.is_file(), f"missing: {DEFAULT_CONFIG_PATH}"
    loaded = load_template(DEFAULT_CONFIG_PATH)
    assert loaded == default_template


def test_custom_template_with_three_sections(tmp_path: Path) -> None:
    """A minimal custom template with only 3 sections is accepted."""
    config = tmp_path / "template.yaml"
    config.write_text(
        """
default_min_section_length: 10
required_sections:
  - "Goal"
  - "Description"
  - "Acceptance"
gwt_sections: []
numbered_list_sections:
  - "Acceptance"
""",
        encoding="utf-8",
    )
    template = load_template(config)
    assert template.required_sections == ["Goal", "Description", "Acceptance"]
    assert template.gwt_sections == []
    assert template.numbered_list_sections == ["Acceptance"]
    assert template.default_min_section_length == 10

    short_increment = """\
## Goal

Add a new endpoint.

## Description

CRUD handler for subscriptions.

## Acceptance

1. Returns 200.
2. Returns 404 for missing id.
"""
    checks = run_all_checks(short_increment, template)
    failures = [(c.name, c.details) for c in checks if not c.passed]
    assert not failures, failures


def test_custom_template_without_gwt_skips_check(tmp_path: Path) -> None:
    """If config declares no gwt_sections, the GWT check passes trivially."""
    config = tmp_path / "template.yaml"
    config.write_text(
        """
default_min_section_length: 20
required_sections:
  - "Goal"
gwt_sections: []
numbered_list_sections: []
""",
        encoding="utf-8",
    )
    template = load_template(config)
    document = "## Goal\n\nSome text here long enough to pass min length.\n"
    check = check_scenarios_parseable_as_gwt(document, template)
    assert check.passed
    assert "no GWT sections" in check.details


# ---------------------------------------------------------------------------
# Failure mode: a required section is missing.
# ---------------------------------------------------------------------------


def test_missing_section_fails_has_required_sections(
    default_template: TemplateConfig,
) -> None:
    bad = VALID_INCREMENT.replace("## Acceptance criteria", "## Something else")
    check = check_has_required_sections(bad, default_template)
    assert not check.passed
    assert "Acceptance criteria" in check.details


def test_build_output_emits_block_on_fail(default_template: TemplateConfig) -> None:
    bad = VALID_INCREMENT.replace("## Acceptance criteria", "## Foo")
    checks = run_all_checks(bad, default_template)
    code, output = build_output(Path("dummy/increment.md"), checks)
    assert code == 1
    assert output["status"] == "fail"
    assert output["result"] == "block"
    assert "ACTION REQUIRED" in output["reason"]


# ---------------------------------------------------------------------------
# Failure mode: a section body is too short.
# ---------------------------------------------------------------------------


def test_short_section_fails_minimum_length(default_template: TemplateConfig) -> None:
    # Replace body of "Increment Goal" with a very short one-liner.
    bad = re.sub(
        r"(## Increment Goal\n\n).*?(?=\n## )",
        r"\1Brief.\n",
        VALID_INCREMENT,
        count=1,
        flags=re.DOTALL,
    )
    check = check_minimum_length_per_section(bad, default_template)
    assert not check.passed
    assert "Increment Goal" in check.details


# ---------------------------------------------------------------------------
# Failure mode: scenarios are not Given-When-Then.
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "bad_scenarios_body",
    [
        # Numbered list, but no Given/When/Then.
        "1. Scenario one. The user does something and gets a result.\n"
        "2. Scenario two. Something goes wrong and an error arrives.\n",
        # H3 headings, but no Given/When/Then.
        "### Scenario 1\nThe user registers in the system.\n\n"
        "### Scenario 2\nThe user logs in again.\n",
        # Empty body — nothing parseable as a scenario.
        "Scenario descriptions will be added later.\n",
    ],
)
def test_scenarios_without_gwt_fails(
    default_template: TemplateConfig,
    bad_scenarios_body: str,
) -> None:
    # Using a lambda so that bad_scenarios_body containing leading digits
    # (e.g. "1. ...") is NOT misinterpreted as a regex backreference (\11, \12, …).
    bad = re.sub(
        r"(## Usage Scenarios\n\n).*?(?=\n## )",
        lambda m: m.group(1) + bad_scenarios_body,
        VALID_INCREMENT,
        count=1,
        flags=re.DOTALL,
    )
    check = check_scenarios_parseable_as_gwt(bad, default_template)
    assert not check.passed


# ---------------------------------------------------------------------------
# Failure mode: acceptance criteria are not a numbered list.
# ---------------------------------------------------------------------------


def test_acceptance_without_numbered_list_fails(
    default_template: TemplateConfig,
) -> None:
    bad = re.sub(
        r"## Acceptance criteria.*",
        (
            "## Acceptance criteria\n\n"
            "- On an active subscription, cancellation succeeds in one request.\n"
            "- An unauthorized user receives 403 without changing the status.\n"
        ),
        VALID_INCREMENT,
        count=1,
        flags=re.DOTALL,
    )
    check = check_acceptance_criteria_format(bad, default_template)
    assert not check.passed


# ---------------------------------------------------------------------------
# CLI smoke tests
# ---------------------------------------------------------------------------


def test_cli_smoke_returns_ok_for_valid_file(tmp_path: Path) -> None:
    """Run the actual script via `uv run --script` on a valid file."""
    f = tmp_path / "increment.md"
    f.write_text(VALID_INCREMENT, encoding="utf-8")

    result = subprocess.run(
        ["uv", "run", "--script", str(VALIDATOR_SCRIPT), "--file", str(f)],
        capture_output=True,
        text=True,
        timeout=60,
    )

    assert result.returncode == 0, result.stderr
    output = json.loads(result.stdout)
    assert output["status"] == "ok"
    assert output["result"] == "continue"


def test_cli_smoke_returns_skipped_for_missing_file(tmp_path: Path) -> None:
    """
    Missing target file is NOT a failure — Stop-hooks fire on every subagent
    stop in /analyze, including ones before Write. The validator must emit
    `continue` so the flow is not blocked while the file isn't there yet.
    """
    missing = tmp_path / "does_not_exist.md"

    result = subprocess.run(
        ["uv", "run", "--script", str(VALIDATOR_SCRIPT), "--file", str(missing)],
        capture_output=True,
        text=True,
        timeout=60,
    )

    assert result.returncode == 0
    output = json.loads(result.stdout)
    assert output["status"] == "skipped"
    assert output["result"] == "continue"
    assert output["checks"] == []


def test_cli_smoke_with_explicit_config(tmp_path: Path) -> None:
    """CLI accepts --config; a 3-section template works end-to-end via subprocess."""
    config = tmp_path / "template.yaml"
    config.write_text(
        """
default_min_section_length: 10
required_sections:
  - "Goal"
  - "Description"
  - "Acceptance"
gwt_sections: []
numbered_list_sections:
  - "Acceptance"
""",
        encoding="utf-8",
    )
    increment = tmp_path / "increment.md"
    increment.write_text(
        """\
## Goal

Add a new endpoint for subscriptions.

## Description

CRUD handler for the subscription entity.

## Acceptance

1. Returns 200 for existing subscriptions.
2. Returns 404 for missing id.
""",
        encoding="utf-8",
    )

    result = subprocess.run(
        [
            "uv",
            "run",
            "--script",
            str(VALIDATOR_SCRIPT),
            "--file",
            str(increment),
            "--config",
            str(config),
        ],
        capture_output=True,
        text=True,
        timeout=60,
    )

    assert result.returncode == 0, result.stderr
    output = json.loads(result.stdout)
    assert output["status"] == "ok"
