# Build and verification report

- **Artifact version:** `1.21.11-26.2`
- **Configured Paper API:** `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`
- **Java bytecode target:** 21
- **Report status:** Source implementation present; release build and deployable JAR are not verified in this workspace.

## Verification performed

- Read `AstraCurrencyEngine_Master_Development_Prompt.md` from the GitHub `origin/main` tree and implemented against it.
- Confirmed the Paper 1.21.11 API version/coordinates from PaperMC's `ver/1.21.11` branch metadata and inspected the PlaceholderAPI 2.11.6 expansion API source on GitHub.
- Parsed all Java source files with the Java parser.
- Compiled Java main and test sources to Java 21 bytecode with Eclipse Compiler for Java 3.39.0 **against temporary minimal Bukkit/Paper/Adventure/PlaceholderAPI/JUnit stubs**. This catches Java syntax and internal type errors only; it does not establish binary compatibility with the real server API, and no tests were executed.
- Parsed all bundled YAML resources with PyYAML and checked message/menu keys referenced by source.
- Validated `pom.xml` as XML and checked Maven release-packaging script inputs.

## Not performed / not verified

- `mvn -B clean verify`: **not run**. Maven and `javac` are not installed in the workspace.
- Real Paper API dependency resolution and compilation: **not performed**.
- Deployable shaded JAR creation, descriptor inspection, and packaged dependency inspection: **not performed**.
- JUnit execution / SQLite driver integration tests: **not performed**.
- Paper 1.21.11 server startup smoke test: **not performed**.
- Java 21 or Java 25 server runtime test: **not performed**.
- PlaceholderAPI runtime test: **not performed**.
- Minecraft/Paper 26.2 compatibility: **not verified and not claimed**. The plugin descriptor sets 1.21.11 as its minimum API; compiling against the oldest API is an intended compatibility strategy, not proof that newer APIs remain binary compatible.
- Vault, Skript, ajLeaderboards, Topper, MySQL/MariaDB, native Dialog API, and Folia: **not implemented**.

## Dependency versions

The Maven project configures Paper API `1.21.11-R0.1-SNAPSHOT` (`provided`), PlaceholderAPI `2.11.6` (optional, `provided`), Xerial SQLite JDBC `3.45.3.0` (shaded), Kotlin stdlib/plugin `2.0.21`, and JUnit 5 `5.10.2`. Repository resolution and dependency/shading compatibility have not been exercised here.

## Release gate

Run the GitHub Actions workflow or execute `mvn -B clean verify` with JDK 21. Only after it succeeds, run `python3 scripts/package_release.py`. The packaging script refuses to continue unless tests have reports and the shaded JAR contains `plugin.yml`, the declared main class, and SQLite JDBC. A Paper server smoke test and owner-approved license remain release requirements.
