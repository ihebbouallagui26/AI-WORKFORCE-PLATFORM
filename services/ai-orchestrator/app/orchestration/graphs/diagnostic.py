"""Deterministic diagnostic graph: proves graph construction, state transitions and checkpointing.

It never calls a model, a tool, a shell or the file system: ``begin → work (× target) → finish``.
Conventions every future graph follows: a typed state, small pure nodes wrapped by :func:`checked`
(sensitive data cannot enter state), a builder that takes the checkpointer, and optional
``interrupt_before`` nodes for human-in-the-loop pauses.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable, Sequence
from functools import wraps
from typing import Any

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph

from app.orchestration.state.models import DiagnosticState, assert_state_safe

NodeFn = Callable[[DiagnosticState], Awaitable[dict[str, Any]]]


def checked(node: NodeFn) -> NodeFn:
    """Validates every state update of a node before LangGraph checkpoints it."""

    @wraps(node)
    async def wrapper(state: DiagnosticState) -> dict[str, Any]:
        update = await node(state)
        assert_state_safe(update)
        return update

    return wrapper


@checked
async def begin(state: DiagnosticState) -> dict[str, Any]:
    return {"status": "running", "counter": 0, "steps": ["begin"]}


@checked
async def work(state: DiagnosticState) -> dict[str, Any]:
    counter = state.get("counter", 0) + 1
    fail_after = state.get("fail_after")
    if fail_after is not None and counter > fail_after:
        raise RuntimeError("diagnostic fault injected")  # exercised by recovery tests only
    return {"counter": counter, "steps": [f"work-{counter}"]}


@checked
async def finish(state: DiagnosticState) -> dict[str, Any]:
    return {"status": "completed", "steps": ["finish"], "result": f"counter={state.get('counter', 0)}"}


def _continue(state: DiagnosticState) -> str:
    return "work" if state.get("counter", 0) < state.get("target", 1) else "finish"


def build_diagnostic_graph(checkpointer: BaseCheckpointSaver[Any], *, interrupt_before: Sequence[str] = ()) -> Any:
    graph = StateGraph(DiagnosticState)
    graph.add_node("begin", begin)
    graph.add_node("work", work)
    graph.add_node("finish", finish)
    graph.add_edge(START, "begin")
    graph.add_conditional_edges("begin", _continue, {"work": "work", "finish": "finish"})
    graph.add_conditional_edges("work", _continue, {"work": "work", "finish": "finish"})
    graph.add_edge("finish", END)
    return graph.compile(checkpointer=checkpointer, interrupt_before=list(interrupt_before))
