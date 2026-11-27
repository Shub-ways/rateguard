# Contributing

RateGuard is a finished portfolio project (v1.0.0), but issues and small
pull requests are welcome.

## Setup

- JDK 25, Maven 3.9+, and Docker. The Testcontainers suites need Docker;
  without it they skip themselves.
- `mvn clean test` should report 128 tests, 0 failures, 0 skipped.

## Pull requests

1. **Branch from `main`** and keep each PR focused on one change.
2. **Tests:** add or update tests for any behaviour change. CI must stay
   green, and CI fails on any skipped test.
3. **No secrets:** don't commit API keys, `.env` files or credentials.
   `.env` is git-ignored; use `.env.example` for placeholders.
4. **Performance claims** need raw evidence in `benchmarks/results/`, a
   row in `docs/EVIDENCE.md`, and the method used. See
   [docs/BENCHMARKS.md](docs/BENCHMARKS.md).
5. **Merging:** PRs are merged with a merge commit, so history is never
   rewritten.
6. **License:** by contributing, you agree that your contributions are
   licensed under the project's [MIT License](LICENSE).

## Reporting problems

Open an issue with steps to reproduce, the expected and actual behaviour,
and your JDK and Docker versions. For security issues, see
[Security considerations](docs/CONFIGURATION.md#security-considerations).
