# SQL Agent

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot 3.2.0](https://img.shields.io/badge/Spring%20Boot-3.2.0-green.svg)](https://spring.io/projects/spring-boot)
[![LangChain4j 0.34.0](https://img.shields.io/badge/LangChain4j-0.34.0-blue.svg)](https://docs.langchain4j.dev)

[Chinese README / 中文说明](README.zh-CN.md) | [Contributing Guide](CONTRIBUTING.md) | [Security Policy](SECURITY.md)

This is an open-source SQL optimization agent for MySQL workloads, built with `Spring Boot`, `LangChain4j`, and an `OpenAI-compatible API`.

The project currently moves along one core workflow and one experimental workflow:

- `Optimization Analysis` is the primary feature and the best place to start. It analyzes a single SQL statement, generates candidate optimization plans, validates them with system-side execution, and returns the best plan with evidence.
- `Single-table multi-index design` is still in testing. The current UI and API expose an experimental index recommendation workflow for grouped SQL statements, and it should still be treated as an evolving prototype rather than a stable flagship feature.

## Language Support

- English is the primary open-source entry point for prompts, architecture, and contributor-facing documentation.
- Chinese documentation is kept in [README.zh-CN.md](README.zh-CN.md).
- The web UI is being upgraded toward bilingual switching for demo and community use.

## Why This Project

Most LLM-based SQL tools stop at "advice". This project aims to go further:

- Evidence-first: start from `EXPLAIN`, schema metadata, statistics, and execution traces.
- System-validated: candidate plans are not only proposed by the model, but also executed, compared, and scored by the application.
- Demo-safe by default: destructive or mutation-style endpoints are disabled in the default open-source setup.

## Screenshots

## Live Demo

[SQL Agent Demo](https://guohaolong.top/SQLAgent)

### Optimization Analysis Workflow

![Optimization Analysis](img.png)

- Input SQL query and receive AI-powered optimization suggestions
- Real-time streaming of execution progress
- Before/after EXPLAIN plan comparison
- Candidate plan workbench with performance metrics

### Database Schema Browser

![Database Schema Browser](img_1.png)

- Browse all tables in your database
- View table structure and existing indexes
- Analyze statistics and data distribution

### Experimental Index Design

![Experimental Index Design](img_2.png)

- Multi-SQL workload analysis
- Index coverage and recommendation reasoning
- DDL suggestions for index optimization

---

## Current Focus

### 1. Optimization Analysis

This is the most polished workflow in the project today, and it is the part open-source users should try first.

In this workflow, the system:

- accepts a single `SELECT` / `WITH` query
- lets the model generate 1 to 5 candidate optimization plans
- measures a baseline
- validates rewritten SQL and temporary index plans in a sandbox
- compares results for correctness
- scores candidates and selects the best one
- streams execution progress to the UI
- persists analysis history for replay

Core entry points:

- [OptimizationController.java](src/main/java/com/sqlagent/controller/OptimizationController.java)
- [UnifiedOptimizationService.java](src/main/java/com/sqlagent/service/UnifiedOptimizationService.java)
- [PlanGenerator.java](src/main/java/com/sqlagent/generator/PlanGenerator.java)
- [PlanExecutionEngine.java](src/main/java/com/sqlagent/engine/PlanExecutionEngine.java)
- [PlanEvaluator.java](src/main/java/com/sqlagent/evaluator/PlanEvaluator.java)

### 2. Experimental Index Design

The current `/api/analyze/workload` flow is best understood as the experimental foundation for future single-table multi-index design.

What is already there:

- Accepts multiple SQL statements
- Produces a recommended index set
- Shows coverage, reasoning, and suggested DDL
- Useful for demos and internal iteration

Why it is still experimental:

- It does not have the same system-side validation loop as the optimization analysis workflow
- It is still being shaped toward a more focused single-table index design and retirement assistant
- The front-end labels it as experimental because that better reflects its maturity

Related files:

- [WorkloadController.java](src/main/java/com/sqlagent/controller/WorkloadController.java)
- [WorkloadOptimizationService.java](src/main/java/com/sqlagent/service/WorkloadOptimizationService.java)
- [IndexRecommendationEngine.java](src/main/java/com/sqlagent/tools/IndexRecommendationEngine.java)

## Feature Overview

### Primary workflow

- Single SQL optimization analysis
- Streaming execution timeline
- Explain comparison before and after optimization
- Candidate plan workbench
- Baseline measurement and result comparison
- History replay

### Supporting capabilities

- SQL input validation
- Schema inspection
- Table/index browsing
- Statistics and skew analysis
- Temporary index sandbox execution

### Experimental workflow

- Grouped SQL index recommendation
- Early-stage exploration toward single-table multi-index design

## Architecture

```text
Browser UI
  -> REST / SSE Controllers
  -> Optimization Services
  -> LLM Agents + SQL Tools
  -> Execution Engine + Sandbox + Evaluator
  -> MySQL
```

Main modules:

- `controller`: REST and SSE entry points
- `service`: orchestration, history, response assembly, execution traces
- `agent`: LangChain4j agent interfaces
- `tools`: explain, ddl, execute, compare, statistics, covering index, data skew, index recommendation
- `engine`: candidate execution engine
- `sandbox`: temporary index lifecycle management
- `evaluator`: candidate scoring and best-plan selection
- `static`: front-end pages and components

## Safety Model

The repository is configured for open-source usage first, not for direct production write access.

Default runtime flags:

- `SQL_AGENT_DEMO_READ_ONLY=true`
- `SQL_AGENT_MUTATION_ENABLED=false`

When mutation is disabled:

- `/api/apply-optimization` cannot create real indexes
- `/api/index/drop` cannot delete indexes
- the UI hides or disables mutation-oriented actions

Runtime feature endpoint:

- `GET /api/features`

## Quick Start

### Option A: Docker Compose

1. Copy the environment template:

```bash
cp .env.example .env
```

2. Edit `.env` and provide at least:

- `SQL_AGENT_DB_URL`
- `SQL_AGENT_DB_USERNAME`
- `SQL_AGENT_DB_PASSWORD`
- `SQL_AGENT_MODEL_BASE_URL`
- `SQL_AGENT_MODEL_API_KEY`
- `SQL_AGENT_MODEL`

3. Start the stack:

```bash
docker compose up --build
```

4. Open:

```text
http://localhost:8899
```

Notes:

- MySQL loads [schema.sql](src/main/resources/schema.sql) on startup
- keep mutation disabled for open-source demos unless you are in a controlled local environment

### Option B: Local MySQL + Maven

1. Copy the environment template:

```bash
cp .env.example .env
```

2. Prepare a MySQL database and import [schema.sql](src/main/resources/schema.sql)

3. Export the variables from `.env` into your shell

4. Run:

```bash
mvn spring-boot:run
```

## Environment Variables

Important variables:

- `SQL_AGENT_DB_URL`
- `SQL_AGENT_DB_USERNAME`
- `SQL_AGENT_DB_PASSWORD`
- `SQL_AGENT_MODEL_BASE_URL`
- `SQL_AGENT_MODEL_API_KEY`
- `SQL_AGENT_MODEL`
- `SQL_AGENT_AVAILABLE_MODELS`
- `SQL_AGENT_DEMO_READ_ONLY`
- `SQL_AGENT_MUTATION_ENABLED`

See [.env.example](.env.example) for a full example.

## Model Provider Integration

The project uses an `OpenAI-compatible API` contract.

That means you can plug in:

- OpenAI directly
- self-hosted gateways
- proxy services
- model providers that expose OpenAI-compatible chat completion endpoints

## API Summary

### Main optimization workflow

- `POST /api/optimize`
- `POST /api/optimize/stream`

### Experimental index workflow

- `POST /api/analyze/workload`

### Metadata and support endpoints

- `GET /api/models`
- `GET /api/tools`
- `GET /api/optimization-samples`
- `GET /api/features`
- `POST /api/sql/validate`
- `GET /api/health`
- `GET /api/tables`
- `GET /api/table/{tableName}/detail`

### History

- `GET /api/history/list`
- `GET /api/history/{id}`
- `DELETE /api/history/{id}`

## Front-end Positioning

The UI intentionally reflects the current maturity of each workflow:

- `Optimization Analysis` is presented as the main experience
- the index-design page is explicitly marked as experimental / testing-stage
- table browsing remains read-only by default in open-source mode

Main front-end files:

- [index.html](src/main/resources/static/index.html)
- [analyze.js](src/main/resources/static/js/pages/analyze.js)
- [workload.js](src/main/resources/static/js/pages/workload.js)
- [stream.js](src/main/resources/static/js/services/stream.js)
- [results.js](src/main/resources/static/js/components/results.js)

## Testing

Current automated coverage focuses on:

- SQL pattern detection
- SQL input validation
- response deserialization compatibility
- candidate scoring selection

Run tests with:

```bash
mvn test
```

## Roadmap

### Near term

- improve the open-source onboarding experience
- add more integration tests
- provide demo data and repeatable benchmark cases
- document deployment profiles more clearly

### Next major product step

- evolve the experimental workflow into a real single-table multi-index design assistant
- support keep/add/retire decisions for index sets
- add stronger system-side validation for index recommendations

## Known Limitations

- the experimental index workflow is not yet as rigorous as the optimization analysis workflow
- some recommendation logic is still heuristic and under active iteration
- the front-end still depends on external CDN/font resources

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md)

## License

MIT, see [LICENSE](LICENSE)
