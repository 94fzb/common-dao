# Entity / Repository API

The opt-in `com.hibegin.common.dao.entity` API separates persisted objects from data access.
Existing `DAO` and `BasePageableDAO` users retain their current behavior.
This is a small DbUtils-based persistence layer, not a JPA implementation.

```java
import com.hibegin.common.dao.DataSourceWrapper;
import com.hibegin.common.dao.entity.BaseEntity;
import com.hibegin.common.dao.entity.Column;
import com.hibegin.common.dao.entity.Entity;
import com.hibegin.common.dao.entity.EntityRepository;

@Entity(idColumn = "logId", generatedId = true)
class Log extends BaseEntity<Integer> {
    private String title;
    @Column("plain_content")
    private String plainContent;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPlainContent() { return plainContent; }
    public void setPlainContent(String plainContent) { this.plainContent = plainContent; }
}

class LogRepository extends EntityRepository<Log, Integer> {
    LogRepository(DataSourceWrapper dataSource) {
        super(Log.class, dataSource);
    }
}
```

```java
LogRepository logRepository = new LogRepository(dataSource);
Log log = new Log();
log.setTitle("First article");
logRepository.insert(log); // ID returned by this insert; version=0; both timestamps set

Log loaded = logRepository.findById(log.getId()).orElseThrow();
loaded.setTitle("Revised article");
logRepository.update(loaded); // only title, updatedAt and version are written
logRepository.update(loaded); // returns false; no SQL and no version/time change
```

Mapping rules:

- `@Entity` is required on each concrete class. A no-argument constructor is required.
- Tables default to snake case (`LogVersion` → `log_version`); `@Entity(table = "...")` overrides it.
- Fields keep their Java names unless `@Column` overrides them. Private and inherited fields are supported.
- The Java key is always `id`; `idColumn` maps it to an existing primary key such as `logId` or `userId`.
- IDs can be `String`, `Integer` or `Long`. Assigned IDs are the default. Set `generatedId = true` for a database-generated numeric ID, leaving `id` null before insertion.
- `BaseEntity` defines `createdAt` and `updatedAt` as `java.sql.Timestamp`, and `version` as `Long`. Times use SQL date/time storage by default. Historical times may remain null; loaded IDs and versions must be non-null, with versions non-negative. Metadata timestamp getters return defensive copies.
- `createdAtColumn` and `updatedAtColumn` on `@Entity` map inherited times to existing column names.
- Static, synthetic and Java `transient` fields are excluded. Field shadowing, duplicate columns, final persistent fields and invalid SQL identifiers are rejected.
- Scalar types: strings, booleans, numeric primitives/wrappers, `BigInteger`, `BigDecimal`, `java.util.Date`, `java.sql.Date`, `java.sql.Timestamp` and JDBC `byte[]`. Nullable columns should use reference types. Encode JSON and other structured values explicitly as strings; arbitrary objects, relationships and collections are not mapped.

Schema changes remain explicit. Supply the table and all mapped columns through installation SQL
and versioned migrations before using a repository. No operation creates, inspects or repairs the schema.
Preserve existing version values when migrating; initialize missing versions explicitly. Keep unknown
historical times null. For example, the common columns for a newly managed table can be:

```sql
createdAt DATETIME(3) NULL,
updatedAt DATETIME(3) NULL,
version BIGINT NOT NULL DEFAULT 0
```

## Date and time columns

`java.sql.Date` maps a calendar date to SQL `DATE`. `java.sql.Timestamp` maps a full date/time to
`DATETIME` or `TIMESTAMP`, retaining the fractional seconds supported by the destination column.
`java.util.Date` also uses date/time storage, with its millisecond precision. Choose the database column
precision explicitly: for example MySQL `DATETIME(3)` for milliseconds or `DATETIME(6)` for microseconds.
The repository does not increase a column's precision or read back values rounded by the database.

JDBC receives typed date/time parameters. SQLite has no dedicated temporal storage class, so SQLite
JDBC and SQLite/D1 WebAPI receive date/time text (`yyyy-MM-dd` or `yyyy-MM-dd HH:mm:ss[.fraction]`),
which supports SQLite's date functions. The new Entity path does not change legacy DAO/WebAPI bindings.
Legacy date formatting also accepts fractional-second text, allowing existing queries to read values
written through Entity.
Mutable date/time fields have independent snapshots, including `Timestamp` nanoseconds.
SQLite equality conditions compare stored text: newly written values use the canonical format above.
Reading an older offset-bearing or differently formatted value does not rewrite it or make different
text representations equal; use an explicit domain query when comparing across historical formats.

Unzoned SQL values retain local calendar-time semantics, using the JVM's configured time zone when a
`Timestamp` is converted to/from an instant. Existing offset-bearing WebAPI strings are read as the
instant specified by their offset. No existing local time is implicitly reinterpreted as UTC. Configure
the application and JDBC connection time zones consistently; SQL `TIMESTAMP` session conversions remain
the JDBC driver's/database's responsibility. Managed times use the repository's application `Clock`;
there is no extra database clock query, default, or trigger.

Existing integer time columns are opt-in, without a schema conversion:

```java
@Entity(createdAtColumn = "created_at", createdAtStorage = TemporalStorage.EPOCH_MILLIS)
class Token extends BaseEntity<String> {
    @Column(temporalStorage = TemporalStorage.EPOCH_MILLIS)
    private java.sql.Timestamp expiresAt;
}
```

Here `created_at` and `expiresAt` remain `BIGINT`, while `updatedAt` uses date/time storage. Set
`updatedAtStorage` separately if that column also uses integers. `EPOCH_MILLIS` is valid only for
`java.util.Date` and `java.sql.Timestamp`; sub-millisecond Timestamp values are rejected instead of
silently truncated. Numeric Java fields such as `Long` continue to map directly to numeric columns.
Malformed dates and numeric values read through native date/time mappings fail explicitly.

### Updating Entity consumers from 1.2.0

1.2.1 corrects the new Entity API's date/time contract: `getCreatedAt()` and `getUpdatedAt()` return
`Timestamp` instead of `Long`, and date fields no longer default to integer storage. Recompile Entity
consumers; use `.getTime()` only where an API boundary specifically needs epoch milliseconds. Existing
`DAO` and `BasePageableDAO` consumers keep their API and storage behavior.

For tables already created with the 1.2.0 integer layout, explicitly set `createdAtStorage`,
`updatedAtStorage`, and each integer-backed date field to `EPOCH_MILLIS`. For native date/time columns,
use the default mapping. No automatic DDL or data conversion is performed; any deliberate column type
change requires a separately reviewed versioned migration.

## Writes, ownership and failures

`update` and `delete` accept only entities loaded or successfully inserted through the **same explicit
data source instance**, or the same `EntitySession` when bound to a transaction. Repository instances may differ. Do not deserialize a request into an Entity
and update it directly: load the object, authorize the operation, and apply the requested changes.
SQL projections stay in the existing DTO/query APIs. `findMatching(conditions, limit)` returns complete
entities, uses equality conditions keyed by Java field name, and requires a limit from 1 to 1000.

Dirty tracking uses an independent snapshot, including copies of mutable values. Setting a mapped field
to null clears its column. Changing a Java object alone never persists it. IDs and managed metadata
cannot be edited on a loaded object. Entities are not thread safe and must not be shared between writers.

Every changed update checks the loaded `id` and `version` in its `WHERE` clause and advances the version
once. A conflicting update/delete throws `OptimisticLockException`. Additional equality conditions can
be supplied in the same statement, for example:

```java
repository.update(entity, Map.of("ownerId", currentUserId));
repository.delete(entity, Map.of("ownerId", currentUserId));
```

These predicates supplement application authorization; the library has no knowledge of users or permissions.
A no-op update issues no SQL, so it does not revalidate predicates or the stored version.
There is no implicit read, retry, upsert, flush, identity cache or cascade. Multi-operation JDBC transactions require an explicit caller-owned `EntitySession`.
Raw SQL is not rewritten: all writers of a migrated table must deliberately maintain the same version
contract, including counters and background jobs. A successful write is one database statement; outside
an `EntitySession`, JDBC generated-key insertion commits on the same connection that returned the key.

After any failed write, the entity cannot be reused for persistence. In-memory ID/time/version metadata
advances only after a confirmed success. Reload and reconcile before deciding what to do next: an HTTP
or connection failure may happen **after** the database committed. For an uncertain generated-ID insert,
reconcile using a business key; there is no automatic replay or exactly-once guarantee. Entity tracking
is transient and excluded by Gson; still use dedicated DTOs at API boundaries.

## Explicit conditional writes

For single-statement authorization, compare-and-set state transitions and bounded bulk operations,
use `insertWhen`, `updateWhere` and `deleteWhere`. These advanced APIs accept **repository-owned** SQL
predicates (without `WHERE`); bind all external values. They do not parse permissions or add an implicit
loaded-version check. Regular `update(entity)` and `delete(entity)` keep their optimistic-lock contract.

```java
boolean inserted = repository.insertWhen(newLog, "EXISTS (SELECT 1 FROM user WHERE userId=? AND enabled=?)", actorId, true);
Log patch = new Log();
patch.setTitle("Corrected title");
int changed = repository.updateWhere(patch, List.of("title"), "logId=? AND version=?", id, expectedVersion);
int deleted = repository.deleteWhere("logId=? AND version=?", id, expectedVersion);
```

Conditional inserts still populate ID, times and version only on success. A false result confirms no
row was inserted and leaves the entity new; invalid/missing acknowledgements throw instead. Generated
keys come from the same statement/connection, with no extra SELECT, including SQLite zero-row inserts.

`updateWhere` requires a fresh template with no persisted metadata and an explicit, nonempty list of
Java field names. Only those fields are assigned; null explicitly clears a selected field. ID and
managed metadata cannot be assigned. Each matching row gets an updated timestamp and increments its
version; null, negative and maximum versions do not match. The template is not hydrated or tracked.
These calls return affected row counts, including zero for a predicate miss. Reload old tracked entities
after bulk updates. On an uncertain partial write, the template cannot be reused; reconcile the result
before creating another write. Conditional delete has no tracked object to invalidate; the caller must
likewise reconcile errors before retrying. No retries, preliminary reads or automatic DDL are introduced.
JDBC `EntitySession` operations stay inside the caller's transaction.

## Repository queries and transactions

`select("WHERE enabled=? ORDER BY id DESC LIMIT ?", true, 20)` returns complete tracked entities.
The repository supplies the mapped table and select list, including WebAPI numeric precision casts.
SQL suffixes belong to repository code; bind external values as parameters. Apply suitable limits
when reading lists. Use a DTO for partial rows and aggregate results. `RowProjection.map(row, Dto.class)`
maps read-only query results, preserving SQL date values and case-insensitive aliases; it rejects
Entity and Map targets. Missing fields retain their DTO defaults, and SQL NULL leaves primitive
fields at their defaults. Register the DTO's constructor and fields for Native Image.

To share a JDBC transaction, bind repositories to an explicit scope:

```java
try (Connection connection = dataSource.getConnection()) {
    connection.setAutoCommit(false);
    try (EntitySession session = new EntitySession(dataSource, connection)) {
        EntityRepository<Log, Integer> logs = session.repository(Log.class);
        Log log = logs.findById(id).orElseThrow();
        log.setTitle("Revised article");
        logs.update(log);
        // Other repositories and parameterized SQL use the same connection here.
        connection.commit();
    } catch (SQLException | RuntimeException failure) {
        connection.rollback();
        throw failure;
    }
}
```

The caller owns commit, rollback and connection close. A session never changes connection settings
or commits an update; it uses DbUtils directly because a legacy QueryRunner may commit internally.
Close the scope when the transaction ends. Tracked objects cannot be written through another scope
or an ordinary repository; reload them in the new transaction. WebAPI has no JDBC session support;
retain atomic conditional statements for operations that must complete together.

## JDBC and WebAPI

This API targets MySQL/H2 JDBC and the SQLite/D1 WebAPI protocol. It does not claim SQL portability
to arbitrary database engines. WebAPI endpoints must return `success`, a `results` array for reads,
and an exact `changes` or `meta.changes` count for update/delete/assigned-ID insert. Numeric generated
IDs require SQLite's `INSERT ... RETURNING` support. Missing counts and incomplete rows fail explicitly.

For WebAPI, long integers and decimals are sent as text parameters and read with `CAST(... AS TEXT)`
to avoid precision loss in JavaScript bridges. Numeric columns must have the appropriate SQLite affinity;
the library cannot recover precision already lost in a database REAL column. Binary fields require an
explicit text encoding on WebAPI. The older QueryRunner/DAO parsing contract is unchanged.

## Native Image

Generate reflection metadata for **all concrete entity classes** as part of the consuming application's
build, before invoking `native-image`:

```java
String json = EntityReflection.configuration(Log.class);
// Write/merge json into META-INF/native-image/<group>/<artifact>/reflect-config.json.
```

The helper registers no-argument constructors, persisted fields and their declaring classes, including
generic ancestors and `BaseEntity`. It introduces no GraalVM runtime dependency. Keep the generated
configuration in the application build and regenerate it when mappings change. Driver, HTTP and API
DTO reflection requirements remain with the application. Validate its actual native artifact before
deploying; a JVM test of generated metadata is not a Native Image execution test.
