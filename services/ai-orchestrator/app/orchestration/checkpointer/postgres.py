"""PostgreSQL-backed LangGraph checkpoints in a dedicated database and schema.

The runtime database is separate from the Core API's business database and is reached with its own
restricted role: that role owns only the orchestrator database and has no access to Core API tables.
The checkpoint tables live in an isolated schema selected through ``search_path``. The Core API
remains the source of truth for business execution records; checkpoints are disposable runtime state.
"""

from __future__ import annotations

import re
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from psycopg import sql
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from app.core.config import Settings
from app.core.errors import UnavailableError

_SCHEMA = re.compile(r"^[a-z_][a-z0-9_]{0,62}$")


@asynccontextmanager
async def open_postgres_checkpointer(settings: Settings) -> AsyncIterator[AsyncPostgresSaver]:
    if settings.database_url is None:
        raise UnavailableError("The runtime database is not configured.")
    schema = settings.checkpoint_schema
    if not _SCHEMA.fullmatch(schema):  # also validated by Settings; checked again before use in DDL
        raise ValueError("invalid checkpoint schema name")
    conninfo = settings.database_url.get_secret_value()
    kwargs = {"autocommit": True, "prepare_threshold": 0, "row_factory": dict_row}

    # Create the schema first (plain connection, default search_path), then pin the pool to it.
    bootstrap = AsyncConnectionPool(conninfo, min_size=1, max_size=1, kwargs=kwargs, open=False)
    await bootstrap.open(wait=True, timeout=10)
    try:
        async with bootstrap.connection() as connection:
            await connection.execute(sql.SQL("CREATE SCHEMA IF NOT EXISTS {}").format(sql.Identifier(schema)))
    finally:
        await bootstrap.close()

    pool = AsyncConnectionPool(
        conninfo,
        min_size=1,
        max_size=5,
        kwargs={**kwargs, "options": f"-c search_path={schema}"},
        open=False,
    )
    await pool.open(wait=True, timeout=10)
    try:
        saver = AsyncPostgresSaver(pool)  # type: ignore[arg-type]
        await saver.setup()
        yield saver
    finally:
        await pool.close()


async def ping(saver: AsyncPostgresSaver) -> bool:
    """Readiness probe: one trivial query, no detail leaked to callers."""
    try:
        async with saver.conn.connection() as connection:  # type: ignore[union-attr]
            await connection.execute("SELECT 1")
        return True
    except Exception:  # noqa: BLE001 - readiness must never raise
        return False
