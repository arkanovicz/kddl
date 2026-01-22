# Changelog

## 0.18
- Add `include 'path.kddl'` statement to include other KDDL files
- SQL output now uses `IF NOT EXISTS` by default (idempotent mode)
- Add `-n/--no-idempotent` CLI flag to disable idempotent mode
- Make signing optional for non-release builds

## 0.17
- Add standalone enum declarations: `enum name(val1, val2)` with optional quotes
- Enums can be referenced by name in field types
- Allow unquoted enum values in both standalone and inline declarations
- Add UML one-liner chain syntax: `A *--* B --* C` for relation declarations
- KDDL output now generates chains from relations, hiding join tables

## 0.16
- Add `as` alias syntax for enum type naming: `mode enum('a','b') as MyMode`
- Add comprehensive test coverage (31 tests)
- Update README with ASCII architecture diagram

## 0.15
- Fix PostgreSQL datetime→timestamp type mapping
- Fix test resource loading

## 0.14
- Add Maven plugin (`kddl-maven-plugin`)
- Add Calcite-based SQL DDL parser (JVM-only)
- Add `varbit` type
- Allow type keywords as field names

## 0.13
- Use `timestamptz`/`timetz` naming (not `timestamp_tz`)

## 0.12
- Use `timestamp` rather than `datetime`
- Add several types to JDBC reverse engineering

## 0.11
- Dependency upgrades

## 0.10
- Upgrade ANTLR plugin, refactor build

## 0.9
- Add `bigint`, `datetime`/`timestamp`, `uuid` types
- Add `+` prefix for indexed fields
- Add `smallint` type
- Fix PlantUML optional FK arrow

## 0.8
- Implement Gradle plugin (`kddl-gradle-plugin`)
