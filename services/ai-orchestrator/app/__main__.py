"""``python -m app``: runs the service with uvicorn using the validated settings."""

import asyncio
import sys

import uvicorn

from app.core.config import get_settings
from app.main import create_app


def main() -> None:
    loop = "asyncio"
    if sys.platform == "win32":
        # psycopg's async mode (PostgreSQL checkpoints) cannot use the proactor loop, which uvicorn
        # forces on Windows unless it is told not to set up a loop at all. The service starts no
        # subprocess, so the selector loop is safe here.
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())  # type: ignore[attr-defined]
        loop = "none"
    settings = get_settings()
    uvicorn.run(
        create_app(settings),
        host=settings.host,
        port=settings.port,
        loop=loop,
        server_header=False,
        timeout_graceful_shutdown=int(settings.shutdown_grace_seconds),
        access_log=False,  # structured request logs come from the application, never raw access lines
    )


if __name__ == "__main__":
    main()
