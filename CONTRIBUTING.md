# Contributing to SQL Agent

If you want to improve SQL Agent with me, this is the contribution guide I put together for the project.

## Development Environment

- Java 17
- Maven 3.9+
- MySQL 8.0+

## Getting Started

1. Copy `.env.example` to `.env` and configure your database and model settings.
2. Start dependencies: `docker compose up -d mysql`
3. Start the application: `mvn spring-boot:run`

## Making Changes

### Code Style

- Keep UTF-8 encoding for all source files.
- Prioritize minimal, verifiable changes.
- Add tests or reproduction steps for new features.
- For index-related changes, default to read-only demo mode with real mutations disabled.

### Commit Messages

Use clear, descriptive commit messages:
- `feat:` for new features
- `fix:` for bug fixes
- `docs:` for documentation changes
- `refactor:` for code refactoring
- `test:` for adding or updating tests
- `chore:` for maintenance tasks

Example: `feat(agent): add support for CTE analysis in optimization workflow`

## Pull Requests

### Before Submitting

- Ensure your code follows the project's coding standards
- Add or update tests as needed
- Update documentation if applicable
- Test your changes locally

### PR Description

Your pull request should include:

- **Problem Background**: What issue does this change address?
- **Target Scenario**: Who benefits from this change and how?
- **Verification**: How did you test this change?
- **Visual Changes**: For UI changes, please attach screenshots or screen recordings

### Review Process

- I will review the PR as soon as I can
- Address review comments by pushing additional commits
- Keep the discussion focused and constructive

## Reporting Issues

When reporting bugs or suggesting features, please use the issue templates and provide:
- Clear description of the problem or feature request
- Steps to reproduce (for bugs)
- Expected vs actual behavior
- Environment details (OS, Java version, etc.)
- Relevant logs or screenshots

## Community Guidelines

- Be respectful and inclusive
- Provide constructive feedback
- Help others when you can
- Follow the [Code of Conduct](CODE_OF_CONDUCT.md)

## Security

If you discover a security vulnerability, please follow our [Security Policy](SECURITY.md) instead of creating a public issue.

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
