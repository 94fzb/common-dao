### 1.1.12

- Fix WebAPI requests being limited to one concurrent request regardless of the pool capacity. Use the pool size with a minimum of one; the default capacity is 10.
- Add a local HTTPS regression test for concurrent requests and queuing when the pool is full.

### 4.0.x

- init
