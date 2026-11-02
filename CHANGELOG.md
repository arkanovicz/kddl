# Changelog

## 0.32
- **breaking change**: type names are normalized wherever the AST is built (parser, reverse engineering, API): `int` → `integer`, `long`/`biginteger` → `bigint`, `short`/`smallinteger` → `smallint`, `byte` → `tinyint`, `datetime` → `timestamp`, `datetimetz`/`datetime_tz`/`timestamp_tz` → `timestamptz`, `clob` → `text`, precision kept (`datetime(3)` → `timestamp(3)`) ; KDDL and PlantUML output print the standard names
- Fix: `datetime(3)` (any aliased type with a precision) reached PostgreSQL unmapped
- Fix: HyperSQL output wrote `timestamptz` / `timetz`, which HSQLDB and H2 reject: now `timestamp[(p)] with time zone` / `time with time zone`
- `SQLFormatter.mapType` takes the `FieldType.Primitive` and maps its base name, precision kept
- Fix: PostgreSQL reverse engineering read `timestamptz` / `timetz` columns as `timestamp` / `time`, and `jsonb` as `json`
- `SQLFormatter.castTypeName(type)`: the dialect type a value can be cast to (a serial gives its integer type)

## 0.31
- Build: Gradle 9.4.1, Kotlin 2.3.21, antlr-kotlin 1.0.13, clikt 5.1.0, coroutines 1.11.0, postgresql 42.7.14, publishing through `com.vanniktech.maven.publish` and Dokka 2

## 0.30
- Fix: JDBC reverse engineering dropped the width of `char` columns and swapped `float` and `double`
- Fix: `clob` new gives `text` under PostgreSQL
- KDDL output: a one-to-many is written in its field (`author_id -- author?`), a link line only for many-to-many
- MySQL/MariaDB reverse engineering: `enum` columns keep their labels, a `0000-00-00` default makes the column nullable, `0`/`''` defaults on key columns are dropped
- PostgreSQL reverse engineering: enum columns are read from the JDBC type name and become real inline enums
- `ReverseFilter.filterType` became `filterColumn` (sees the table, the vendor type, the key status, and may change nullability)

## 0.29
- Fix: an insert through an inherited view now returns the row (JDBC generated keys work)
- Fix: an insert through an inherited view with a serial key could give the child row the wrong key
- Implicit primary keys are deprecated (warning on stderr)

## 0.28
- **breaking change**: inheritance discriminator changed from `class` varchar column to `kind` enum column
- `kind` is hidden by KDDL and PlantUML output
- Fix: the insert rule of a child whose parent key is not serial mixed up the parent's columns

## 0.27
- **breaking change**: an arrow head marks traversal intent: `a *-- b` navigates both ways, `a *--> b` only `a.b` ; migration: `*-->` → `*--` and `-->` → `--` wherever both navigations are wanted
- `a -- b` between two tables is rejected (no key side) ; `field -- table` is bidirectional, `field -> table` one-way
- Fix: a syntax error now fails parsing with `SyntaxException` instead of silently yielding a wrong model
- Fix: a table-level link's layout hint (`(up)`, …) was dropped

## 0.26
- Add `tinyint` (alias `byte`) and `short` as alias of `smallint`
- KDDL output parses back: tables in dependency order, chains referencing-first, literal date/time defaults quoted
- Default values take the type of their field
- Fix: JDBC reverse engineering maps `VARBINARY`/`LONGVARBINARY` to `blob` and `TINYINT`/`SMALLINT` to `tinyint`/`smallint`

## 0.25
- Add `bigserial` type

## 0.24
- Fix: HyperSQL enum fields were broken
- remove dialects aliases (`postgres` dropped ; now `postgresql` or `hypersql`)

## 0.23
- Add partial (conditional) constraint groups: `!(a, b) where c is null` — conditions are restricted to `c is null`, `c is not null`, `flag` and `not flag` ; unsupported for HyperSQL
- Fix: constraint groups can now reference link-created fields
- **breaking change**: `where`, `is`, `not` are now keywords and can no longer be used as identifiers

## 0.22
- **breaking change**: multiple `+` fields now produce one index each
- Add composite unique/index constraint groups syntax: `!(a, b)` and `+(a, b)`
- Non-unique indexes are now emitted as `CREATE INDEX` statements
- KDDL output: render the `+` indexed marker (was dropped) and constraint group lines

## 0.21
- Downgrade `mysql-connector-j` to 8.4.0, last release on protobuf-java 3.x: protobuf 4.x breaks some AGP release tasks

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
