"""The bounded transport (app/mcp/bounded_stdio.py) depends on PRIVATE parts of the MCP Python SDK. They are pinned by ``mcp==2.3.0``.

This file is the compatibility contract: it fails clearly, naming what changed, if an SDK upgrade moves any of them. After an upgrade,
re-audit ``bounded_stdio.py`` against the SDK's ``mcp/client/stdio.py`` (reader, writer, shutdown, process creation) and rerun
``tests/test_mcp_output_limits.py``, ``tests/test_mcp_aggregate_and_classification.py`` and ``tests/test_mcp_lifecycle.py`` on
Windows AND Linux before changing the pin. docs/SPRINT_C3_6_SECURITY_INTEGRATION_REPORT.md lists the exact private APIs.
"""

import importlib.metadata
import inspect

import pytest

EXPECTED_SDK = "2.3.0"

# (module, attribute, expected parameter names, is coroutine function)
PRIVATE_CONTRACTS = [
    ("mcp.client.stdio", "_aclose_all", ["streams"], True),
    ("mcp.client.stdio", "_close_pipe", ["stream"], True),
    ("mcp.client.stdio", "_drain_stdout", ["process"], True),
    ("mcp.client.stdio", "_get_executable_command", ["command"], True),
    ("mcp.client.stdio", "_stop_server_process", ["process"], True),
    ("mcp.client.stdio", "_terminate_process_tree", ["process"], True),
    ("mcp.client.stdio", "get_default_environment", [], False),
    ("mcp.os.win32.utilities", "create_windows_process", ["command", "args", "env", "errlog", "cwd"], True),
]


def test_the_sdk_version_is_the_audited_one():
    assert importlib.metadata.version("mcp") == EXPECTED_SDK, (
        f"MCP SDK is {importlib.metadata.version('mcp')}, the bounded transport was audited against {EXPECTED_SDK}: "
        "see the docstring of this file for the upgrade procedure"
    )


@pytest.mark.parametrize("module,name,parameters,is_coroutine", PRIVATE_CONTRACTS)
def test_each_private_sdk_function_keeps_its_contract(module, name, parameters, is_coroutine):
    target = getattr(importlib.import_module(module), name, None)
    assert target is not None, f"{module}.{name} disappeared: the bounded transport cannot work"
    assert inspect.iscoroutinefunction(target) is is_coroutine, f"{module}.{name} changed between sync and async"
    names = [p.name for p in inspect.signature(target).parameters.values()]
    if name == "_aclose_all":
        names = ["streams"]  # *streams
    assert names == parameters, f"{module}.{name} signature changed: {names}"


def test_the_process_and_stream_types_the_transport_relies_on_are_unchanged():
    from mcp.client import _transport, stdio
    from mcp.os.win32 import utilities
    from mcp.shared.message import SessionMessage

    assert hasattr(utilities, "FallbackProcess") and hasattr(utilities.FallbackProcess, "returncode")
    assert "popen" in inspect.signature(utilities.FallbackProcess.__init__).parameters or "popen_obj" in inspect.signature(utilities.FallbackProcess.__init__).parameters
    assert hasattr(_transport, "TransportStreams") and hasattr(_transport, "Transport")
    assert "message" in inspect.signature(SessionMessage).parameters
    fields = set(stdio.StdioServerParameters.model_fields)
    assert {"command", "args", "env", "cwd", "encoding", "encoding_error_handler"} <= fields


def test_the_sdk_client_still_accepts_a_transport_object():
    import mcp.client.client as client_module

    source = inspect.getsource(client_module)
    assert "_connect_transport(srv)" in source, "mcp.Client no longer accepts a custom Transport: the bounded transport cannot be plugged in"


def test_the_sdk_server_still_serves_the_wire_from_a_private_descriptor_which_the_adversarial_test_server_uses():
    from mcp.server import stdio

    assert hasattr(stdio, "_claims") and hasattr(stdio, "_StreamClaim") and "private_fd" in inspect.signature(stdio._StreamClaim).parameters
