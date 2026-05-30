# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AI Fusion Video (融光) — an AI-driven video creation platform with a Spring Boot backend and Next.js frontend.

## Development Commands

### Backend (Java 21 + Spring Boot)

```bash
# Start dev middleware (MySQL 43306, Redis 46379)
cd ai-fusion-video && docker compose -f docker-compose-middleware.yml up -d

# Build (skip tests)
cd ai-fusion-video && ./mvnw package -DskipTests

# Run
cd ai-fusion-video && ./mvnw spring-boot:run

# Run a single test
cd ai-fusion-video && ./mvnw test -Dtest=ClassName#methodName
```

Backend runs on port 18080. Active profile: `local` (configured in `application-local.yaml`).

### Frontend (Next.js + pnpm)

```bash
cd ai-fusion-video-web && pnpm install
cd ai-fusion-video-web && pnpm dev      # dev server
cd ai-fusion-video-web && pnpm build    # production build
cd ai-fusion-video-web && pnpm lint     # eslint
```

API base URL defaults to `http://localhost:18080`, override with `NEXT_PUBLIC_API_BASE_URL`.

### Full Stack (Docker)

```bash
docker compose up -d                    # pull prebuilt images
docker compose -f docker-compose.build.yml up -d  # build from source
```

## Architecture

### Backend (`ai-fusion-video/`)

Spring Boot 3.5 application. Package: `com.stonewu.fusion`.

- **controller/** — REST API organized by domain (ai, asset, generation, project, script, storage, storyboard, task, team)
- **service/** — business logic, mirrors controller domains
- **entity/** — MyBatis-Plus entities with logical delete (`deleted` field)
- **mapper/** — MyBatis-Plus data access
- **convert/** — MapStruct DTO converters
- **infrastructure/** — external service adapters (AI providers, storage)
- **security/** — Spring Security + JWT auth
- **config/** — Spring configuration beans

Key integrations: Spring AI (OpenAI, Anthropic, Ollama, Vertex AI, DashScope), AgentScope (agent pipelines), S3-compatible storage (AWS/OSS/COS/MinIO), FFmpeg (video composition).

DB migrations: Flyway, located in `src/main/resources/db/migration/`.

### Frontend (`ai-fusion-video-web/`)

Next.js 16 App Router with TypeScript, React 19, Tailwind CSS 4.

- **app/(auth)/** — login, register, setup, forgot-password
- **app/(dashboard)/** — main application shell
- **app/(dashboard)/projects/[id]/** — project workspace (scripts, storyboards, assets, members)
- **lib/api/** — axios HTTP client (`client.ts` exports `http` and `API_BASE_URL`)
- **lib/stores/** — zustand state management
- **components/ui/** — shadcn components (do not modify directly)

State: zustand. UI primitives: shadcn + @base-ui/react. Animations: framer-motion.

## Coding Standards

### Java

- Always import classes at the top — never use fully-qualified names inline
- Use `@Cacheable`/`@CacheEvict` on all services; clear cache on create/update/delete
- Use `SecurityUtils.requireCurrentUserId()` for current user — never hardcode userId

### Frontend

- Install shadcn components via `pnpm dlx shadcn@latest add <component>` in the web dir; never edit `components/ui/` source
- Dialogs: `max-h-[calc(100vh-2rem)] flex flex-col overflow-hidden`, shrink-0 header/footer, `min-h-0 overflow-y-auto` body
- Dashboard layout: `h-screen overflow-hidden flex flex-col` with fixed header/sidebar, `overflow-auto` main content
- Full-height pages use `h-full` — never `h-[calc(100vh-Xrem)]`
- Popups inside `overflow-hidden` containers: use React Portal + `getBoundingClientRect()`
- Select component uses `@base-ui/react` (style: base-maia) — requires `items` prop, `SelectValue` uses `placeholder` attribute
