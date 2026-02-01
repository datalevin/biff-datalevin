# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.6] - 2026-01-31

### Improved
- Updated deps.

## [0.1.0] - 2026-01-21

### Added

- **Core system lifecycle** (`biff.datalevin.core`)
  - `start-system` / `stop-system` for component lifecycle management
  - `use-datalevin` component for database connection (sets both `:biff.datalevin/conn` and `:biff/db`)
  - `assoc-stop` for registering cleanup functions
  - **Biff compatibility**: `use-datalevin` works as a drop-in component with Biff's `start-system`

- **Database utilities** (`biff.datalevin.db`)
  - Connection management with `get-db`, `get-conn`
  - `assoc-db` for refreshing `:biff/db` snapshot (matches Biff's pattern)
  - Transaction helpers with special values (`:db/now`, `:db/uuid`)
  - Query functions: `q`, `lookup`, `lookup-all`, `lookup-id`, `entity-exists?`
  - Entity operations: `pull`, `pull-many`, `merge-tx`, `delete-tx`

- **Authentication** (`biff.datalevin.auth`)
  - Password hashing with bcrypt (`hash-password`, `verify-password`)
  - User management (`create-user-tx`, `authenticate-user`, `find-user-by-email`)
  - GitHub OAuth integration (`github-authorize-url`, `github-exchange-code`, `github-get-user`)
  - Generic OAuth support (`oauth-authorize-url`, `oauth-exchange-code`)
  - Email verification tokens (`create-verification-token`, `verify-token`)

- **Session management** (`biff.datalevin.session`)
  - Datalevin-backed sessions (`create-session`, `get-session`, `delete-session-tx`)
  - JWT token support (`create-session-token`, `verify-session-token`)
  - Ring session store (`datalevin-session-store`)
  - Session cleanup utilities (`cleanup-expired-sessions-tx`)

- **Middleware** (`biff.datalevin.middleware`)
  - Authentication middleware (`wrap-authentication`, `wrap-require-auth`, `wrap-require-role`)
  - CSRF protection (`wrap-csrf`, `csrf-token`, `csrf-input`)
  - Composed middleware stacks (`wrap-site-defaults`, `wrap-api-defaults`)
  - Utility middleware (`wrap-catch-exceptions`, `wrap-logging`)

- Comprehensive test suite (50 tests, 195 assertions)
- Documentation with usage examples

[Unreleased]: https://github.com/datalevin/biff-datalevin/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/datalevin/biff-datalevin/releases/tag/v0.1.0
