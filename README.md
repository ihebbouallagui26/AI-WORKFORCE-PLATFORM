# AI Workforce Platform

Platform for running virtual organizations staffed by human employees and supervised AI agents.

| Path | Content | Status |
| --- | --- | --- |
| [`apps/desktop`](apps/desktop/README.md) | Desktop application (Angular + Electron) | Phase A: UI on mock data |
| `apps/vscode-extension` | VS Code extension | Not started |
| [`services/core-api`](services/core-api/README.md) | Core backend (Java 21, Spring Boot 4) | Sprint B0: foundation (no business endpoints, no authentication yet) |
| `services/ai-orchestrator` | AI orchestration engine (Python, FastAPI, LangGraph) | Not started |
| `services/local-runner` | Local model runner | Not started |
| [`contracts/openapi`](contracts/openapi/README.md) | OpenAPI contracts | Core API v1 (health) |
| `contracts/events` | Event contracts | Not started |
| [`infra/docker`](infra/docker/compose.yaml) | Docker Compose: PostgreSQL 18 + pgvector, Core API | Local development |
| [`docs`](docs) | Architecture, security, ADRs, database conventions, reports | Ongoing |

## Quick start (desktop)

```bash
cd apps/desktop
npm install
npm run ui:install
npm run dev
```

## Quick start (Core API)

```bash
cp infra/docker/.env.example infra/docker/.env      # then set DB_PASSWORD
docker compose -f infra/docker/compose.yaml --env-file infra/docker/.env up -d postgres
cd services/core-api && ./mvnw spring-boot:run      # http://localhost:8080/api/v1/health
```

## Documentation

- [Core backend architecture](docs/architecture/CORE_BACKEND_ARCHITECTURE.md)
- [Security architecture](docs/architecture/SECURITY_ARCHITECTURE.md)
- [Architecture decision records](docs/adr/README.md)
- [Database conventions](docs/database/DATABASE_CONVENTIONS.md)
- Reports: [Phase A audit](docs/PHASE_A_TECHNICAL_AUDIT.md) · [Sprint B0](docs/SPRINT_B0_IMPLEMENTATION_REPORT.md)
