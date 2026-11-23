#!/bin/bash
set -euo pipefail

# Veyloria Restore Script — Quarkus v3

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SCRIPT_DIR"

echo "=== Veyloria Restore Script ==="
echo

BACKUP_DIR="${BACKUP_DIR:-$SCRIPT_DIR/backups}"
DB_NAME="${DATABASE_NAME:-chatllm}"
DB_USER="${DATABASE_USER:-chatllm}"
DB_PASS="${DATABASE_PASSWORD:-chatllm}"
DB_HOST="${DATABASE_HOST:-localhost}"
DB_PORT="${DATABASE_PORT:-3306}"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

if [ ! -d "$BACKUP_DIR" ]; then
    echo -e "${RED}Error: Backup directory not found: $BACKUP_DIR${NC}"
    exit 1
fi

# List available backups
echo "Available database backups:"
echo
mapfile -t backups < <(ls -1 "$BACKUP_DIR"/chatllm_db_*.sql.gz 2>/dev/null || true)
if [ ${#backups[@]} -eq 0 ]; then
    echo -e "${RED}No backups found in $BACKUP_DIR${NC}"
    exit 1
fi
for i in "${!backups[@]}"; do
    echo "$((i+1))) ${backups[$i]}"
done

echo
read -p "Enter backup number to restore (or 'q' to quit): " selection

if [ "$selection" = "q" ]; then
    echo "Restore cancelled"
    exit 0
fi

BACKUP_FILE="${backups[$((selection-1))]:-}"
if [ -z "$BACKUP_FILE" ] || [ ! -f "$BACKUP_FILE" ]; then
    echo -e "${RED}Invalid selection${NC}"
    exit 1
fi

echo
echo -e "${YELLOW}Selected backup: $BACKUP_FILE${NC}"
echo -e "${YELLOW}WARNING: This will replace all current data!${NC}"
read -p "Are you sure you want to continue? (yes/no): " confirm

if [ "$confirm" != "yes" ]; then
    echo "Restore cancelled"
    exit 0
fi

# Check for running Quarkus process
echo
echo "Checking for running application..."
QUARKUS_PID=$(pgrep -f "quarkus|chatllm.*runner" 2>/dev/null || true)
if [ -n "$QUARKUS_PID" ]; then
    echo -e "${YELLOW}Application appears to be running (PID: $QUARKUS_PID).${NC}"
    echo "Please stop it first (e.g. Ctrl+C on the dev server, or 'docker compose stop backend')"
    exit 1
fi

TEMP_DIR=$(mktemp -d)
echo "Extracting backup..."
gunzip -c "$BACKUP_FILE" > "$TEMP_DIR/restore.sql"

echo "Restoring database..."
if mysql -u "$DB_USER" -p"$DB_PASS" -h "$DB_HOST" -P "$DB_PORT" "$DB_NAME" < "$TEMP_DIR/restore.sql"; then
    echo -e "${GREEN}Database restored successfully${NC}"
else
    echo -e "${RED}Database restore failed${NC}"
    rm -rf "$TEMP_DIR"
    exit 1
fi

rm -rf "$TEMP_DIR"

# Optional config restore
CONFIG_BACKUP="${BACKUP_FILE//db/config}"
CONFIG_BACKUP="${CONFIG_BACKUP%.sql.gz}.tar.gz"
if [ -f "$CONFIG_BACKUP" ]; then
    read -p "Restore configuration files too? (yes/no): " restore_config
    if [ "$restore_config" = "yes" ]; then
        echo "Restoring configuration..."
        cp backend/src/main/resources/application.properties backend/src/main/resources/application.properties.bak 2>/dev/null || true
        tar -xzf "$CONFIG_BACKUP" && echo -e "${GREEN}Configuration restored${NC}" || echo -e "${YELLOW}Config restore had issues${NC}"
    fi
fi

echo
echo -e "${GREEN}Restore complete!${NC}"
echo "You can now restart the application."