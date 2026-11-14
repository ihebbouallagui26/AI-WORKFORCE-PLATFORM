"""Durable checkpoints in a dedicated, restricted PostgreSQL database (Testcontainers, no real service)."""

import uuid

import httpx
import psycopg
import pytest
from pydantic import SecretStr
from testcontainers.community.postgres import PostgresContainer

from app.core.config import Settings
from app.core.errors import NotFoundError
from app.main import create_app
from app.orchestration.checkpointer.postgres import open_postgres_checkpointer, ping
from app.orchestration.runtime import OrchestrationRuntime
from app.orchestration.state.models import ActorContext, ExecutionContext

pytestmark = pytest.mark.docker
RUNTIME_PASSWORD = "runtime-pw-not-a-secret"


@pytest.fixture(scope="module")
def database():
    """A PostgreSQL with a core business database and a separate orchestrator database/role."""
    with PostgresContainer("pgvector/pgvector:0.8.7-pg18", username="boot", password="boot-pw", dbname="postgres") as pg:
        host, port = pg.get_container_host_ip(), pg.get_exposed_port(5432)
        admin = f"postgresql://boot:boot-pw@{host}:{port}"
        with psycopg.connect(f"{admin}/postgres", autocommit=True) as conn:
            conn.execute(f"CREATE ROLE aio_runtime LOGIN PASSWORD '{RUNTIME_PASSWORD}' NOSUPERUSER NOCREATEDB NOCREATEROLE")
            conn.execute("CREATE DATABASE ai_orchestrator OWNER aio_runtime")
            conn.execute("REVOKE ALL ON DATABASE ai_orchestrator FROM PUBLIC")
            conn.execute("CREATE DATABASE core_business")
            conn.execute("REVOKE ALL ON DATABASE core_business FROM PUBLIC")
        with psycopg.connect(f"{admin}/core_business", autocommit=True) as conn:
            conn.execute("CREATE TABLE agents (id uuid PRIMARY KEY, name text)")
            conn.execute("INSERT INTO agents VALUES (gen_random_uuid(), 'business secret row')")
        yield {"admin": admin, "runtime": f"postgresql://aio_runtime:{RUNTIME_PASSWORD}@{host}:{port}/ai_orchestrator", "host": host, "port": port}


def settings_for(database) -> Settings:
    return Settings(environment="test", database_url=SecretStr(database["runtime"]))


def new_context(org=None) -> ExecutionContext:
    return ExecutionContext.create(org or uuid.uuid4(), uuid.uuid4(), ActorContext("service", "core-api"))


async def test_checkpoints_persist_and_reload_across_restarts(database):
    ctx = new_context()
    async with open_postgres_checkpointer(settings_for(database)) as saver:
        done = await OrchestrationRuntime(saver).run_diagnostic(ctx, target=3)
        assert done.status == "completed"
    # A brand new connection pool and runtime ("process restart") reads the same state.
    async with open_postgres_checkpointer(settings_for(database)) as saver:
        reloaded = await OrchestrationRuntime(saver).get_snapshot(ctx.organization_id, ctx.execution_id)
    assert reloaded.status == "completed" and reloaded.counter == 3 and reloaded.steps[0] == "begin"


async def test_a_failed_run_resumes_after_a_restart(database):
    ctx = new_context()
    async with open_postgres_checkpointer(settings_for(database)) as saver:
        with pytest.raises(RuntimeError):
            await OrchestrationRuntime(saver).run_diagnostic(ctx, target=4, fail_after=1)
    async with open_postgres_checkpointer(settings_for(database)) as saver:
        recovered = await OrchestrationRuntime(saver).resume(ctx.organization_id, ctx.execution_id, clear_fault=True)
    assert recovered.status == "completed" and recovered.counter == 4


async def test_organizations_cannot_read_each_others_runtime_state(database):
    ctx = new_context()
    async with open_postgres_checkpointer(settings_for(database)) as saver:
        runtime = OrchestrationRuntime(saver)
        await runtime.run_diagnostic(ctx)
        with pytest.raises(NotFoundError):
            await runtime.get_snapshot(uuid.uuid4(), ctx.execution_id)


async def test_tables_live_in_the_isolated_schema_only(database):
    async with open_postgres_checkpointer(settings_for(database)):
        pass
    with psycopg.connect(database["runtime"]) as conn:
        rows = conn.execute("SELECT table_schema, table_name FROM information_schema.tables WHERE table_name LIKE 'checkpoint%'").fetchall()
    assert rows and {schema for schema, _ in rows} == {"orchestrator_runtime"}


def test_the_runtime_role_cannot_reach_core_business_data(database):
    with pytest.raises(psycopg.OperationalError):
        psycopg.connect(f"postgresql://aio_runtime:{RUNTIME_PASSWORD}@{database['host']}:{database['port']}/core_business")


async def test_checkpoints_contain_no_credentials_or_tokens(database):
    ctx = new_context()
    async with open_postgres_checkpointer(settings_for(database)) as saver:
        await OrchestrationRuntime(saver).run_diagnostic(ctx, target=2)
    with psycopg.connect(database["runtime"]) as conn:
        text = ""
        for table in ("checkpoints", "checkpoint_blobs", "checkpoint_writes"):
            for row in conn.execute(f"SELECT * FROM orchestrator_runtime.{table}").fetchall():  # noqa: S608 - fixed names
                text += repr(row)
    for marker in ("Bearer", "sk-", "api_key", "password", "client_secret", "eyJ"):
        assert marker not in text


async def test_readiness_probe_reports_up(database):
    async with open_postgres_checkpointer(settings_for(database)) as saver:
        assert await ping(saver)


async def test_application_readiness_with_a_database(database, keys):
    app = create_app(settings_for(database))
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as http:
            response = await http.get("/health/ready")
    assert response.status_code == 200 and response.json() == {"status": "UP", "checks": {"checkpointStore": "UP"}}
    assert RUNTIME_PASSWORD not in response.text


@pytest.mark.docker
async def test_an_execution_graph_leaves_no_secret_instruction_or_result_in_postgres(database):
    """The real execution graph on the real PostgreSQL checkpointer, with mocked Core API and Anthropic."""
    import httpx
    import respx

    from tests.test_execution import ANTHROPIC, CORE, EXECUTION, ORG, RESULT, SECRET, TASK, CoreMock, Env, anthropic_ok

    settings = Settings(
        environment="test", database_url=SecretStr(database["runtime"]), anthropic_base_url=ANTHROPIC, run_timeout_seconds=10
    )
    async with open_postgres_checkpointer(settings) as saver:
        env = Env(settings)
        runner = await env.build()
        runner._checkpointer = saver  # noqa: SLF001 - same runner, durable checkpointer
        with respx.mock:
            CoreMock(respx.mock)
            anthropic_ok()
            runner.submit(ORG, EXECUTION)
            import asyncio

            await asyncio.gather(*list(runner._tasks.values()))  # noqa: SLF001
        await env.close()
    assert CORE and httpx
    with psycopg.connect(database["runtime"]) as conn:
        text = ""
        for table in ("checkpoints", "checkpoint_blobs", "checkpoint_writes"):
            for row in conn.execute(f"SELECT * FROM orchestrator_runtime.{table}").fetchall():
                text += repr(row)
    for forbidden in (SECRET, "THIS-IS-A-FAKE-KEY", TASK, RESULT, "Be precise", "service-access-token", "Bearer", "client-secret-value"):
        assert forbidden not in text
    assert str(EXECUTION) in text  # it did persist (identifiers and stage names only)
