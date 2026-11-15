"""Controlled local MCP server used ONLY by the integration tests (stdio, real SDK).

Usage: ``python mcp_test_server.py <mode> <pid-file>``. It records its PID in ``<pid-file>`` so a test can prove the
process is really gone. Modes: ``normal`` | ``crash-before-handshake`` | ``hang-before-handshake``.
Tools are deliberately hostile so the production manager's limits can be exercised; none touches files or network.
"""

import asyncio
import os
import sys

mode = sys.argv[1] if len(sys.argv) > 1 else "normal"
if len(sys.argv) > 2:
    with open(sys.argv[2], "w", encoding="utf-8") as handle:
        handle.write(str(os.getpid()))

if mode == "crash-before-handshake":
    os._exit(3)
if mode == "hang-before-handshake":
    import time

    time.sleep(3600)

from mcp.server.mcpserver import MCPServer  # noqa: E402

server = MCPServer("test-server")


@server.tool()
def echo(text: str) -> str:
    """Returns the text."""
    return text


@server.tool()
def pid() -> int:
    """Returns the server process id."""
    return os.getpid()


@server.tool()
async def hang() -> str:
    """Never returns in time."""
    await asyncio.sleep(3600)
    return "late"


@server.tool()
def crash() -> str:
    """Kills the server process in the middle of the call."""
    os._exit(7)


@server.tool()
def big(size: int) -> str:
    """Returns ``size`` characters."""
    return "A" * size


@server.tool()
def fail() -> str:
    """Reports a tool error carrying a secret-looking string."""
    raise ValueError("boom sk-ant-api03-SHOULD-NOT-LEAK-0123456789")


@server.tool()
def visible_secrets() -> str:
    """Names of the environment variables of this process that look like orchestrator secrets."""
    return ",".join(sorted(k for k in os.environ if k.startswith("AIO_") or "SECRET" in k.upper() or "KEY" in k.upper() or "TOKEN" in k.upper()))


# ---- adversarial output (security fix 8.4). Every tool below writes to the real file descriptors, bypassing JSON-RPC framing. ----


def _wire() -> int:
    """The REAL protocol descriptor. The SDK's stdio server diverts fd 1 to stderr (stray prints must not corrupt the wire) and serves
    the protocol from a private duplicate: a hostile server that controls its own stdout writes there."""
    from mcp.server import stdio

    claim = stdio._claims.get(1)  # noqa: SLF001
    return claim.private_fd if claim is not None and claim.private_fd is not None else 1


def _write_all(fd: int, data: bytes) -> None:
    view = memoryview(data)
    while len(view):
        view = view[os.write(fd, view) :]


def _flood(fd: int, total: int, chunk: int) -> None:
    """``total`` bytes (negative = for ever) in writes of ``chunk`` bytes."""
    block = b"X" * chunk
    sent = 0
    while total < 0 or sent < total:
        size = chunk if total < 0 else min(chunk, total - sent)
        _write_all(fd, block[:size])
        sent += size


@server.tool()
def flood_stdout(total: int, chunk: int = 65_536, newline: bool = False) -> str:
    """Raw bytes on stdout, no framing. ``chunk`` = ``total`` is a single huge write; ``total`` < 0 never stops."""
    _flood(_wire(), total, chunk)
    if newline:
        _write_all(_wire(), b"\n")
    return "done"


@server.tool()
def huge_line(size: int) -> str:
    """One syntactically plausible JSON-RPC response of ``size`` bytes, WITH a newline (a huge message, not a stream without newline)."""
    _write_all(_wire(), b'{"jsonrpc":"2.0","id":1,"result":"' + b"A" * size + b'"}\n')
    return "done"


@server.tool()
def stderr_burst(total: int) -> str:
    """``total`` bytes on stderr (the first line carries a credential-looking string), then a NORMAL response."""
    _write_all(2, b"startup warning sk-ant-api03-STDERR-SECRET-0123456789abcdef\n")
    _flood(2, total, 4096)
    return "stderr written"


def _stderr_forever() -> None:
    _flood(2, -1, 65_536)


@server.tool()
def stderr_forever() -> str:
    """Starts a thread that writes to stderr without ever stopping, and returns normally."""
    import threading

    threading.Thread(target=_stderr_forever, daemon=True).start()
    return "started"


@server.tool()
def pressure(stdout_total: int) -> str:
    """stderr never stops AND stdout is flooded without newline at the same time."""
    import threading

    threading.Thread(target=_stderr_forever, daemon=True).start()
    _flood(_wire(), stdout_total, 65_536)
    return "done"


@server.tool()
def drip(partial: int) -> str:
    """Writes ``partial`` bytes of a message, never finishes it, never sends a newline (a stall in the middle of an output)."""
    import time

    _write_all(_wire(), b"Y" * partial)
    time.sleep(3600)
    return "never"


@server.tool()
def effect(counter: str) -> str:
    """An external side effect: appends one line to a counter file that lives OUTSIDE the server process and survives it."""
    with open(counter, "a", encoding="utf-8") as handle:
        handle.write("1\n")
    return "applied"


@server.tool()
def effect_then_flood(counter: str) -> str:
    """The side effect happens, THEN the response is replaced by an endless stdout flood: the result is lost, the effect is not."""
    effect(counter)
    _flood(_wire(), -1, 65_536)
    return "never"


@server.tool()
def chatter(count: int, size: int = 100) -> str:
    """``count`` individually VALID JSON-RPC notifications of about ``size`` bytes each, written to the wire (an aggregate-volume attack)."""
    line = b'{"jsonrpc":"2.0","method":"notifications/message","params":{"level":"info","data":"' + b"N" * size + b'"}}\n'
    for _ in range(count):
        _write_all(_wire(), line)
    return "chatted"


@server.tool()
def effect_then_hang(counter: str) -> str:
    """The side effect happens, THEN the tool never answers (a timeout after the request was dispatched)."""
    import time

    effect(counter)
    time.sleep(3600)
    return "never"


@server.tool()
def run_shell(command: str) -> str:
    """A forbidden capability the registry must refuse to register."""
    return command


if __name__ == "__main__":
    server.run("stdio")
