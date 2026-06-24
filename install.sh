#!/bin/bash
# Install Claude Code hooks for Java/React projects
# Usage: curl -fsSL https://raw.githubusercontent.com/a-simeshin/claude-code-hooks-mastery/main/install.sh | bash

set -e

REPO="a-simeshin/claude-code-hooks-mastery"
BRANCH="main"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${GREEN}Claude Code Hooks Installer${NC}"
echo -e "${CYAN}For Java / React / Python projects${NC}"
echo ""

# ─────────────────────────────────────────────────────────────
# Check and install UV (required for hooks)
# ─────────────────────────────────────────────────────────────
if ! command -v uv &> /dev/null; then
    echo -e "${YELLOW}UV not found. Installing...${NC}"

    if [[ "$OSTYPE" == "darwin"* ]] && command -v brew &> /dev/null; then
        # macOS with Homebrew
        brew install uv
    else
        # Cross-platform installer
        curl -LsSf https://astral.sh/uv/install.sh | sh
        # Add to PATH for current session
        export PATH="$HOME/.local/bin:$PATH"
    fi

    if command -v uv &> /dev/null; then
        echo -e "${GREEN}UV installed successfully${NC}"
    else
        echo -e "${YELLOW}Warning: UV installation may require restart. Add ~/.local/bin to PATH${NC}"
    fi
else
    echo -e "${GREEN}UV found: $(uv --version)${NC}"
fi
echo ""

# Check if .claude already exists
if [ -d ".claude" ]; then
    echo -e "${YELLOW}Note: .claude directory exists, will merge files${NC}"
fi

# Create temp directory
TMP_DIR=$(mktemp -d)
trap "rm -rf $TMP_DIR" EXIT

# Download and extract
echo "Downloading from github.com/${REPO}..."
curl -fsSL "https://github.com/${REPO}/archive/refs/heads/${BRANCH}.tar.gz" | tar -xz -C "$TMP_DIR"

# Copy .claude folder (merge if exists)
mkdir -p .claude
cp -r "$TMP_DIR/claude-code-hooks-mastery-${BRANCH}/.claude/." .claude/

echo ""
echo -e "${GREEN}Configuration${NC}"
echo ""

# Detect interactive TTY
if [ -t 0 ] && [ -e /dev/tty ]; then
    INTERACTIVE=true
else
    INTERACTIVE=false
    echo -e "${YELLOW}Non-interactive mode: using defaults (status=1, tts=off, agentic=off)${NC}"
fi

# ─────────────────────────────────────────────────────────────
# 1. TTS Notifications
# ─────────────────────────────────────────────────────────────
echo ""
if [ "$INTERACTIVE" = true ]; then
    read -p "Enable TTS notifications? (requires ElevenLabs/OpenAI) [y/N]: " -n 1 -r TTS_CHOICE </dev/tty
    echo
else
    TTS_CHOICE="${TTS_ENABLED:-n}"
fi
if [[ $TTS_CHOICE =~ ^[Yy]$ ]]; then
    TTS_ENABLED=true
else
    TTS_ENABLED=false
fi

# ─────────────────────────────────────────────────────────────
# 2. Agentic Mode
# ─────────────────────────────────────────────────────────────
echo ""
echo -e "${YELLOW}Agentic mode runs without permission prompts.${NC}"
if [ "$INTERACTIVE" = true ]; then
    read -p "Start Claude Code with --dangerously-skip-permissions? [y/N]: " -n 1 -r AGENTIC_CHOICE </dev/tty
    echo
else
    AGENTIC_CHOICE="${AGENTIC_MODE:-n}"
fi

# ─────────────────────────────────────────────────────────────
# Apply configuration
# ─────────────────────────────────────────────────────────────
echo ""
SETTINGS_FILE=".claude/settings.json"

# TTS
if [ "$TTS_ENABLED" = false ]; then
    # Remove --notify flags
    sed -i.bak 's/ --notify//g' "$SETTINGS_FILE" && rm -f "$SETTINGS_FILE.bak"
    echo "  TTS: disabled"
else
    echo "  TTS: enabled"
fi

echo "  Validators: Java, React/TS, Python (all installed)"

# ─────────────────────────────────────────────────────────────
# 3. MCP servers (context7, serena)
# ─────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}MCP servers${NC}"

if command -v claude &> /dev/null; then
    # context7 — up-to-date library docs (runs via npx)
    if command -v npx &> /dev/null; then
        if claude mcp add context7 -s project -- npx -y @upstash/context7-mcp@latest 2>/dev/null; then
            echo -e "  ${GREEN}context7: added${NC}"
        else
            echo -e "  ${YELLOW}context7: skipped (already configured or failed)${NC}"
        fi
    else
        echo -e "  ${YELLOW}context7: skipped (npx/Node.js not found)${NC}"
    fi

    # serena — semantic code toolkit (runs via uvx, provided by uv)
    if command -v uvx &> /dev/null; then
        if claude mcp add serena -s project -- uvx --from git+https://github.com/oraios/serena serena start-mcp-server --context ide-assistant --project "$(pwd)" 2>/dev/null; then
            echo -e "  ${GREEN}serena: added${NC}"
        else
            echo -e "  ${YELLOW}serena: skipped (already configured or failed)${NC}"
        fi
    else
        echo -e "  ${YELLOW}serena: skipped (uvx not found — install uv)${NC}"
    fi
else
    echo -e "  ${YELLOW}claude CLI not found — skipping MCP setup.${NC}"
    echo "  After installing Claude Code, run:"
    echo "    claude mcp add context7 -- npx -y @upstash/context7-mcp@latest"
    echo "    claude mcp add serena -- uvx --from git+https://github.com/oraios/serena serena start-mcp-server --context ide-assistant"
fi

# ─────────────────────────────────────────────────────────────
# 4. OpenSpec (optional — living specs for /plan_w_team + /smart_build)
# ─────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}OpenSpec (optional)${NC}"

if command -v npm &> /dev/null; then
    if command -v openspec &> /dev/null; then
        echo -e "  ${GREEN}openspec: already installed ($(openspec --version 2>/dev/null))${NC}"
    elif npm install -g @fission-ai/openspec 2>/dev/null; then
        echo -e "  ${GREEN}openspec: installed${NC}"
    else
        echo -e "  ${YELLOW}openspec: skipped (npm install failed)${NC}"
    fi

    # Initialize OpenSpec in this project so /plan_w_team explore/propose
    # and /smart_build task-tracking steps activate (otherwise they skip).
    if command -v openspec &> /dev/null; then
        if [ -d "openspec" ]; then
            echo "  openspec: already initialized in this project"
        elif openspec init --tools claude 2>/dev/null; then
            echo -e "  ${GREEN}openspec: initialized (openspec/ created)${NC}"
        else
            echo -e "  ${YELLOW}openspec: init skipped — run 'openspec init --tools claude' manually${NC}"
        fi
    fi
else
    echo -e "  ${YELLOW}openspec: skipped (npm/Node.js not found)${NC}"
    echo "  To enable living specs later, run:"
    echo "    npm i -g @fission-ai/openspec && openspec init --tools claude"
fi

echo ""
echo -e "${GREEN}Done!${NC}"
echo ""
echo "Installed to .claude/"

# Launch Claude Code if agentic mode selected
if [[ $AGENTIC_CHOICE =~ ^[Yy]$ ]]; then
    echo ""
    echo -e "${YELLOW}Starting Claude Code in agentic mode...${NC}"
    exec claude --dangerously-skip-permissions
else
    echo "Run 'claude' to start, or 'claude --dangerously-skip-permissions' for agentic mode."
fi
