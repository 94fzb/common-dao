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
- `BaseEntity` defines `createdAt`, `updatedAt` and `version` as `Long`. Times are UTC epoch milliseconds. Historical times may remain null; loaded IDs and versions must be non-null, with versions non-negative.
- Static, synthetic and Java `transient` fields are excluded. Field shadowing, duplicate columns, final persistent fields and invalid SQL identifiers are rejected.
- Scalar types: strings, booleans, numeric primitives/wrappers, `BigInteger`, `BigDecimal`, `java.util.Date` and JDBC `byte[]`. Date fields use epoch milliseconds, **not** SQL DATETIME conversion. Nullable columns should use reference types. Encode JSON and other structured values explicitly as strings; arbitrary objects, relationships and collections are not mapped.

Schema changes remain explicit. Supply the table and all mapped columns through installation SQL
and versioned migrations before using a repository. No operation creates, inspects or repairs the schema.
Preserve existing version values when migrating; initialize missing versions explicitly. Keep unknown
historical times null. For example, the common columns for a newly managed table can be:

```sql
createdAt BIGINT NULL,
updatedAt BIGINT NULL,
version BIGINT NOT NULL DEFAULT 0
```

## Writes, ownership and failures

`update` and `delete` accept only entities loaded or successfully inserted through the **same explicit
data source instance**. Repository instances may differ. Do not deserialize a request into an Entity
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
There is no implicit read, retry, upsert, flush, identity cache, cascade or multi-operation transaction.
Raw SQL is not rewritten: all writers of a migrated table must deliberately maintain the same version
contract, including counters and background jobs. A successful write is one database statement; JDBC
generated-key insertion commits on the same connection that returned the key.

After any failed write, the entity cannot be reused for persistence. In-memory ID/time/version metadata
advances only after a confirmed success. Reload and reconcile before deciding what to do next: an HTTP
or connection failure may happen **after** the database committed. For an uncertain generated-ID insert,
reconcile using a business key; there is no automatic replay or exactly-once guarantee. Entity tracking
is transient and excluded by Gson; still use dedicated DTOs at API boundaries.

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
