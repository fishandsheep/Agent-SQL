# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- CONTRIBUTING.md with English and Chinese versions
- GitHub issue templates (bug report, feature request, documentation)
- GitHub pull request template
- CODE_OF_CONDUCT.md (Contributor Covenant 2.0)
- SECURITY.md with comprehensive security policy
- CHANGELOG.md for version tracking
- README badges (License, Java, Spring Boot, LangChain4j)
- Screenshot placeholders in README files

### Changed
- Improved README structure with better navigation
- Enhanced documentation for open-source onboarding

### Security
- Documented security best practices in SECURITY.md
- Clarified default read-only demo mode behavior

## [2.0.0] - 2026-03-XX

### Added
- Single SQL optimization analysis workflow
- Streaming execution timeline with SSE
- Before/after EXPLAIN plan comparison
- Candidate plan workbench with scoring
- Baseline measurement and result validation
- History replay functionality
- SQL input validation
- Schema inspection and table/index browsing
- Statistics and data skew analysis
- Temporary index sandbox execution
- Multi-language support (English/Chinese UI foundation)

### Experimental Features
- Grouped SQL index recommendation (`/api/analyze/workload`)
- Early-stage foundation for single-table multi-index design

### Security
- Default read-only demo mode (`SQL_AGENT_DEMO_READ_ONLY=true`)
- Mutation disabled by default (`SQL_AGENT_MUTATION_ENABLED=false`)
- Sandbox execution for temporary indexes
- SQL injection prevention with parameterized queries

### Documentation
- Comprehensive README with architecture overview
- Docker Compose setup for easy deployment
- Environment variable documentation
- API endpoint documentation

### Testing
- SQL pattern detection tests
- SQL input validation tests
- Response deserialization compatibility tests
- Candidate scoring selection tests

### Known Limitations
- Experimental index workflow lacks full validation loop
- Some recommendation logic is still heuristic
- Frontend depends on external CDN/font resources
- Limited test coverage for core Agent logic

## [1.x.x] - Historical Versions

> Note: Versions before 2.0.0 were development iterations and not publicly released.

---

## Version Classification

- **Major (X.0.0)**: Breaking changes, major architectural shifts
- **Minor (x.X.0)**: New features, backward compatible
- **Patch (x.x.X)**: Bug fixes, documentation updates, minor improvements

## Release Cadence

This project is in active development. There is no fixed release schedule, but we aim to:

- Release patch versions as needed for bug fixes
- Release minor versions for new features
- Release major versions for significant architectural changes

## Migration Guides

### Upgrading from 1.x to 2.0.0

[Detailed migration guide will be added with first stable release]

---

[Unreleased]: https://github.com/yourusername/sql-agent/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/yourusername/sql-agent/releases/tag/v2.0.0
