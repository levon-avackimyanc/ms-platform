#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#   "pyyaml>=6.0",
# ]
# ///

"""
Validates the structural integrity of `analytic/increment.md` produced by the
`business-analyst` agent.

Hook Type: Stop (configured on `/analyze`).

Template (which sections are required, which need Given-When-Then scenarios,
which need a numbered list) is loaded from
`.claude/config/increment_template.yaml` — single source of truth shared
with the `business-analyst` agent prompt. Built-in defaults are used as a
safe fallback when the config is missing or unparseable.

Checks performed:
1. has_required_sections          — all H2 sections from config present
2. minimum_length_per_section     — each required section body has
                                    >= default_min_section_length chars
3. scenarios_parseable_as_gwt     — every scenario inside any `gwt_sections`
                                    contains Given/When/Then
4. acceptance_criteria_format     — every `numbered_list_sections` body has
                                    >= 1 numbered list item

Exit codes:
- 0: all checks passed
- 1: one or more checks failed (or file missing)
- 0 on unexpected validator errors (do not block the user on bugs)

Output (stdout, JSON):
{
  "result":  "continue" | "block",      # for Claude Code Stop hook
  "message": "...",                     # when result == continue
  "reason":  "...",                     # when result == block (action-oriented)
  "status":  "ok" | "fail",             # contract field (used by pytest)
  "checks":  [{name, passed, details}, ...]
}

Usage:
  uv run --script .claude/hooks/validators/validate_increment.py
  uv run --script .claude/hooks/validators/validate_increment.py --file path/to/increment.md
  uv run --script .claude/hooks/validators/validate_increment.py --config path/to/template.yaml
  uv run --script .claude/hooks/validators/validate_increment.py --min-section-length 80
  uv run --script .claude/hooks/validators/validate_increment.py --pretty   # human-readable, keeps Cyrillic

Frontmatter example (in commands/analyze.md):
  hooks:
    Stop:
      - hooks:
          - type: command
            command: >-
              uv run $CLAUDE_PROJECT_DIR/.claude/hooks/validators/validate_increment.py
              --file analytic/increment.md
              --config $CLAUDE_PROJECT_DIR/.claude/config/increment_template.yaml
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import re
import sys
from dataclasses import asdict, dataclass, field, replace
from pathlib import Path

import yaml

# Logging setup — log file next to this script (SAME NAME).
SCRIPT_DIR = Path(__file__).parent
LOG_FILE = SCRIPT_DIR / "validate_increment.log"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    handlers=[logging.FileHandler(LOG_FILE, mode="a")],
)
logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

DEFAULT_FILE = "analytic/increment.md"
DEFAULT_CONFIG_RELATIVE = ".claude/config/increment_template.yaml"

ACTION_HEADER = (
    "VALIDATION FAILED for {file}: {n} check(s) failed.\n\n"
    "FAILED CHECKS:\n{details}\n\n"
    "ACTION REQUIRED: Fix the listed issues in {file} and re-save. "
    "Do NOT proceed until validate_increment.py reports status=ok."
)


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class TemplateConfig:
    """Shape of `analytic/increment.md` consumed by this validator."""

    required_sections: list[str] = field(default_factory=list)
    gwt_sections: list[str] = field(default_factory=list)
    numbered_list_sections: list[str] = field(default_factory=list)
    default_min_section_length: int = 50


DEFAULT_TEMPLATE = TemplateConfig(
    required_sections=[
        "Цель инкремента",
        "Функциональные требования",
        "Нефункциональные требования",
        "Business-flow",
        "Сценарии использования",
        "Acceptance criteria",
    ],
    gwt_sections=["Сценарии использования"],
    numbered_list_sections=["Acceptance criteria"],
    default_min_section_length=50,
)


@dataclass(frozen=True)
class Check:
    """Result of a single structural check."""

    name: str
    passed: bool
    details: str

    def to_dict(self) -> dict:
        return asdict(self)


# ---------------------------------------------------------------------------
# Config loading
# ---------------------------------------------------------------------------


def resolve_default_config_path() -> Path | None:
    """
    Try to find the team-level increment_template.yaml without an explicit flag.

    Search order:
      1. $CLAUDE_PROJECT_DIR/.claude/config/increment_template.yaml
      2. $PWD/.claude/config/increment_template.yaml

    Returns the first existing path, or None if neither is found.
    """
    project_dir = os.environ.get("CLAUDE_PROJECT_DIR")
    candidates: list[Path] = []
    if project_dir:
        candidates.append(Path(project_dir) / DEFAULT_CONFIG_RELATIVE)
    candidates.append(Path.cwd() / DEFAULT_CONFIG_RELATIVE)

    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return None


def load_template(config_path: Path | None) -> TemplateConfig:
    """
    Load template config from YAML. Missing path / file / parse error
    fall back to the built-in DEFAULT_TEMPLATE — we never block the user
    on infra problems.

    Each field in YAML is optional; missing fields fall back per-field.
    """
    if config_path is None or not config_path.is_file():
        logger.info("config not provided or not found, using DEFAULT_TEMPLATE")
        return DEFAULT_TEMPLATE

    try:
        raw = config_path.read_text(encoding="utf-8")
        data = yaml.safe_load(raw) or {}
        if not isinstance(data, dict):
            raise ValueError(f"top-level YAML must be a mapping, got {type(data).__name__}")
    except (OSError, UnicodeDecodeError, yaml.YAMLError, ValueError) as exc:
        logger.warning(
            "failed to parse config %s: %s — falling back to DEFAULT_TEMPLATE",
            config_path,
            exc,
        )
        return DEFAULT_TEMPLATE

    return TemplateConfig(
        required_sections=list(
            data.get("required_sections", DEFAULT_TEMPLATE.required_sections)
        ),
        gwt_sections=list(
            data.get("gwt_sections", DEFAULT_TEMPLATE.gwt_sections)
        ),
        numbered_list_sections=list(
            data.get(
                "numbered_list_sections",
                DEFAULT_TEMPLATE.numbered_list_sections,
            )
        ),
        default_min_section_length=int(
            data.get(
                "default_min_section_length",
                DEFAULT_TEMPLATE.default_min_section_length,
            )
        ),
    )


# ---------------------------------------------------------------------------
# Section helpers
# ---------------------------------------------------------------------------


def _h2_pattern(section_name: str) -> re.Pattern[str]:
    """
    Compile a regex matching a level-2 heading with the given name.

    Per CommonMark, up to 3 spaces of indentation in front of `##` is valid,
    so we tolerate any leading whitespace — the validator is here to catch
    structural mistakes, not whitespace quibbles.
    """
    return re.compile(
        r"^\s*##\s+" + re.escape(section_name) + r"\s*$",
        re.MULTILINE | re.IGNORECASE,
    )


def find_section_text(content: str, section_name: str) -> str | None:
    """
    Return the body of the H2 section named `section_name` — i.e. everything
    between its heading and the next H2 heading (or end-of-file).

    Tolerates leading whitespace before `##` (CommonMark allows up to 3 spaces;
    we don't quibble about the exact count).

    Returns None if the section heading is not found.
    """
    pattern = re.compile(
        r"^\s*##\s+" + re.escape(section_name) + r"\s*$"
        r"(.*?)"
        r"(?=^\s*##\s+|\Z)",
        re.MULTILINE | re.IGNORECASE | re.DOTALL,
    )
    match = pattern.search(content)
    if match is None:
        return None
    return match.group(1).strip()


def _split_scenarios(body: str) -> list[str]:
    """
    Split a scenarios section body into individual scenarios.

    Recognized formats:
    1. H3 headings (`### Сценарий 1: ...`), with optional leading indent.
    2. Numbered list items (`1. Сценарий ...`, `2. ...`).
    """
    h3_parts = re.split(r"^\s*###\s+.*$", body, flags=re.MULTILINE)
    if len(h3_parts) > 1:
        return [chunk.strip() for chunk in h3_parts[1:] if chunk.strip()]

    matches = re.findall(
        r"^\s*\d+\.\s+(.*?)(?=^\s*\d+\.\s+|\Z)",
        body,
        flags=re.MULTILINE | re.DOTALL,
    )
    return [m.strip() for m in matches if m.strip()]


# ---------------------------------------------------------------------------
# Individual checks (all parametrized by TemplateConfig)
# ---------------------------------------------------------------------------


def check_has_required_sections(content: str, template: TemplateConfig) -> Check:
    if not template.required_sections:
        return Check(
            name="has_required_sections",
            passed=True,
            details="no required sections configured",
        )

    missing = [
        name for name in template.required_sections if not _h2_pattern(name).search(content)
    ]
    if missing:
        return Check(
            name="has_required_sections",
            passed=False,
            details=f"missing sections: {missing}",
        )
    return Check(
        name="has_required_sections",
        passed=True,
        details=f"all {len(template.required_sections)} required sections present",
    )


def check_minimum_length_per_section(content: str, template: TemplateConfig) -> Check:
    min_length = template.default_min_section_length
    too_short: list[tuple[str, int]] = []
    for name in template.required_sections:
        body = find_section_text(content, name)
        if body is None:
            # Don't double-report — has_required_sections already flagged it.
            continue
        normalized = re.sub(r"\s+", " ", body).strip()
        if len(normalized) < min_length:
            too_short.append((name, len(normalized)))

    if too_short:
        details = "; ".join(
            f"'{name}' has {length} chars (min {min_length})"
            for name, length in too_short
        )
        return Check(
            name="minimum_length_per_section",
            passed=False,
            details=details,
        )
    return Check(
        name="minimum_length_per_section",
        passed=True,
        details=f"all sections >= {min_length} chars",
    )


def check_scenarios_parseable_as_gwt(content: str, template: TemplateConfig) -> Check:
    # Only check sections that are both flagged as GWT-sections AND required.
    relevant = [s for s in template.gwt_sections if s in template.required_sections]
    if not relevant:
        return Check(
            name="scenarios_parseable_as_gwt",
            passed=True,
            details="no GWT sections configured",
        )

    invalid_details: list[str] = []
    sections_checked = 0
    for section_name in relevant:
        body = find_section_text(content, section_name)
        if body is None:
            # has_required_sections already flagged it; skip silently.
            continue
        sections_checked += 1

        scenarios = _split_scenarios(body)
        if not scenarios:
            invalid_details.append(
                f"'{section_name}': no scenarios parseable "
                "(expected '### ...' or numbered list)"
            )
            continue

        for idx, scenario_text in enumerate(scenarios, start=1):
            missing = [
                keyword
                for keyword in ("Given", "When", "Then")
                if not re.search(rf"\b{keyword}\b", scenario_text, re.IGNORECASE)
            ]
            if missing:
                invalid_details.append(
                    f"'{section_name}' scenario #{idx} missing: {missing}"
                )

    if invalid_details:
        return Check(
            name="scenarios_parseable_as_gwt",
            passed=False,
            details="; ".join(invalid_details),
        )
    if sections_checked == 0:
        return Check(
            name="scenarios_parseable_as_gwt",
            passed=True,
            details="no GWT sections present in the document",
        )
    return Check(
        name="scenarios_parseable_as_gwt",
        passed=True,
        details=f"all scenarios in {sections_checked} section(s) are GWT-parseable",
    )


def check_acceptance_criteria_format(content: str, template: TemplateConfig) -> Check:
    relevant = [
        s for s in template.numbered_list_sections if s in template.required_sections
    ]
    if not relevant:
        return Check(
            name="acceptance_criteria_format",
            passed=True,
            details="no numbered-list sections configured",
        )

    invalid_details: list[str] = []
    sections_checked = 0
    for section_name in relevant:
        body = find_section_text(content, section_name)
        if body is None:
            continue
        sections_checked += 1
        numbered_items = re.findall(r"^\s*\d+\.\s+\S+", body, flags=re.MULTILINE)
        if not numbered_items:
            invalid_details.append(
                f"'{section_name}': no numbered list items found "
                "(expected '1. ...', '2. ...', ...)"
            )

    if invalid_details:
        return Check(
            name="acceptance_criteria_format",
            passed=False,
            details="; ".join(invalid_details),
        )
    return Check(
        name="acceptance_criteria_format",
        passed=True,
        details=f"all numbered-list sections OK ({sections_checked} checked)",
    )


# ---------------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------------


def run_all_checks(content: str, template: TemplateConfig) -> list[Check]:
    """Run all four structural checks in order."""
    return [
        check_has_required_sections(content, template),
        check_minimum_length_per_section(content, template),
        check_scenarios_parseable_as_gwt(content, template),
        check_acceptance_criteria_format(content, template),
    ]


def build_output(file_path: Path, checks: list[Check]) -> tuple[int, dict]:
    """
    Convert the list of checks into a (exit_code, output_dict) pair ready to
    print to stdout. The dict carries both Claude Code Stop-hook fields
    (result / message / reason) and our internal contract (status / checks).
    """
    failed = [c for c in checks if not c.passed]
    status = "ok" if not failed else "fail"

    if status == "ok":
        return 0, {
            "result": "continue",
            "message": f"{file_path} passed all {len(checks)} structural checks",
            "status": status,
            "checks": [c.to_dict() for c in checks],
        }

    details = "\n".join(f"  - {c.name}: {c.details}" for c in failed)
    reason = ACTION_HEADER.format(file=file_path, n=len(failed), details=details)
    return 1, {
        "result": "block",
        "reason": reason,
        "status": status,
        "checks": [c.to_dict() for c in checks],
    }


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate the structural integrity of analytic/increment.md"
    )
    parser.add_argument(
        "--file",
        type=Path,
        default=Path(DEFAULT_FILE),
        help=f"Path to increment.md (default: {DEFAULT_FILE})",
    )
    parser.add_argument(
        "--config",
        type=Path,
        default=None,
        help=(
            "Path to increment_template.yaml. "
            f"If omitted, looks for $CLAUDE_PROJECT_DIR/{DEFAULT_CONFIG_RELATIVE} "
            f"or $PWD/{DEFAULT_CONFIG_RELATIVE}. "
            "Missing config = built-in defaults."
        ),
    )
    parser.add_argument(
        "--min-section-length",
        type=int,
        default=None,
        help=(
            "Override default_min_section_length from config "
            "(no effect on the rest of the template)."
        ),
    )
    parser.add_argument(
        "--pretty",
        action="store_true",
        help=(
            "Pretty-print the JSON output (indent=2, non-ASCII preserved). "
            "Convenience for local inspection — DO NOT use in the Claude Code "
            "Stop-hook command, the hook reads compact JSON."
        ),
    )
    return parser.parse_args(argv)


def _emit(output: dict, pretty: bool) -> None:
    """Serialize and print the JSON output, preserving Cyrillic in both modes."""
    if pretty:
        print(json.dumps(output, ensure_ascii=False, indent=2))
    else:
        print(json.dumps(output, ensure_ascii=False))


def main(argv: list[str] | None = None) -> int:
    logger.info("=" * 60)
    logger.info("Validator started: validate_increment")

    pretty = False  # used by the catch-all error branch below
    try:
        args = parse_args(argv)
        pretty = args.pretty
        config_path = args.config or resolve_default_config_path()
        template = load_template(config_path)

        # CLI override of min-section-length, if provided.
        if args.min_section_length is not None:
            template = replace(template, default_min_section_length=args.min_section_length)

        logger.info(
            "file=%s config=%s required=%d min=%d pretty=%s",
            args.file,
            config_path or "<built-in default>",
            len(template.required_sections),
            template.default_min_section_length,
            pretty,
        )

        if not args.file.exists():
            reason = (
                f"VALIDATION FAILED: file {args.file} not found.\n\n"
                "ACTION REQUIRED: Use the Write tool to create "
                f"{args.file} with the required sections."
            )
            _emit(
                {
                    "result": "block",
                    "reason": reason,
                    "status": "fail",
                    "checks": [
                        {
                            "name": "file_exists",
                            "passed": False,
                            "details": f"file not found: {args.file}",
                        }
                    ],
                },
                pretty,
            )
            logger.warning("FAIL: file not found: %s", args.file)
            return 1

        try:
            content = args.file.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            _emit(
                {
                    "result": "block",
                    "reason": f"VALIDATION FAILED: cannot read {args.file}: {exc}",
                    "status": "fail",
                    "checks": [
                        {
                            "name": "file_readable",
                            "passed": False,
                            "details": f"cannot read {args.file}: {exc}",
                        }
                    ],
                },
                pretty,
            )
            logger.error("FAIL: cannot read file: %s", exc)
            return 1

        checks = run_all_checks(content, template)
        exit_code, output = build_output(args.file, checks)

        if exit_code == 0:
            logger.info("PASS: all %d checks", len(checks))
        else:
            failing_names = [c.name for c in checks if not c.passed]
            logger.warning("FAIL: %s", failing_names)

        _emit(output, pretty)
        return exit_code

    except Exception as exc:  # noqa: BLE001 — last-resort safety net
        # Never block the user on a bug in this validator: log + emit "continue".
        logger.exception("Validator crashed: %s", exc)
        _emit(
            {
                "result": "continue",
                "message": f"validate_increment error (allowing through): {exc}",
            },
            pretty,
        )
        return 0


if __name__ == "__main__":
    sys.exit(main())
