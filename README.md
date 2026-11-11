# AI Workforce Platform

Platform for running virtual organizations staffed by human employees and supervised AI agents.

| Path | Content | Status |
| --- | --- | --- |
| [`apps/desktop`](apps/desktop/README.md) | Desktop application (Angular + Electron) | Phase A: UI on mock data |
| `apps/vscode-extension` | VS Code extension | Not started |
| `services/core-api` | Core backend (Java, Spring Boot) | Not started |
| `services/ai-orchestrator` | AI orchestration engine (Python, FastAPI, LangGraph) | Not started |
| `services/local-runner` | Local model runner | Not started |
| `contracts` | OpenAPI and event contracts | Not started |
| `infra` | Docker and deployment | Not started |
| `docs` | Architecture, ADRs, API and database docs | Not started |

## Quick start (desktop)

```bash
cd apps/desktop
npm install
npm run ui:install
npm run dev
```
