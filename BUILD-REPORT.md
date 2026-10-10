# Build and verification report

- **Artifact version:** `1.21.11-26.2`
- **Configured Paper API:** `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`
- **Java bytecode target:** 21
- **Verified source commit:** `f80e2861e4ada69e18f0d537e4f5eebd7b6a53ca`
- **GitHub Actions run:** [Maven build #3](https://github.com/frdreamoheh12/PixelNitroGen/actions/runs/38061830712) — **success**
- **Report status:** Maven verification, JUnit tests, shaded-JAR checks, ZIP packaging, and artifact upload passed in GitHub Actions. No Paper server startup or Paper 26.2 compatibility claim is made.

## Build and test results

- `mvn -B clean verify`: **passed** on GitHub Actions with Temurin Java 21.
- Real Paper API and PlaceholderAPI compile dependencies resolved; Java and Kotlin compile phases completed.
- JUnit 5 / Surefire: **8 tests, 0 failures, 0 errors, 0 skipped**.
  - Currency definition validation: 3 tests.
  - Decimal formatting: 2 tests.
  - SQLite persistence, transfer/tax, idempotency, and concurrent withdrawals: 3 tests.
- Release packaging script: **passed**. It inspected `plugin.yml`, the declared main class, Maven/plugin versions, and shaded `org.sqlite.JDBC`.
- The uploaded Actions distribution includes:
  - `AstraCurrencyEngine-1.21.11-26.2.jar`
  - `AstraCurrencyEngine-Source.zip`
  - `AstraCurrencyEngine-Complete.zip`
  - `SHA256SUMS.txt`
  - CI-generated `BUILD-REPORT.md`
- Download the temporary workflow artifact from the linked run page above; the workflow retains it for 30 days. The checksum file is included in that artifact bundle.

## Runtime verification still outstanding

- Paper 1.21.11 server startup / plugin smoke test: **not performed**.
- Java 25 runtime test: **not performed**.
- Minecraft/Paper 26.2 compatibility: **not verified and not claimed**. The plugin descriptor targets API 1.21.11; compiling against that API is not proof of compatibility with future server APIs.
- PlaceholderAPI runtime test: **not performed**; only compile-time integration was verified.
- Vault, Skript, ajLeaderboards, Topper, MySQL/MariaDB, native Dialog API, and Folia: **not implemented** in this release.

## Dependency versions

Paper API `1.21.11-R0.1-SNAPSHOT` (`provided`), PlaceholderAPI `2.11.6` (optional, `provided`), Xerial SQLite JDBC `3.45.3.0` (shaded), Kotlin stdlib/plugin `2.0.21`, and JUnit 5 `5.10.2`.

## License gate

The owner has not selected a license. `LICENSE` is a status notice, not a license. Public redistribution remains blocked until an owner-approved license is supplied.
