### 1.1.15

- Include the common-dao version resource in Native Image builds so WebAPI database information reports its version even when tracing uses another database.

### 1.1.14

- Support configuring WebAPI HTTP proxies in code with a standard JDK `ProxySelector`. Connection checks reuse the data source's proxy configuration.

### 1.1.13

- Omit missing or blank JDBC catalogs from successful connection-test logs, including SQLite connections.

### 1.1.12

- Fix WebAPI requests being limited to one concurrent request regardless of the pool capacity. Use the pool size with a minimum of one; the default capacity is 10.
- Add a local HTTPS regression test for concurrent requests and queuing when the pool is full.

### 4.0.x

- init
