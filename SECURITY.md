# Security Policy

## Supported Versions

Currently, only the latest version of SQL Agent is supported with security updates.

| Version | Supported          |
| ------- | ------------------ |
| 2.0.x   | :white_check_mark: |

## Reporting a Vulnerability

If you discover a security vulnerability, please **DO NOT** create a public issue.

Instead, please send an email to g13104233370@163.com This will allow us to assess the report and issue a fix before the vulnerability is disclosed to the public.

Please include:

* A description of the vulnerability
* Steps to reproduce the issue
* Potential impact of the vulnerability
* Any suggested mitigations or fixes (if available)

We will acknowledge receipt of your report within 48 hours and provide regular updates on our progress.

## Security Best Practices

### For Users

1. **Keep Software Updated**: Always use the latest version of SQL Agent to ensure you have the latest security patches.

2. **Environment Variables**: Never commit `.env` files or expose sensitive credentials:
   ```bash
   # .env should be in .gitignore
   .env
   ```

3. **Demo Mode**: In production environments, carefully configure security flags:
   ```env
   SQL_AGENT_DEMO_READ_ONLY=true      # Default: true
   SQL_AGENT_MUTATION_ENABLED=false   # Default: false
   ```

4. **Database Access**: Use database accounts with minimal required permissions. Avoid using root credentials in production.

5. **API Keys**: Rotate your model provider API keys regularly and use environment variables for configuration.

### For Developers

1. **Input Validation**: All SQL inputs are validated through `SqlInputValidationService` before execution.

2. **Sandbox Execution**: Index operations are executed in a temporary sandbox and cleaned up after validation.

3. **Read-Only Default**: The application defaults to read-only mode to prevent accidental data modifications.

4. **No SQL Injection**: We use parameterized queries and prepared statements throughout the codebase.

## Security Features

### Built-in Protections

* **SQL Injection Prevention**: All queries use parameterized statements
* **Input Validation**: Multi-layer validation for SQL inputs
* **Sandbox Mode**: Temporary indexes are isolated and cleaned up
* **Read-Only Default**: DDL/DML operations are disabled by default
* **Explain-First**: Analysis relies on EXPLAIN, not data modification

### Configuration Safety

The following security controls are available:

| Setting | Purpose | Default |
|---------|---------|---------|
| `SQL_AGENT_DEMO_READ_ONLY` | Prevents any write operations | `true` |
| `SQL_AGENT_MUTATION_ENABLED` | Controls index creation/deletion | `false` |
| `SQL_AGENT_MODEL_API_KEY` | Model provider authentication | *required* |

## Dependency Management

We regularly update dependencies to address security vulnerabilities:

* **Spring Boot**: Follows Spring security advisories
* **LangChain4j**: Monitors for security updates
* **MySQL Connector**: Updated when security patches are released

## Disclosure Policy

When a security vulnerability is reported:

1. **Confirmation**: We acknowledge receipt within 48 hours
2. **Investigation**: We investigate and validate the report
3. **Fix Development**: We develop a fix in a private branch
4. **Coordination**: We coordinate disclosure with the reporter
5. **Release**: We release a security update
6. **Public Disclosure**: We publish security advisories after fixes are deployed

## Security Audits

We welcome security audits and responsible disclosure. If you're conducting a security audit, please let us know in advance so we can track your findings appropriately.

## Contact

For security-related questions not related to vulnerability disclosure, please open an issue with the `security` label.

---

*Last Updated: 2026-03-31*
