# Contributing to Veyloria

Thank you for your interest in contributing to Veyloria! We welcome contributions from the community to help improve and
expand this project.

## Table of Contents

1. [Code of Conduct](#code-of-conduct)
2. [Getting Started](#getting-started)
3. [How to Contribute](#how-to-contribute)
4. [Development Guidelines](#development-guidelines)
5. [Submitting Changes](#submitting-changes)
6. [Style Guidelines](#style-guidelines)
7. [Testing](#testing)
8. [Documentation](#documentation)

## Code of Conduct

By participating in this project, you agree to abide by our code of conduct:

- Be respectful and inclusive
- Welcome newcomers and help them get started
- Focus on constructive criticism
- Accept feedback gracefully
- Prioritize the community's best interests

## Getting Started

1. **Fork the repository** on GitHub
2. **Clone your fork** locally:
   ```bash
   git clone <your-fork-url>
   cd Veyloria
   ```
3. **Add upstream remote**:
   ```bash
   git remote add upstream <upstream-repository-url>
   ```
4. **Create a branch** for your feature:
   ```bash
   git checkout -b feature/your-feature-name
   ```

Use Node.js 24 LTS alongside Bun: the Vite development server and test tooling
need Node compatibility. Follow the [quick start](README.md#quick-start) for JWT,
credentials, optional GPU speech and real inference setup. Report vulnerabilities
according to [SECURITY.md](SECURITY.md), rather than posting secrets in issues.

Hidden directories must remain untracked. Run
`python3 scripts/check-public-tree.py` before submitting changes. CI templates
live in [ci/](ci/README.md); they are inactive until a CI service is configured.

## How to Contribute

### Reporting Bugs

Before creating a bug report, please check existing issues to avoid duplicates.

**When reporting bugs, include:**

- Clear, descriptive title
- Steps to reproduce the issue
- Expected behavior
- Actual behavior
- System information (OS, Java / Bun versions, browser, etc.)
- Relevant logs or error messages

### Suggesting Features

We welcome feature suggestions. When proposing new features:

- Check if it's already been suggested
- Clearly describe the feature and its benefits
- Provide use cases
- Consider implementation complexity

### Code Contributions

1. **Small fixes**: Typos, small bug fixes can be submitted directly
2. **Large changes**: Please open an issue first to discuss
3. **New features**: Discuss in an issue before implementing

## Development Guidelines

### Project Structure

See [repository organization](docs/repository-layout.md) for ownership and rules
for generated files, local configuration, and historical documentation.

```
Veyloria/
├── backend/                              # Quarkus 3.36 backend
│   ├── build.gradle.kts                  # Gradle Kotlin DSL
│   ├── settings.gradle.kts
│   ├── gradle.properties                 # Pins Quarkus 3.36 platform
│   ├── gradlew / gradle/                 # Gradle wrapper
│   ├── scripts/
│   │   └── reset-database.sh
│   └── src/main/
│       ├── java/com/eonmux/chatllm/
│       │   ├── lifecycle/                # StartupEvent / ShutdownEvent observers
│       │   ├── config/                   # AppConfig (@ConfigProperty), RoleplayEventAddresses
│       │   ├── agents/                   # Focused decisions, generation and pipeline orchestration
│       │   ├── map/                      # Procedural maps, local stages and journeys
│       │   ├── models/                   # Domain models and DTOs
│       │   ├── services/                 # CDI domain services
│       │   │   └── auth/                 # JWTAuthProvider, AuthContext
│       │   ├── graphql/
│       │   │   ├── auth/                 # GraphQLAuthContext (@RequestScoped)
│       │   │   ├── resolvers/            # Code-first GraphQL resolvers (@Query/@Mutation/@Subscription)
│       │   │   └── scalars/              # Scalar conversion helpers
│       │   ├── llm/                      # llama.cpp integration + GGUF parser
│       │   ├── comfyui/                  # Image generation
│       │   ├── messaging/                # Event-bus bridges (LLMBridge, RedisCacheBridge)
│       │   └── utils/                    # Cache, validation, Base64, helpers
│       └── resources/
│           ├── application.properties    # Quarkus + chatllm.* configuration
│           └── db/schema.sql             # Canonical fresh-install schema
├── frontend/                             # React 18 + TypeScript + Vite 8 (Bun runtime)
│   ├── package.json                      # "engines": { "bun": ">=1.0.0" }
│   ├── bun.lock
│   ├── vite.config.ts
│   └── src/
│       ├── components/                   # Chat, roleplay, map and shared UI
│       ├── pages/                        # Lazy-loaded application routes
│       ├── stores/                       # Zustand stores
│       ├── hooks/                        # Custom hooks
│       ├── graphql/                      # operations, roleplay-operations, types, utils
│       └── lib/                          # apollo-client, websocket-client, storeHelpers
├── services/tts/                         # Ming Voice Design adapter and tests
├── docs/                                 # Current guides and historical records
├── scripts/                              # Operational scripts (health-check, backup, restore)
├── monitoring/                           # Prometheus config + Grafana provisioning
└── docker-compose.yml                    # Data services, app, Ming and monitoring
```

GraphQL schema is **code-first** — generated from annotated `@GraphQLApi` classes at runtime. There is no checked-in GraphQL schema snapshot; treat the annotated Java classes and live introspection as the source of truth. The canonical database shape lives in `schema.sql`; bundled idempotent upgrades and guarded column additions in `ApplicationLifecycle` keep existing installations compatible. See [database maintenance](docs/DEVELOPER_GUIDE.md#schema-changes).

### Setting Up Development Environment

1. **Install prerequisites**:
   - Java 21+ (via SDKMAN: `sdk install java 21.0.5-tem`)
   - Bun 1.4.5+ (via `curl -fsSL https://bun.sh/install | bash`)
   - Docker & Docker Compose
   - MySQL 8.0+
   - Redis 7+

   No system-wide Gradle install needed — use `./gradlew`.

2. **Configure IDE**:
   - **Backend (IntelliJ IDEA)**: Open `backend/build.gradle.kts` as a Gradle project; set Project SDK to Java 21. Install the GraphQL and Lombok plugins.
   - **Frontend (VS Code)**: Install ESLint, Prettier, Tailwind CSS, Apollo GraphQL, and Bun extensions.

3. **Environment setup**:
   ```bash
   cp .env.example .env
   # Edit .env with your configuration; key: JWT_SECRET, DATABASE_PASSWORD, LLM_BINARY_PATH, LLM_MODEL_PATH
   ```

### Building and Running

```bash
# Backend — dev mode with hot reload
cd backend
./gradlew quarkusDev

# Backend — production uber-jar
./gradlew clean build -x test
java -jar build/chatllm-quarkus-3.0.0-runner.jar

# Backend — run tests
./gradlew test

# Backend — enable debug logging (ad-hoc)
LOG_LEVEL=DEBUG ./gradlew quarkusDev

# Frontend — install and dev
cd frontend
bun install --frozen-lockfile
bun run dev                              # http://localhost:3000 (proxies /graphql to :8300)

# Frontend — production build
bun run build                            # typechecks then builds

# Docker — full stack
docker compose up -d --build mysql redis backend frontend # core; configure .env first
docker compose --profile llm up -d llama-server backend # configured optional inference
```

## Submitting Changes

### Pull Request Process

1. **Update your fork**:
   ```bash
   git fetch upstream
   git remote set-head upstream --auto
   git rebase upstream/HEAD
   ```

2. **Make your changes**:
   - Write clean, readable code
   - Add/update tests as needed
   - Update documentation

3. **Commit your changes**:
   ```bash
   git add .
   git commit -m "feat: add new feature X"
   ```

   Use conventional commits:
   - `feat:` New feature
   - `fix:` Bug fix
   - `docs:` Documentation changes
   - `style:` Code style changes
   - `refactor:` Code refactoring
   - `test:` Test additions/changes
   - `chore:` Build process/auxiliary changes
   - `perf:` Performance improvement
   - `ci:` CI configuration

4. **Push to your fork**:
   ```bash
   git push origin feature/your-feature-name
   ```

5. **Create Pull Request**:
   - Go to GitHub and create PR from your fork
   - Fill in the PR template
   - Link related issues

### PR Requirements

- Clear description of changes
- Backend tests pass with integration tests enabled (`RUN_INVENTORY_DB_TESTS=true ./gradlew test` from `backend/`)
- Frontend tests pass (`bun run test -- --run`), typechecks (`bun run typecheck`), lints (`bun run lint`)
- Code follows style guidelines
- Documentation updated if needed
- No merge conflicts

## Style Guidelines

### Backend (Java + Quarkus + Mutiny)

```java
package com.eonmux.chatllm.example;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.mysqlclient.MySQLPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Services are CDI beans — @ApplicationScoped, fields @Inject'd.
 * Public methods return Mutiny Uni<T> / Multi<T> and never block.
 */
@ApplicationScoped
public class ExampleService {

    @Inject
    MySQLPool pool;

    public Uni<String> lookupName(String id) {
        return pool.preparedQuery("SELECT displayName FROM user WHERE userUUID = ?")
                   .execute(Tuple.of(id))
                   .onItem().transform(rs -> rs.iterator().hasNext()
                       ? rs.iterator().next().getString("displayName")
                       : null);
    }
}
```

**Key conventions**
- `@ApplicationScoped` + `@Inject` (no constructors)
- Reactive everything: `Uni<T>` / `Multi<T>`, never `Future`, never blocking
- Parameterized SQL only; no string concatenation
- Custom JWT via `JWTAuthProvider` + request-scoped `GraphQLAuthContext` (the project does not use `quarkus-smallrye-jwt`)
- WebSocket auth: read from `connection_init.payload.authorization`, not HTTP headers
- Event-bus bridges for LLM / Redis live in `messaging.LLMBridge` and `messaging.RedisCacheBridge`

### Frontend (TypeScript + React)

```typescript
// Typed functional components; use Apollo + Zustand
interface UserProps {
  id: string;
  name: string;
}

export const UserProfile: React.FC<UserProps> = ({ id, name }) => {
  const { data, error } = useQuery(GET_USER, { variables: { id } });

  if (error) return <ErrorMessage error={error} />;
  return <div className="user-profile">{data?.user.displayName ?? name}</div>;
};

// Zustand stores use immer + shared helpers
import { setLoading, clearLoadingAndError, handleStoreError } from '@/lib/storeHelpers';
```

**Key conventions**
- Immutable updates via Immer drafts — never mutate Zustand state in place
- Use `@/lib/storeHelpers` for loading/error transitions
- Roleplay GraphQL lives in `@/graphql/roleplay-operations`; shared types in `@/graphql/types`
- The single path alias `@/` maps to `frontend/src/` in TypeScript, Vite and Vitest.
- Subscriptions go through `@/lib/websocket-client` (dedup + reconnect built in) — don't open parallel WS connections

### GraphQL

```graphql
# Fragments for reuse
fragment UserInfo on User {
  id
  email
  displayName
}

# Input types for mutations
mutation CreateAdventure($input: CreateRoleplayConversationInput!) {
  createRoleplayConversation(input: $input) { id title }
}
```

## Testing

### Backend

See [SSEStreamOrderingTest](backend/src/test/java/com/eonmux/chatllm/services/SSEStreamOrderingTest.java)
for a concurrency regression test and
[InventoryServiceDatabaseTest](backend/src/test/java/com/eonmux/chatllm/services/InventoryServiceDatabaseTest.java)
for disposable MySQL integration tests. These use the actual services and schema.

```bash
# From the repository root; Docker is required for the integration suite.
RUN_INVENTORY_DB_TESTS=true ./backend/gradlew -p backend test
./backend/gradlew -p backend test --tests '*SSEStreamOrderingTest'
```

### Frontend

See [DataExportImport.test.tsx](frontend/src/components/DataExportImport.test.tsx)
for an Apollo/Testing Library regression test. Use `@/` for imports from `src/`.

```bash
cd frontend
bun run test -- --run                    # All tests, then exit
bun run test -- --run DataExportImport   # Filter by filename
bun run test:coverage                    # Coverage (watch mode locally)
bun run test:ui                          # Vitest UI
```

## Documentation

### Code Documentation

- Add JavaDoc for non-obvious public APIs (parameters, return values, thrown exceptions)
- Keep comments explaining **why**, not **what** — names should carry the what
- Update `docs/API.md` when adding / renaming GraphQL operations
- Update `docs/DEVELOPER_GUIDE.md` when changing developer workflow
- Update `README.md` when adding top-level features

### User Documentation

- Update `README.md` for user-facing changes
- Update architectural docs for structural changes
- Include examples where they help

### Commit Messages

Good commit message:

```
feat: add support for custom prompt templates

- Added PromptTemplate enum with 50+ template types
- Implemented auto-detection via GGUF metadata -> server API -> filename
- Per-agent template overrides exposed through chatllm.agent.* config
- Added tests for template detection

Closes #123
```

## Questions?

- Open an issue for questions
- Join discussions in existing issues
- Check existing documentation first: [README.md](README.md), [docs/DEVELOPER_GUIDE.md](docs/DEVELOPER_GUIDE.md), [documentation index](docs/README.md)

Thank you for contributing to Veyloria!
