# OpenAPI contracts

| File | Producer | Version |
| --- | --- | --- |
| [`core-api.v1.json`](core-api.v1.json) | `services/core-api` (springdoc, OpenAPI 3.1) | `/api/v1` |

The contract is **generated from the code**. The test suite writes it to `services/core-api/target/openapi/core-api.v1.json`. After an API change:

```bash
cd services/core-api && ./mvnw test
cp target/openapi/core-api.v1.json ../../contracts/openapi/core-api.v1.json
```

Commit the updated contract with the code change, so reviewers see API changes explicitly. Sprint B1 will add a CI check that fails when the committed contract differs from the generated one, and will generate the desktop's TypeScript types from it.
