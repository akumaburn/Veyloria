#!/bin/bash
# Veyloria - Initialization and Smoke Test Script
# This script starts the system and runs basic verification tests

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "========================================"
echo "Veyloria Initialization & Smoke Test"
echo "========================================"
echo ""

# Function to print status
print_status() {
    if [ $1 -eq 0 ]; then
        echo -e "${GREEN}[PASS]${NC} $2"
    else
        echo -e "${RED}[FAIL]${NC} $2"
        return 1
    fi
}

print_warning() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_info() {
    echo -e "[INFO] $1"
}

# Check if .env exists
check_env() {
    print_info "Checking environment configuration..."
    if [ -f ".env" ]; then
        print_status 0 ".env file exists"
    else
        print_warning ".env file not found - copying from .env.example"
        if [ -f ".env.example" ]; then
            cp .env.example .env
            print_warning "Created .env from .env.example. Set a random JWT_SECRET and replace passwords before starting; see README.md."
            return 1
        else
            print_status 1 ".env.example not found"
            return 1
        fi
    fi
}

# Check Docker availability
check_docker() {
    print_info "Checking Docker availability..."
    if command -v docker &> /dev/null; then
        print_status 0 "Docker is installed"
        docker --version
    else
        print_status 1 "Docker is not installed"
        return 1
    fi

    if docker compose version &> /dev/null 2>&1; then
        print_status 0 "Docker Compose is available"
    else
        print_status 1 "Docker Compose is not available"
        return 1
    fi
}

# Start services with Docker Compose
start_services() {
    print_info "Starting services with Docker Compose..."

    docker compose up -d --build mysql redis backend frontend
    print_status $? "Core Docker Compose services started"

    # Wait for services to be healthy
    print_info "Waiting for services to be ready (max 60s)..."
    sleep 10  # Initial wait

    local retries=10
    local count=0
    while [ $count -lt $retries ]; do
        if curl -fsS http://localhost:8300/health > /dev/null 2>&1; then
            print_status 0 "Backend is responding"
            break
        fi
        count=$((count + 1))
        print_info "Waiting for backend... ($count/$retries)"
        sleep 5
    done

    if [ $count -eq $retries ]; then
        print_status 1 "Backend did not become ready in time"
        return 1
    fi
}

# Run smoke tests
run_smoke_tests() {
    print_info "Running smoke tests..."
    echo ""

    # Test 1: Health endpoint
    print_info "Test 1: Health endpoint"
    HEALTH_RESPONSE=$(curl -s http://localhost:8300/health)
    if echo "$HEALTH_RESPONSE" | grep -q "UP\|healthy\|ok"; then
        print_status 0 "Health endpoint returns healthy status"
    else
        print_warning "Health endpoint response: $HEALTH_RESPONSE"
        print_status 1 "Health endpoint check failed"
    fi

    # Test 2: GraphQL endpoint responds
    print_info "Test 2: GraphQL endpoint"
    GQL_RESPONSE=$(curl -s -X POST http://localhost:8300/graphql \
        -H "Content-Type: application/json" \
        -d '{"query":"{ __typename }"}')
    if echo "$GQL_RESPONSE" | grep -q "Query\|data"; then
        print_status 0 "GraphQL endpoint is responding"
    else
        print_warning "GraphQL response: $GQL_RESPONSE"
        print_status 1 "GraphQL endpoint check failed"
    fi

    # Test 3: Frontend is accessible (if running)
    print_info "Test 3: Frontend accessibility"
    local frontend_port="${FRONTEND_PORT:-$(sed -n 's/^FRONTEND_PORT=//p' .env 2>/dev/null | tail -1 | sed 's/[[:space:]#].*$//')}"
    FRONTEND_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${frontend_port:-80}" 2>/dev/null || curl -s -o /dev/null -w "%{http_code}" http://localhost:5173 2>/dev/null || echo "000")
    if [ "$FRONTEND_RESPONSE" = "200" ] || [ "$FRONTEND_RESPONSE" = "304" ]; then
        print_status 0 "Frontend is accessible"
    else
        print_warning "Frontend not accessible (HTTP $FRONTEND_RESPONSE) - may require manual start"
    fi

    # Test 4: Database connectivity via health
    print_info "Test 4: Database connectivity"
    HEALTH_DETAIL=$(curl -s http://localhost:8300/health 2>/dev/null)
    if echo "$HEALTH_DETAIL" | grep -qi "database.*up\|mysql.*connected\|db.*ok"; then
        print_status 0 "Database is connected"
    else
        print_warning "Could not verify database connectivity from health endpoint"
    fi

    # Test 5: GraphQL introspection
    print_info "Test 5: GraphQL schema introspection"
    INTROSPECTION=$(curl -s -X POST http://localhost:8300/graphql \
        -H "Content-Type: application/json" \
        -d '{"query":"{ __schema { types { name } } }"}')
    if echo "$INTROSPECTION" | grep -q "User\|Conversation\|Message"; then
        print_status 0 "GraphQL schema is available"
    else
        print_warning "Schema introspection may be disabled"
    fi

    echo ""
}

# Manual start instructions
show_manual_instructions() {
    echo ""
    echo "========================================"
    echo "Manual Start Instructions"
    echo "========================================"
    echo ""
    echo "Option 1: Docker Compose (Recommended)"
    echo "  cd $SCRIPT_DIR"
    echo "  docker compose up -d --build mysql redis backend frontend"
    echo ""
    echo "Option 2: Manual Development"
    echo "  Backend:"
    echo "    cd $SCRIPT_DIR/backend"
    echo "    ./gradlew build -x test"
    echo "    ./gradlew quarkusDev"
    echo ""
    echo "  Frontend:"
    echo "    cd $SCRIPT_DIR/frontend"
    echo "    bun install --frozen-lockfile"
    echo "    bun run dev"
    echo ""
    echo "Option 3: Run automated tests"
    echo "  cd $SCRIPT_DIR/backend && ./gradlew test"
    echo "  cd $SCRIPT_DIR/frontend && bun run test -- --run"
    echo ""
    echo "Endpoints:"
    echo "  Frontend: http://localhost (or http://localhost:3000 in dev)"
    echo "  GraphQL:  http://localhost:8300/graphql"
    echo "  Health:   http://localhost:8300/health"
    echo "  Metrics:  http://localhost:8300/metrics"
    echo ""
}

# Stop services
stop_services() {
    print_info "Stopping services..."
    if docker compose version &> /dev/null 2>&1; then
        docker compose down
    else
        docker-compose down
    fi
    print_status $? "Services stopped"
}

# Main execution
main() {
    local mode="${1:-full}"

    case "$mode" in
        "check")
            echo "Running pre-flight checks only..."
            check_env
            check_docker
            ;;
        "start")
            echo "Starting services..."
            check_env
            check_docker
            start_services
            ;;
        "test")
            echo "Running smoke tests only..."
            run_smoke_tests
            ;;
        "stop")
            stop_services
            ;;
        "help")
            show_manual_instructions
            ;;
        "full"|*)
            echo "Running full initialization..."
            check_env
            check_docker
            start_services
            run_smoke_tests
            show_manual_instructions
            ;;
    esac

    echo ""
    echo "========================================"
    echo "Initialization Complete"
    echo "========================================"
}

# Parse arguments
main "$@"
