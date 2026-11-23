#!/bin/bash
set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║   Veyloria Production Deployment        ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"

# Check if running as root (for systemd service installation)
if [[ $EUID -eq 0 ]]; then
   echo -e "${RED}This script should not be run as root!${NC}" 
   exit 1
fi

# Build backend
echo -e "\n${GREEN}[1/4] Building backend...${NC}"
cd backend
./gradlew clean build -x test -Dquarkus.package.jar.type=uber-jar

if [ $? -ne 0 ]; then
    echo -e "${RED}Backend build failed!${NC}"
    exit 1
fi

# Build frontend with the Bun package manager. Node 24 is needed for development tooling.
echo -e "\n${GREEN}[2/4] Building frontend...${NC}"
cd ../frontend
bun install --frozen-lockfile
bun run build

if [ $? -ne 0 ]; then
    echo -e "${RED}Frontend build failed!${NC}"
    exit 1
fi

# Create deployment directory
echo -e "\n${GREEN}[3/4] Creating deployment structure...${NC}"
cd ..
DEPLOY_DIR="chatllm-deploy-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DEPLOY_DIR"

# Copy backend JAR
BACKEND_JAR=$(ls backend/build/chatllm-quarkus-*-runner.jar 2>/dev/null | head -n1)
if [ -z "$BACKEND_JAR" ]; then
    echo -e "${RED}Backend JAR not found in backend/build/${NC}"
    exit 1
fi
cp "$BACKEND_JAR" "$DEPLOY_DIR/chatllm-backend.jar"

# Include the repository license in the release directory.
cp LICENSE "$DEPLOY_DIR/LICENSE"

# Copy frontend dist
cp -r frontend/dist "$DEPLOY_DIR/frontend"

# Quarkus reads configuration from application.properties + environment
# variables, not a config.json. Emit a .env template the operator fills in
# before starting the service.
cat > "$DEPLOY_DIR/chatllm.env" << 'EOF'
# Required. Used by application.properties default overrides.
QUARKUS_HTTP_PORT=8300
QUARKUS_HTTP_HOST=0.0.0.0

DATABASE_HOST=localhost
DATABASE_PORT=3306
DATABASE_NAME=chatllm
DATABASE_USER=chatllm
DATABASE_PASSWORD=CHANGE_ME
DATABASE_POOL_SIZE=20

REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=CHANGE_ME
REDIS_DATABASE=0
REDIS_POOL_SIZE=10

# JWT (milliseconds)
JWT_SECRET=CHANGE_ME_AT_LEAST_32_CHARACTERS
JWT_ISSUER=chatllm
JWT_ACCESS_TOKEN_EXPIRY=14400000
JWT_REFRESH_TOKEN_EXPIRY=604800000

ALLOWED_ORIGINS=https://veyloria.example.com
ENVIRONMENT=production
DEVELOPMENT_MODE=false
SHOW_GRAPHQL_ERROR_DETAILS=false
LOG_LEVEL=INFO
ENABLE_GRAPHQL_PLAYGROUND=false
AUTH_BYPASS_ENABLED=false
RATE_LIMIT_PER_MINUTE=600

LLM_ENABLED=true
LLM_MOCK_MODE=false
LLM_EXTERNAL_SERVER=false
LLM_BINARY_PATH=/opt/llama.cpp/llama-server
LLM_MODEL_PATH=/opt/models/your-model.gguf
EOF

# Create systemd service file
cat > "$DEPLOY_DIR/chatllm.service" << 'EOF'
[Unit]
Description=Veyloria Backend Service
After=network.target mysql.service redis.service

[Service]
Type=simple
User=chatllm
WorkingDirectory=/opt/chatllm
Environment="JAVA_OPTS=-Xmx2G -Xms512M -Duser.timezone=UTC"
EnvironmentFile=/opt/chatllm/chatllm.env
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/chatllm/chatllm-backend.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# Create nginx configuration
cat > "$DEPLOY_DIR/chatllm.nginx" << 'EOF'
server {
    listen 80;
    server_name chatllm.local;
    
    # Frontend
    location / {
        root /opt/chatllm/frontend;
        try_files $uri $uri/ /index.html;
        
        # Security headers
        add_header X-Frame-Options "SAMEORIGIN" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header X-XSS-Protection "1; mode=block" always;
    }
    
    # Backend API & GraphQL (HTTP + WebSocket on the same /graphql path)
    location /graphql {
        proxy_pass http://localhost:8300;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        proxy_read_timeout 86400;
    }

    # Health check endpoint
    location /health {
        proxy_pass http://localhost:8300;
    }
}
EOF

# Create deployment script
cat > "$DEPLOY_DIR/install.sh" << 'EOF'
#!/bin/bash

echo "Installing Veyloria to /opt/chatllm..."

# Create chatllm user if not exists
if ! id "chatllm" &>/dev/null; then
    sudo useradd -r -s /bin/false chatllm
fi

# Create installation directory
sudo mkdir -p /opt/chatllm
sudo cp chatllm-backend.jar /opt/chatllm/
sudo cp chatllm.env /opt/chatllm/
sudo chmod 640 /opt/chatllm/chatllm.env
sudo cp -r frontend /opt/chatllm/
sudo chown -R chatllm:chatllm /opt/chatllm

# Install systemd service
sudo cp chatllm.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable chatllm

# Install nginx config
sudo cp chatllm.nginx /etc/nginx/sites-available/chatllm
sudo ln -sf /etc/nginx/sites-available/chatllm /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx

echo "Installation complete!"
echo "Start the service with: sudo systemctl start chatllm"
echo "Check status with: sudo systemctl status chatllm"
echo "View logs with: sudo journalctl -u chatllm -f"
EOF

chmod +x "$DEPLOY_DIR/install.sh"

# Create README
cat > "$DEPLOY_DIR/README.md" << 'EOF'
# Veyloria Production Deployment

## Prerequisites
- Java 21+
- MySQL 8.0+
- Redis 6.0+
- Nginx
- systemd

## Installation
1. Edit `chatllm.env` with your real secrets (JWT_SECRET, DATABASE_PASSWORD, REDIS_PASSWORD, LLM paths)
2. Update nginx configuration in `chatllm.nginx` with your domain
3. Run `sudo ./install.sh`

Quarkus reads configuration from `application.properties` (embedded in the uber-jar) and from the environment. There is no external JSON config file.

## Management Commands
```bash
# Start service
sudo systemctl start chatllm

# Stop service
sudo systemctl stop chatllm

# Restart service
sudo systemctl restart chatllm

# View status
sudo systemctl status chatllm

# View logs
sudo journalctl -u chatllm -f

# View last 100 lines
sudo journalctl -u chatllm -n 100
```

## Database Setup
Ensure MySQL database and user are created:
```sql
CREATE DATABASE IF NOT EXISTS chatllm;
CREATE USER IF NOT EXISTS 'chatllm'@'localhost' IDENTIFIED BY 'your_password';
GRANT ALL PRIVILEGES ON chatllm.* TO 'chatllm'@'localhost';
FLUSH PRIVILEGES;
```

## Security Notes
1. Change `JWT_SECRET` in `chatllm.env` (must be >= 32 chars)
2. Update `DATABASE_PASSWORD` and `REDIS_PASSWORD`
3. Confirm `AUTH_BYPASS_ENABLED=false` and `ENABLE_GRAPHQL_PLAYGROUND=false` in production
4. Configure firewall — expose only :80 / :443 (nginx) externally; :8300 must stay loopback
5. Enable SSL/TLS in production
6. Set up regular backups (see `scripts/backup.sh`)

## Monitoring
- Health check: http://your-domain/health (also `/health/live`, `/health/ready`)
- Metrics: http://localhost:8300/metrics (internal only — Prometheus scrape target)
EOF

echo -e "\n${GREEN}[4/4] Deployment package created!${NC}"
echo -e "${BLUE}════════════════════════════════════════${NC}"
echo -e "${GREEN}✓ Deployment package: ${DEPLOY_DIR}${NC}"
echo -e "${YELLOW}Next steps:${NC}"
echo -e "  1. Copy ${DEPLOY_DIR} to your production server"
echo -e "  2. Review and fill in secrets in chatllm.env"
echo -e "  3. Run sudo ./install.sh on the server"
echo -e "${BLUE}════════════════════════════════════════${NC}"