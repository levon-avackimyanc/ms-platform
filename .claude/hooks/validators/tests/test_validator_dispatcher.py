"""
Tests for `validator_dispatcher.py` — focus on the `--authoring` relaxed gate.

Two layers:
- unit tests on the pure `get_validators_for_file` selection logic;
- a `main()` test that exercises the `--authoring` argv path without running the
  real linters (run_validator is monkeypatched to a recorder).

Run from repo root:
    uv run --with pytest pytest .claude/hooks/validators/tests/
"""

from __future__ import annotations

import io
import json
import sys
from pathlib import Path

import pytest

# Make the validator module importable.
VALIDATORS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(VALIDATORS_DIR))

from validator_dispatcher import (  # noqa: E402  (intentional late import)
    get_validators_for_file,
    main,
)

COMPILE = "maven_compile_validator.py"
JACOCO = "jacoco_validator.py"
SPOTLESS = "spotless_validator.py"
PMD = "pmd_validator.py"


# ── get_validators_for_file: authoring relaxes the gate for Java test files ──


@pytest.mark.parametrize("filename", ["FooIT.java", "BarTest.java"])
def test_authoring_drops_compile_and_coverage_for_test_files(filename):
    validators = get_validators_for_file(f"src/test/java/{filename}", authoring=True)
    assert validators == [SPOTLESS, PMD]
    assert COMPILE not in validators
    assert JACOCO not in validators


@pytest.mark.parametrize("filename", ["FooIT.java", "BarTest.java"])
def test_normal_mode_keeps_full_gate_for_test_files(filename):
    validators = get_validators_for_file(f"src/test/java/{filename}")
    assert validators == [SPOTLESS, COMPILE, JACOCO, PMD]


def test_authoring_does_not_relax_non_test_java():
    # Defensive: the autotester only writes test files, but a product .java in
    # authoring mode must still compile.
    validators = get_validators_for_file("src/main/java/Service.java", authoring=True)
    assert COMPILE in validators
    assert JACOCO not in validators  # jacoco is test-file only


def test_authoring_flag_does_not_affect_python():
    assert get_validators_for_file("x.py", authoring=True) == get_validators_for_file(
        "x.py"
    )


def test_pom_xml_unaffected_by_authoring():
    validators = get_validators_for_file("pom.xml", authoring=True)
    assert validators == [COMPILE, "ossindex_validator.py"]


# ── main(): the --authoring argv flag wires through to selection ──


def _run_main(monkeypatch, capsys, file_path: str, argv: list[str]) -> list[str]:
    """Run main() with a recorded run_validator; return the dispatched scripts."""
    dispatched: list[str] = []

    def fake_run_validator(script: str, _stdin: str) -> dict:
        dispatched.append(script)
        return {}  # everything passes

    monkeypatch.setattr("validator_dispatcher.run_validator", fake_run_validator)
    monkeypatch.setattr(sys, "argv", ["validator_dispatcher.py", *argv])
    payload = json.dumps({"tool_input": {"file_path": file_path}})
    monkeypatch.setattr(sys, "stdin", io.StringIO(payload))

    main()
    capsys.readouterr()  # drain
    return dispatched


def test_main_authoring_skips_compile_for_test_file(monkeypatch, capsys):
    dispatched = _run_main(
        monkeypatch, capsys, "src/test/java/FooIT.java", ["--authoring"]
    )
    assert dispatched == [SPOTLESS, PMD]


def test_main_without_authoring_runs_full_gate(monkeypatch, capsys):
    dispatched = _run_main(monkeypatch, capsys, "src/test/java/FooIT.java", [])
    assert dispatched == [SPOTLESS, COMPILE, JACOCO, PMD]
