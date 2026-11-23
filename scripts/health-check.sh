#!/bin/bash
set -euo pipefail

# Veyloria Health Check Script — Quarkus v3

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== Veyloria Health Check ==="
echo "Time: $(date)"
echo

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

HTTP_PORT="${QUARKUS_HTTP_PORT:-8300}"
LLM_PORT="${LLM_PORT:-8080}"
DB_NAME="${DATABASE_NAME:-chatllm}"

check_service() {
    local name=$1
    local check_command=$2
    echo -n "Checking $name... "
    if eval "$check_command" > /dev/null 2>&1; then
        echo -e "${GREEN}OK${NC}"
        return 0
    else
        echo -e "${RED}FAILED${NC}"
        return 1
    fi
}

# Quarkus health endpoints (root-path configured as /health in application.properties)
check_service "Backend (Quarkus)" "curl -sf http://localhost:$HTTP_PORT/health/live"
check_service "Backend (Ready)"   "curl -sf http://localhost:$HTTP_PORT/health/ready"

# GraphQL endpoint
check_service "GraphQL API" "curl -sf -X POST http://localhost:$HTTP_PORT/graphql -H 'Content-Type: application/json' -d '{\"query\":\"{__typename}\"}'"

# MySQL
check_service "MySQL" "mysql -u \"${DATABASE_USER:-chatllm}\" -p\"${DATABASE_PASSWORD:-chatllm}\" -h \"${DATABASE_HOST:-localhost}\" -P \"${DATABASE_PORT:-3306}\" \"$DB_NAME\" -e 'SELECT 1'"

# Redis
check_service "Redis" "redis-cli -h \"${REDIS_HOST:-localhost}\" -p \"${REDIS_PORT:-6379}\" -a \"${REDIS_PASSWORD:-}\" --no-auth-warning ping"

# LLM Server (optional — only checked when enabled)
if [ "${LLM_ENABLED:-false}" = "true" ]; then
    check_service "LLM Server" "curl -sf http://localhost:$LLM_PORT/health"
fi

# Quarkus process
echo -n "Checking Quarkus Process... "
if pgrep -f "quarkus" > /dev/null 2>&1 || pgrep -f "chatllm.*runner" > /dev/null 2>&1; then
    PID=$(pgrep -f "quarkus|chatllm.*runner" | head -1)
    MEM=$(ps -p "$PID" -o %mem= 2>/dev/null | tr -d ' ' || echo "?")
    echo -e "${GREEN}Running (PID: $PID, Mem: ${MEM}%)${NC}"
else
    echo -e "${RED}Not Running${NC}"
fi

# Disk
echo -n "Checking Disk Space... "
DISK_USAGE=$(df -h "$SCRIPT_DIR" | tail -1 | awk '{print $5}' | sed 's/%//')
if [ "$DISK_USAGE" -lt 90 ]; then
    echo -e "${GREEN}OK (${DISK_USAGE}% used)${NC}"
else
    echo -e "${YELLOW}Warning (${DISK_USAGE}% used)${NC}"
fi

# Log file
LOG_FILE="$SCRIPT_DIR/logs/chatllm.log"
echo -n "Checking Log File... "
if [ -f "$LOG_FILE" ]; then
    LOG_SIZE=$(du -h "$LOG_FILE" | cut -f1)
    RECENT_ERRORS=$(tail -n 100 "$LOG_FILE" | grep -c "ERROR" || true)
    echo -e "${GREEN}Found (Size: $LOG_SIZE, Recent errors: $RECENT_ERRORS)${NC}"
else
    echo -e "${YELLOW}Not Found${NC}"
fi

# Grafana (optional — only checked when enabled)
if [ "${GRAFANA_ENABLED:-false}" = "true" ]; then
    check_service "Grafana" "curl -sf http://localhost:${GRAFANA_PORT:-3005}/api/health"
fi

# Prometheus (optional — only checked when enabled)
if [ "${PROMETHEUS_ENABLED:-false}" = "true" ]; then
    check_service "Prometheus" "curl -sf http://localhost:${PROMETHEUS_PORT:-9091}/-/healthy"
fi

echo
echo "=== Health Check Complete ==="