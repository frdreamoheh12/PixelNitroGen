#!/usr/bin/env python3
"""Validate a Maven-built AstraCurrencyEngine JAR and prepare release ZIPs."""
from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "artifacts"
POM = ROOT / "pom.xml"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run(command: list[str]) -> str:
    return subprocess.check_output(command, cwd=ROOT, text=True, stderr=subprocess.STDOUT).strip()


def xml_test_totals() -> tuple[int, int, int, int]:
    tests = failures = errors = skipped = 0
    for report in (ROOT / "target" / "surefire-reports").glob("TEST-*.xml"):
        root = ElementTree.parse(report).getroot()
        tests += int(root.attrib.get("tests", 0))
        failures += int(root.attrib.get("failures", 0))
        errors += int(root.attrib.get("errors", 0))
        skipped += int(root.attrib.get("skipped", 0))
    return tests, failures, errors, skipped


def project_files() -> list[Path]:
    ignored = {".git", "target", "artifacts", "node_modules", ".cache", ".arena", "__pycache__"}
    result: list[Path] = []
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(ROOT)
        if any(part in ignored for part in relative.parts):
            continue
        if path.suffix in {".class", ".jar", ".db", ".log", ".pyc"}:
            continue
        result.append(path)
    return sorted(result)


def write_zip(destination: Path, files: list[tuple[Path, str]]) -> None:
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path, archive_name in files:
            archive.write(path, archive_name)


def main() -> int:
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom = ElementTree.parse(POM).getroot()
    version = pom.findtext("m:version", namespaces=ns)
    paper_version = pom.findtext("m:properties/m:paper.version", namespaces=ns)
    sqlite_version = pom.findtext("m:dependencies/m:dependency[m:artifactId='sqlite-jdbc']/m:version", namespaces=ns)
    papi_version = pom.findtext("m:properties/m:placeholderapi.version", namespaces=ns)
    kotlin_version = pom.findtext("m:properties/m:kotlin.version", namespaces=ns)
    if not version:
        raise SystemExit("Could not read project version from pom.xml")

    built_jar = ROOT / "target" / f"AstraCurrencyEngine-{version}.jar"
    if not built_jar.is_file():
        raise SystemExit(f"Missing Maven JAR: {built_jar}. Run `mvn -B clean verify` first.")
    try:
        with zipfile.ZipFile(built_jar) as jar:
            names = set(jar.namelist())
            if "plugin.yml" not in names:
                raise SystemExit("JAR is missing plugin.yml")
            descriptor = jar.read("plugin.yml").decode("utf-8")
            main_match = re.search(r"(?m)^main:\s*([^\s#]+)", descriptor)
            declared_version = re.search(r"(?m)^version:\s*([^\s#]+)", descriptor)
            if not main_match:
                raise SystemExit("plugin.yml does not declare a main class")
            main_class = main_match.group(1).replace(".", "/") + ".class"
            if main_class not in names:
                raise SystemExit(f"JAR is missing declared main class: {main_class}")
            if "org/sqlite/JDBC.class" not in names:
                raise SystemExit("JAR is missing the shaded SQLite JDBC driver")
            if declared_version and declared_version.group(1) != version:
                raise SystemExit(f"plugin.yml version {declared_version.group(1)} does not match Maven version {version}")
    except zipfile.BadZipFile as exc:
        raise SystemExit(f"Maven output is not a valid JAR/ZIP: {exc}") from exc

    tests, failures, errors, skipped = xml_test_totals()
    if tests == 0:
        raise SystemExit("No Surefire test reports were found; refusing to label this a verified release")
    if failures or errors:
        raise SystemExit(f"Tests failed: {failures} failures, {errors} errors")

    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    jar_name = f"AstraCurrencyEngine-{version}.jar"
    release_jar = ARTIFACTS / jar_name
    shutil.copy2(built_jar, release_jar)

    java_version = run(["java", "-version"])
    maven_version = run(["mvn", "-v"])
    report = f"""# Build and verification report

- **Artifact version:** `{version}`
- **Configured Paper API:** `io.papermc.paper:paper-api:{paper_version}`
- **Java bytecode target:** 21
- **Build:** `mvn -B clean verify` succeeded in GitHub Actions or the current build environment.

## Build and test results

- JAR: `{jar_name}` ({release_jar.stat().st_size} bytes)
- JAR SHA-256: `{sha256(release_jar)}`
- JUnit/Surefire: {tests} tests, {failures} failures, {errors} errors, {skipped} skipped
- Descriptor check: `plugin.yml` present; main class and shaded `org.sqlite.JDBC` present; descriptor version matches Maven.
- Java runtime used for build: `{java_version.replace(chr(10), ' ')}`
- Maven version: `{maven_version.splitlines()[0] if maven_version else 'unknown'}`

## Dependency versions

- Paper API: `{paper_version}` (provided)
- PlaceholderAPI: `{papi_version}` (optional, provided; runtime integration is not verified by compilation)
- Xerial SQLite JDBC: `{sqlite_version}` (shaded)
- Kotlin: `{kotlin_version}`

## Runtime verification status

A successful compile and unit test run does **not** constitute a Paper server startup test. Paper 1.21.11 startup, Java 25 runtime, and Paper/Minecraft 26.2 compatibility must be listed as unverified unless a separate smoke test was recorded. Vault, Skript, Topper, ajLeaderboards, MySQL/MariaDB, native Dialog API, and Folia are not implemented by this release. PlaceholderAPI requires a server runtime test before production use.

## Release checksums

`artifacts/SHA256SUMS.txt` lists checksums for the JAR and generated ZIPs. Source/complete ZIP checksums are kept outside the report to avoid a self-referential archive checksum.

## License gate

The owner has not selected a license. The `LICENSE` file is a status notice, not a license. Public redistribution remains blocked until an owner-approved license is supplied.
"""
    root_report = ROOT / "BUILD-REPORT.md"
    root_report.write_text(report, encoding="utf-8")
    shutil.copy2(root_report, ARTIFACTS / "BUILD-REPORT.md")

    project = project_files()
    source_zip = ARTIFACTS / "AstraCurrencyEngine-Source.zip"
    source_items = [(path, str(Path("AstraCurrencyEngine") / path.relative_to(ROOT))) for path in project]
    write_zip(source_zip, source_items)

    complete_zip = ARTIFACTS / "AstraCurrencyEngine-Complete.zip"
    complete_items = list(source_items)
    complete_items.append((release_jar, str(Path("AstraCurrencyEngine") / jar_name)))
    write_zip(complete_zip, complete_items)

    checksums = ARTIFACTS / "SHA256SUMS.txt"
    lines = [f"{sha256(path)}  {path.name}" for path in (release_jar, source_zip, complete_zip)]
    checksums.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("Verified release artifacts:")
    for path in (release_jar, source_zip, complete_zip, checksums):
        print(f" - {path.relative_to(ROOT)} ({path.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ElementTree.ParseError, OSError, subprocess.CalledProcessError) as exc:
        print(f"Packaging failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
