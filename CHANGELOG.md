# Changelog

## 0.24
- Fix: HyperSQL enum fields were rendered as a dangling `enum_<name>` column type that was never defined (HSQLDB has no native `ENUM`), so the create script could not be applied. Enums are now defined as CHECKed varchar domains: `CREATE DOMAIN enum_<name> AS VARCHAR(<longest label>) CHECK (VALUE IN (...))`
- The `enum_<name>` type rendering is now gated on the same `supportsEnums` switch as the type definitions: a dialect without native enum support falls back to `varchar(<longest label>)` instead of emitting an undefined type
- remove dialects aliases (`postgres` dropped ; now `postgresql` or `hypersql`)

## 0.23
- Add partial (conditional) constraint groups: `!(a, b) where c is null` — conditions are restricted to `c is null`, `c is not null`, `flag` and `not flag` so identifiers stay transformable per dialect
- PostgreSQL renders conditional unique groups as `CREATE UNIQUE INDEX <table>_<cols>_uidx ... WHERE ...` (no inline `UNIQUE`); conditional `+(...)` groups as partial `CREATE INDEX`
- HyperSQL (no partial-index support) fails with a `SemanticException` instead of emitting silently stronger DDL
- Semantic checks: condition field must exist, be boolean for the bare/`not` forms, be nullable for the `is [not] null` forms
- JDBC reverse engineering reads `FILTER_CONDITION`: kddl-expressible conditions map back to `where` tails, others skip the index with a warning (previously a partial unique index silently became an unconditional one)
- Fix: constraint groups can now reference link-created fields (resolution deferred until after links; previously broke KDDL round-trip)
- **Reserved words**: `where`, `is`, `not` are now keywords and can no longer be used as identifiers

## 0.22
- Add composite unique/index constraint groups: `!(a, b)` and `+(a, b)` lines inside a table block (repeatable, may overlap; column order is declaration order)
- **Semantic change**: multiple `+` fields now produce one index each; the previous implicit grouping of all indexed fields into a single composite index is gone (composite is spelled `+(a, b)`)
- Non-unique indexes are now emitted as `CREATE INDEX` statements (the previous inline `INDEX (...)` was invalid PostgreSQL)
- KDDL output: render the `+` indexed marker (was dropped) and constraint group lines
- JDBC reverse engineering: multi-column unique indexes are now mapped to `!(a, b)` groups instead of being dropped (or mis-attributed on odd column counts)

## 0.21
- Downgrade `mysql-connector-j` to 8.4.0 (last release on protobuf-java 3.x) to avoid forcing protobuf 4.x onto consumer buildscript classpaths and breaking AGP's Tink-based release tasks (`NoSuchMethodError` on `Keyset.makeExtensionsImmutable`)

## 0.20
- Fix: inheritance INSERT rules now wrap `NEW.col` in `COALESCE(NEW.col, <default-expr>)` for columns with a `DEFAULT` clause, so view inserts that omit the column actually pick up the default instead of inserting NULL

## 0.19
- Introduce `FieldType` sealed type (`Primitive` / `InlineEnum` / `NamedEnum`) replacing the stringly-typed `ASTField.type`
- Fix named enums: a single SQL `CREATE TYPE` is now emitted per declared enum, named after the enum
- Fix: two fields referencing the same named enum no longer produce two distinct SQL types
- KDDL round-trip: named-enum references re-emit as `field_name enum_name` instead of expanded inline values

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
