"""``python -m app``: runs the service with uvicorn using the validated settings."""

import uvicorn

from app.core.config import get_settings
from app.main import create_app


def main() -> None:
    settings = get_settings()
    uvicorn.run(
        create_app(settings),
        host=settings.host,
        port=settings.port,
        server_header=False,
        timeout_graceful_shutdown=int(settings.shutdown_grace_seconds),
        access_log=False,  # structured request logs come from the application, never raw access lines
    )


if __name__ == "__main__":
    main()
