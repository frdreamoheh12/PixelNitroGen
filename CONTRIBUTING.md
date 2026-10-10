# Contributing

## Build prerequisites

- JDK 21
- Maven 3.9+
- A Paper 1.21.11 test server for runtime checks

Run:

```bash
mvn -B clean verify
```

For release packaging, after verification succeeds:

```bash
python3 scripts/package_release.py
```

## Engineering rules

- Keep balance and tax arithmetic in `BigDecimal`; never introduce floating-point money.
- Do not perform JDBC/file I/O from Bukkit command, inventory, event, or placeholder callbacks. Use the bounded storage API and marshal Bukkit calls back to the main thread.
- Keep transaction writes atomic and parameterized. Extend `SqliteStorageTest` for storage changes.
- Keep optional integrations isolated and guarded by soft dependencies; do not claim an adapter is supported until its API and server behavior are tested.
- Keep `plugin.yml`, permission documentation, commands, messages, and README in sync.
- Do not claim support for Folia or a Paper version that has not been smoke-tested.
- Do not publish a release until an owner-approved license replaces the pending notice in `LICENSE`.

## Tests

Add JUnit 5 tests for configuration validation, arithmetic/precision, transaction integrity, idempotency, persistence, permissions, and integrations. Report unit tests, server smoke tests, and compatibility tests separately. Never fabricate a passing build or integration result.
