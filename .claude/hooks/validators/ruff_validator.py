#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""
Ruff Linter Validator for Claude Code PostToolUse Hook

Runs `uvx ruff check` on individual Python files after Write operations.

Outputs JSON decision for Claude Code PostToolUse hook:
- {"decision": "block", "reason": "..."} to block and retry
- {} to allow completion
"""
import json
import logging
import subprocess
import sys
from pathlib import Path

# Logging setup - log file next to this script
SCRIPT_DIR = Path(__file__).parent
LOG_FILE = SCRIPT_DIR / "ruff_validator.log"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    handlers=[logging.FileHandler(LOG_FILE, mode='a')]
)
logger = logging.getLogger(__name__)


def main():
    logger.info("=" * 50)
    logger.info("RUFF VALIDATOR POSTTOOLUSE HOOK TRIGGERED")

    # Read hook input from stdin (Claude Code passes JSON)
    try:
        stdin_data = sys.stdin.read()
        if stdin_data.strip():
            hook_input = json.loads(stdin_data)
            logger.info(f"hook_input keys: {list(hook_input.keys())}")
        else:
            hook_input = {}
    except json.JSONDecodeError:
        hook_input = {}

    # Extract file_path from PostToolUse input
    file_path = hook_input.get("tool_input", {}).get("file_path", "")
    logger.info(f"file_path: {file_path}")

    # Only run for Python files
    if not file_path.endswith(".py"):
        logger.info("Skipping non-Python file")
        print(json.dumps({}))
        return

    # Run uvx ruff check on the single file
    logger.info(f"Running: uvx ruff check {file_path}")
    try:
        result = subprocess.run(
            ["uvx", "ruff", "check", file_path],
            capture_output=True,
            text=True,
            timeout=120
        )

        stdout = result.stdout.strip()
        stderr = result.stderr.strip()

        if stdout:
            for line in stdout.split('\n')[:20]:  # Limit log lines
                logger.info(f"  {line}")

        if result.returncode == 0:
            logger.info("RESULT: PASS - Lint check successful")
            print(json.dumps({}))
            return

        # Auto-fix the safe violations first instead of blocking and making the
        # agent hand-fix trivial lint. Then re-check; block only on what remains
        # (real issues ruff won't auto-fix).
        logger.info("Lint failed — running ruff check --fix")
        subprocess.run(
            ["uvx", "ruff", "check", "--fix", file_path],
            capture_output=True,
            text=True,
            timeout=120,
        )
        recheck = subprocess.run(
            ["uvx", "ruff", "check", file_path],
            capture_output=True,
            text=True,
            timeout=120,
        )
        if recheck.returncode == 0:
            logger.info("RESULT: PASS - lint auto-fixed")
            print(json.dumps({}))
            return

        error_output = recheck.stdout.strip() or recheck.stderr.strip() or "Lint check failed"
        logger.info(f"RESULT: BLOCK (remaining after --fix, exit {recheck.returncode})")
        print(json.dumps({
            "decision": "block",
            "reason": (
                "Lint issues remain AFTER `ruff check --fix` — these need real changes, "
                f"not formatting:\n{error_output[:500]}"
            )
        }))

    except subprocess.TimeoutExpired:
        logger.info("RESULT: BLOCK (timeout)")
        print(json.dumps({
            "decision": "block",
            "reason": "Lint check timed out after 120 seconds"
        }))
    except FileNotFoundError:
        logger.info("RESULT: PASS (uvx ruff not found, skipping)")
        print(json.dumps({}))


if __name__ == "__main__":
    main()
