#!/bin/bash
# ============================================================
# Veyloria Database Reset Script
# Drops and recreates the database with fresh schema
# ============================================================

# Configuration (can be overridden by environment variables)
DB_HOST="${DATABASE_HOST:-localhost}"
DB_PORT="${DATABASE_PORT:-3306}"
DB_NAME="${DATABASE_NAME:-chatllm}"
DB_USER="${DATABASE_USER:-chatllm}"
DB_PASSWORD="${DATABASE_PASSWORD:-chatllm}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(dirname "$SCRIPT_DIR")"
SCHEMA_FILE="$BACKEND_DIR/src/main/resources/db/schema.sql"

echo -e "${YELLOW}============================================================${NC}"
echo -e "${YELLOW}Veyloria Database Reset Script${NC}"
echo -e "${YELLOW}============================================================${NC}"
echo ""

# Debug info
echo -e "${CYAN}Configuration:${NC}"
echo "  Host: $DB_HOST:$DB_PORT"
echo "  Database: $DB_NAME"
echo "  User: $DB_USER"
echo "  Schema file: $SCHEMA_FILE"
echo ""

# Check if schema file exists
if [ ! -f "$SCHEMA_FILE" ]; then
    echo -e "${RED}Error: Schema file not found at: $SCHEMA_FILE${NC}"
    exit 1
fi

# Check schema file size
SCHEMA_SIZE=$(wc -c < "$SCHEMA_FILE")
echo -e "${CYAN}Schema file size: ${SCHEMA_SIZE} bytes${NC}"

if [ "$SCHEMA_SIZE" -lt 100 ]; then
    echo -e "${RED}Error: Schema file appears to be empty or too small${NC}"
    exit 1
fi

echo ""

# Warning
echo -e "${RED}WARNING: This will DELETE ALL DATA in the '$DB_NAME' database!${NC}"
echo ""
read -p "Are you sure you want to continue? (y/N) " -n 1 -r
echo ""

if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo -e "${YELLOW}Operation cancelled.${NC}"
    exit 0
fi

echo ""
echo -e "${YELLOW}Step 1: Dropping and recreating database...${NC}"

# Drop and recreate database
OUTPUT=$(mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASSWORD" -e "
    DROP DATABASE IF EXISTS $DB_NAME;
    CREATE DATABASE $DB_NAME CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
" 2>&1)
EXIT_CODE=$?

# Show output (filter password warning)
echo "$OUTPUT" | grep -v "Using a password on the command line" | grep -v "Deprecated"

if [ $EXIT_CODE -ne 0 ]; then
    echo -e "${RED}Failed to drop/recreate database. Check credentials and permissions.${NC}"
    echo -e "${YELLOW}Required permissions: CREATE, DROP on databases${NC}"
    exit 1
fi

echo -e "${GREEN}Database dropped and recreated successfully.${NC}"

echo ""
echo -e "${YELLOW}Step 2: Applying schema...${NC}"
echo -e "${CYAN}Reading schema from: $SCHEMA_FILE${NC}"

# Apply schema
MYSQL_OUTPUT=$(mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" < "$SCHEMA_FILE" 2>&1)
MYSQL_EXIT_CODE=$?

# Filter out password warning, show everything else
FILTERED_OUTPUT=$(echo "$MYSQL_OUTPUT" | grep -v "Using a password on the command line" | grep -v "Deprecated")
if [ -n "$FILTERED_OUTPUT" ]; then
    echo "$FILTERED_OUTPUT"
fi

if [ $MYSQL_EXIT_CODE -ne 0 ]; then
    echo ""
    echo -e "${RED}Failed to apply schema. Exit code: $MYSQL_EXIT_CODE${NC}"
    echo -e "${RED}See error messages above.${NC}"
    exit 1
fi

echo -e "${GREEN}Schema applied successfully.${NC}"

echo ""
echo -e "${YELLOW}Step 3: Verifying tables...${NC}"

# Verify tables were created
TABLE_COUNT=$(mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" -N -e "
    SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = '$DB_NAME';
" 2>&1 | grep -v "Using a password" | grep -v "Deprecated" | tr -d '[:space:]')

if [ -z "$TABLE_COUNT" ] || [ "$TABLE_COUNT" -eq 0 ]; then
    echo -e "${RED}Warning: No tables were created!${NC}"
    echo -e "${RED}There may be an issue with the schema file.${NC}"
    exit 1
fi

echo -e "${GREEN}Created $TABLE_COUNT tables in database '$DB_NAME'.${NC}"

# List tables
echo ""
echo -e "${YELLOW}Tables created:${NC}"
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" -e "SHOW TABLES;" 2>&1 | grep -v "Using a password" | grep -v "Deprecated"

echo ""
echo -e "${GREEN}============================================================${NC}"
echo -e "${GREEN}Database reset complete!${NC}"
echo -e "${GREEN}============================================================${NC}"
echo ""
echo "Next steps:"
echo "  1. Start Redis: sudo systemctl start redis"
echo "  2. Run the application: ./run-dev.sh or ./gradlew quarkusDev"
echo ""
