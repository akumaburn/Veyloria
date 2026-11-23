#!/bin/bash
set -euo pipefail

# Veyloria Backup Script — Quarkus v3

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== Veyloria Backup Script ==="
echo "Time: $(date)"
echo

BACKUP_DIR="${BACKUP_DIR:-$SCRIPT_DIR/backups}"
DB_NAME="${DATABASE_NAME:-chatllm}"
DB_USER="${DATABASE_USER:-chatllm}"
DB_PASS="${DATABASE_PASSWORD:-chatllm}"
DB_HOST="${DATABASE_HOST:-localhost}"
DB_PORT="${DATABASE_PORT:-3306}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"

mkdir -p "$BACKUP_DIR"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")

# Database backup
echo "Backing up database..."
DUMP_FILE="$BACKUP_DIR/chatllm_db_$TIMESTAMP.sql"
if mysqldump -u "$DB_USER" -p"$DB_PASS" -h "$DB_HOST" -P "$DB_PORT" "$DB_NAME" > "$DUMP_FILE" 2>/dev/null; then
    gzip "$DUMP_FILE"
    echo "Database backed up to: ${DUMP_FILE}.gz"
else
    echo "Database backup failed (is MySQL running?)"
    exit 1
fi

# Configuration backup
echo "Backing up configuration..."
CONFIG_BACKUP="$BACKUP_DIR/chatllm_config_$TIMESTAMP.tar.gz"
tar -czf "$CONFIG_BACKUP" \
    -C "$SCRIPT_DIR" \
    backend/src/main/resources/application.properties \
    .env.example \
    docker-compose.yml \
    2>/dev/null && echo "Configuration backed up to: $CONFIG_BACKUP" || echo "Configuration backup skipped (some files missing)"

# Logs backup (optional)
if [ -d "$SCRIPT_DIR/logs" ]; then
    echo "Backing up logs..."
    LOGS_BACKUP="$BACKUP_DIR/chatllm_logs_$TIMESTAMP.tar.gz"
    tar -czf "$LOGS_BACKUP" -C "$SCRIPT_DIR" logs/ 2>/dev/null && echo "Logs backed up to: $LOGS_BACKUP"
fi

# Clean old backups
echo "Cleaning backups older than $RETENTION_DAYS days..."
find "$BACKUP_DIR" -name "chatllm_*.gz" -type f -mtime "+$RETENTION_DAYS" -delete 2>/dev/null || true

TOTAL_SIZE=$(du -sh "$BACKUP_DIR" | cut -f1)
echo
echo "Backup complete!"
echo "Total backup size: $TOTAL_SIZE"
echo "Backup location: $BACKUP_DIR/"

echo
echo "Recent backups:"
ls -lht "$BACKUP_DIR" | head -n 6