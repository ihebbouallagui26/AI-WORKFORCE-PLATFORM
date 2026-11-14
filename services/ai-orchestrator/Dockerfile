# AI orchestrator image. Build context: services/ai-orchestrator
FROM python:3.13-slim AS build
COPY --from=ghcr.io/astral-sh/uv:0.13.8 /uv /usr/local/bin/uv
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
COPY pyproject.toml uv.lock README.md ./
RUN uv sync --frozen --no-dev --no-install-project
COPY app ./app
RUN uv sync --frozen --no-dev

FROM python:3.13-slim
# Non-root, no shell login, no home directory.
RUN useradd --system --uid 10001 --user-group --no-create-home --shell /usr/sbin/nologin orchestrator
WORKDIR /app
COPY --from=build --chown=10001:10001 /app /app
ENV PATH="/app/.venv/bin:${PATH}" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    AIO_HOST=0.0.0.0 \
    AIO_PORT=8091
USER 10001
EXPOSE 8091
HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 \
  CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8091/health/live', timeout=2).status == 200 else 1)"]
CMD ["python", "-m", "app"]
