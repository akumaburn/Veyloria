#!/bin/bash
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Quarkus is launched from backend/, so load the root configuration explicitly.
# This is a trusted local shell-compatible file, just like exported shell settings.
if [[ -f "$SCRIPT_DIR/.env" ]]; then
    set -a
    source "$SCRIPT_DIR/.env"
    set +a
fi

echo -e "${GREEN}Starting Veyloria Development Environment${NC}"
echo "========================================"

# Check dependencies using Docker (preferred) or systemd fallback
echo -e "${YELLOW}Checking dependencies...${NC}"

if command -v docker &>/dev/null && docker compose version &>/dev/null; then
    echo -e "${GREEN}Docker Compose available - ensuring core services are up${NC}"
    cd "$SCRIPT_DIR"
    docker compose up -d mysql redis
else
    # Legacy systemd fallback
    for svc in mysql redis; do
        if ! systemctl is-active --quiet "$svc" 2>/dev/null; then
            echo -e "${YELLOW}$svc is not running. Starting $svc...${NC}"
            sudo systemctl start "$svc" || echo -e "${RED}Failed to start $svc (try Docker instead)${NC}"
        fi
    done
fi

# Kill existing processes on dev ports
echo -e "${YELLOW}Cleaning up existing processes...${NC}"
lsof -ti:8300 2>/dev/null | xargs -r kill -9 2>/dev/null || true
lsof -ti:3000 2>/dev/null | xargs -r kill -9 2>/dev/null || true

# Start Ming natively so development can use the host GPU without Docker GPU setup.
TTS_PID=''
if curl -sf --max-time 2 http://127.0.0.1:9797/health >/dev/null; then
    echo -e "${GREEN}Ming voice service is already healthy${NC}"
else
    "$SCRIPT_DIR/scripts/run-tts.sh" &
    TTS_PID=$!
fi

# Start backend
echo -e "${GREEN}Starting backend server...${NC}"
cd "$SCRIPT_DIR/backend"
./gradlew quarkusDev --console=plain &
BACKEND_PID=$!

# Start frontend
echo -e "${GREEN}Starting frontend development server...${NC}"
cd "$SCRIPT_DIR/frontend"
bun run dev &
FRONTEND_PID=$!

# Wait then verify
echo -e "${YELLOW}Waiting for services to start...${NC}"
sleep 8

if curl -sf http://localhost:8300/health > /dev/null 2>&1; then
    echo -e "${GREEN}Backend is healthy!${NC}"
else
    echo -e "${YELLOW}Backend health check pending (still starting?)${NC}"
fi

echo -e "${GREEN}========================================"
echo -e "Veyloria is running!"
echo -e "Frontend: http://localhost:3000"
echo -e "Backend: http://localhost:8300"
echo -e "GraphQL: http://localhost:8300/graphql"
echo -e "========================================${NC}"
echo ""
echo "Press Ctrl+C to stop all services"

cleanup() {
    echo -e "\n${YELLOW}Shutting down services...${NC}"
    kill "$BACKEND_PID" 2>/dev/null || true
    kill "$FRONTEND_PID" 2>/dev/null || true
    if [[ -n "$TTS_PID" ]]; then kill "$TTS_PID" 2>/dev/null || true; fi
    echo -e "${GREEN}Services stopped.${NC}"
}

trap cleanup INT TERM
wait