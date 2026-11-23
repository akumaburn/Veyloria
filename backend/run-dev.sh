#!/bin/bash

# Development run script for Veyloria backend (Quarkus + Gradle)

echo "🚀 Starting Veyloria Backend in Development Mode..."

# Set environment variables for development
export DEVELOPMENT_MODE=true
export JWT_SECRET="development-secret-change-in-production"
export DATABASE_HOST=localhost
export DATABASE_NAME=chatllm
export DATABASE_USER=chatllm
export DATABASE_PASSWORD=chatllm
export REDIS_HOST=localhost
export LLM_MODEL_PATH="./models/llama-3.2-3b-instruct-q8_0.gguf"
export ENABLE_GRAPHQL_PLAYGROUND=true

# Ensure database is running
echo "📊 Checking database connection..."
mysql -h localhost -u chatllm -pchatllm -e "SELECT 1" chatllm > /dev/null 2>&1
if [ $? -ne 0 ]; then
    echo "❌ Database is not accessible. Please ensure MySQL is running and database 'chatllm' exists."
    echo "   You can create it with: mysql -u root -p -e 'CREATE DATABASE IF NOT EXISTS chatllm'"
    exit 1
fi

# Ensure Redis is running
echo "🔄 Checking Redis connection..."
redis-cli ping > /dev/null 2>&1
if [ $? -ne 0 ]; then
    echo "❌ Redis is not running. Please start Redis with: sudo systemctl start redis"
    exit 1
fi

# Create uploads directory if it doesn't exist
mkdir -p /tmp/uploads

# Run the application in Quarkus dev mode (live reload, debug on :5005)
echo "✅ Starting application (Quarkus dev mode)..."
./gradlew quarkusDev \
  -Djava.awt.headless=true \
  --console=plain
