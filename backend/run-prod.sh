#!/bin/bash

# Production run script for Veyloria backend (Quarkus + Gradle)

echo "🚀 Starting Veyloria Backend in Production Mode..."

# Check if JWT secret is set
if [ -z "$JWT_SECRET" ]; then
    echo "❌ JWT_SECRET environment variable must be set for production"
    echo "   Generate one with: openssl rand -base64 64"
    exit 1
fi

# Set production environment
export ENVIRONMENT=production
export DEVELOPMENT_MODE=false
export SHOW_GRAPHQL_ERROR_DETAILS=false
export ENABLE_GRAPHQL_PLAYGROUND=false

# Check required environment variables
required_vars=(
    "DATABASE_HOST"
    "DATABASE_NAME"
    "DATABASE_USER"
    "DATABASE_PASSWORD"
    "REDIS_HOST"
)

# LLM vars are only required when the LLM is enabled
if [ "${LLM_ENABLED:-false}" = "true" ]; then
    required_vars+=("LLM_MODEL_PATH")
fi

for var in "${required_vars[@]}"; do
    if [ -z "${!var}" ]; then
        echo "❌ Required environment variable $var is not set"
        exit 1
    fi
done

# Create necessary directories
mkdir -p logs
mkdir -p /tmp/uploads

# Locate the built Quarkus uber-jar
JAR_FILE=$(ls build/chatllm-quarkus-*-runner.jar 2>/dev/null | head -n1)

# Build if JAR doesn't exist
if [ -z "$JAR_FILE" ]; then
    echo "🔨 Building production JAR..."
    ./gradlew clean build -x test -Dquarkus.package.jar.type=uber-jar
    if [ $? -ne 0 ]; then
        echo "❌ Build failed"
        exit 1
    fi
    JAR_FILE=$(ls build/chatllm-quarkus-*-runner.jar 2>/dev/null | head -n1)
fi

if [ -z "$JAR_FILE" ]; then
    echo "❌ Runner JAR not found after build"
    exit 1
fi

# Run with production JVM settings
echo "✅ Starting application in production mode..."
java \
  -server \
  -Xmx4G \
  -Xms2G \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=./logs/ \
  -Djava.awt.headless=true \
  -Dfile.encoding=UTF-8 \
  -jar "$JAR_FILE"
