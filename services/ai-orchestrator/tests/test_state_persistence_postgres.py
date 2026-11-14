"""Sealed orchestration state across REAL orchestrator lifecycles on the REAL PostgreSQL checkpointer (Testcontainers).

Test boundary (documented): each "lifecycle" is an independent connection pool + checkpointer + ExecutionRunner + MCP
stand-in + StateSealer built from the key alone, inside one test process; nothing is shared in memory between lifecycles,
so only the database and the key carry state over. The Core API and Anthropic are mocked; this is not a subprocess or
container restart (see docs for what a container restart adds). No real key is used: keys are synthetic.
"""

import os
import uuid

import psycopg
import pytest
import respx

from app.core.crypto import StateSealer
from app.mcp.executor import ToolOutput
from app.orchestration.checkpointer.postgres import open_postgres_checkpointer
from tests.test_postgres_checkpoints import database, settings_for  # noqa: F401 - the shared Postgres fixture
from tests.test_tool_execution import (
    EXECUTION,
    KEY,
    ORG,
    SECRET,
    TASK,
    CoreScript,
    Env,
    Provider,
    final,
    run,
    tool_use,
)

pytestmark = pytest.mark.docker
OTHER_KEY = os.urandom(32)
ARGUMENT = "Remember the demo"
FORBIDDEN = (SECRET, "THIS-IS-A-FAKE-KEY", TASK, ARGUMENT, "Be precise", "service-access-token", "client-secret-value", "Bearer", "eyJ")
TABLES = ("checkpoints", "checkpoint_blobs", "checkpoint_writes")


@pytest.fixture(autouse=True)
def clean_checkpoints(database):  # noqa: F811
    with psycopg.connect(f"{database['admin']}/ai_orchestrator", autocommit=True) as conn:
        names = conn.execute(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'orchestrator_runtime' "
            "AND table_name LIKE 'checkpoint%' AND table_name <> 'checkpoint_migrations'"
        ).fetchall()
        for (name,) in names:
            conn.execute(f"TRUNCATE orchestrator_runtime.{name}")  # noqa: S608 - names come from the catalog


def dump(database) -> str:  # noqa: F811
    with psycopg.connect(database["runtime"]) as conn:
        rows = [conn.execute(f"SELECT * FROM orchestrator_runtime.{table}").fetchall() for table in TABLES]  # noqa: S608
    return "".join(repr(row) for table_rows in rows for row in table_rows)


def stored_blob(database) -> str:  # noqa: F811
    with psycopg.connect(database["runtime"]) as conn:
        rows = conn.execute(
            "SELECT checkpoint->'channel_values'->>'loop_blob' FROM orchestrator_runtime.checkpoints "
            "WHERE checkpoint->'channel_values'->>'loop_blob' <> '' ORDER BY checkpoint_id DESC"
        ).fetchall()
    assert rows, "no sealed conversation was persisted"
    return rows[0][0]


async def lifecycle_a_pauses(database):  # noqa: F811
    """Lifecycle A: the run asks for an approval-required tool and pauses; everything is closed cleanly afterwards."""
    core = CoreScript(respx.mock)
    core.decide("toolu_note", "APPROVAL_REQUIRED", approvalId=str(uuid.uuid4()))
    Provider(tool_use("diagnostic_record_note", "toolu_note", {"text": ARGUMENT}), final("All noted."))
    async with open_postgres_checkpointer(settings_for(database)) as saver:
        env = Env(saver=saver)
        await run(await env.build())
        await env.close()
    return core


@respx.mock
async def test_a_paused_run_is_stored_sealed_and_resumes_in_a_new_lifecycle_with_the_same_key(database):  # noqa: F811
    core = await lifecycle_a_pauses(database)
    assert core.credential.call_count == 1 and core.complete.call_count == 0

    # What PostgreSQL really holds: identifiers and a sealed hex blob, never the conversation, a secret or a token.
    text = dump(database)
    for forbidden in FORBIDDEN:
        assert forbidden not in text
    assert str(EXECUTION) in text
    sealed = stored_blob(database)
    assert set(sealed) <= set("0123456789abcdef") and len(sealed) > 100
    # It is genuinely the sealed conversation: only the right key and the right (organization, execution) open it.
    assert ARGUMENT.encode() in StateSealer(KEY).open(sealed, StateSealer.aad(ORG, EXECUTION))

    # Lifecycle B: nothing in memory survives; same key, brand new pool/checkpointer/runner/manager.
    core.decide("toolu_note", "APPROVED")
    async with open_postgres_checkpointer(settings_for(database)) as saver:
        env = Env(saver=saver, key=KEY)
        env.manager.output = ToolOutput(text="receipt-1", truncated=False, is_error=False)
        await run(await env.build(), resume=True)
        await env.close()
    assert env.manager.calls == [("diagnostic", "record_note", {"text": ARGUMENT})]
    assert core.posted(core.complete)["result"] == "All noted." and core.fail.call_count == 0
    assert core.credential.call_count == 2                                   # a fresh single-use credential, never stored
    after = dump(database)
    for forbidden in (*FORBIDDEN, "All noted.", "receipt-1"):
        assert forbidden not in after


@respx.mock
async def test_a_different_key_cannot_read_the_state_and_fails_safely(database):  # noqa: F811
    core = await lifecycle_a_pauses(database)
    core.decide("toolu_note", "APPROVED")
    async with open_postgres_checkpointer(settings_for(database)) as saver:
        env = Env(saver=saver, key=OTHER_KEY)
        await run(await env.build(), resume=True)
        await env.close()
    failure = core.posted(core.fail)
    assert failure["errorCode"] == "STATE_UNAVAILABLE" and core.complete.call_count == 0 and env.manager.calls == []
    leaked = str(failure)
    for forbidden in (*FORBIDDEN, KEY.hex(), OTHER_KEY.hex()):
        assert forbidden not in leaked
    # The stored state was not modified or destroyed by the failed attempt: the right key still opens it.
    assert ARGUMENT.encode() in StateSealer(KEY).open(stored_blob(database), StateSealer.aad(ORG, EXECUTION))


@respx.mock
@pytest.mark.parametrize("tamper", ["flip-last-byte", "truncate", "swap-with-other-execution"])
async def test_a_tampered_sealed_state_in_postgres_is_rejected(database, tamper):  # noqa: F811
    core = await lifecycle_a_pauses(database)
    sealed = stored_blob(database)
    other = StateSealer(KEY).seal(b'{"system":"s","messages":[]}', StateSealer.aad(ORG, uuid.uuid4()))
    forged = {
        "flip-last-byte": sealed[:-2] + ("00" if sealed[-2:] != "00" else "ff"),
        "truncate": sealed[:-8],
        "swap-with-other-execution": other,
    }[tamper]
    with psycopg.connect(database["runtime"], autocommit=True) as conn:
        changed = conn.execute(
            "UPDATE orchestrator_runtime.checkpoints "
            "SET checkpoint = jsonb_set(checkpoint, '{channel_values,loop_blob}', to_jsonb(%s::text)) "
            "WHERE checkpoint->'channel_values'->>'loop_blob' = %s",
            (forged, sealed),
        ).rowcount
    assert changed >= 1
    core.decide("toolu_note", "APPROVED")
    async with open_postgres_checkpointer(settings_for(database)) as saver:
        env = Env(saver=saver)
        await run(await env.build(), resume=True)
        await env.close()
    assert core.posted(core.fail)["errorCode"] == "STATE_UNAVAILABLE"
    assert env.manager.calls == [] and core.complete.call_count == 0         # a forged state never reaches the model or a tool


@respx.mock
async def test_a_run_without_any_pause_leaves_no_sealed_conversation(database):  # noqa: F811
    core = CoreScript(respx.mock)
    Provider(final("Plain answer."))
    async with open_postgres_checkpointer(settings_for(database)) as saver:
        env = Env(saver=saver)
        await run(await env.build())
        await env.close()
    assert core.posted(core.complete)["result"] == "Plain answer."
    text = dump(database)
    for forbidden in (*FORBIDDEN, "Plain answer."):
        assert forbidden not in text
