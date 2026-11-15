"""Security fix 8.3: checkpoint protection (format 2 envelope, derived key, identity-bound AAD) and what a checkpoint really holds.

Unit tests of the sealer, then the tool loop with an in-memory checkpointer. The Postgres-backed checkpointer has its own tests
(test_postgres_checkpoints.py, Docker); the real stack (rebuilt image, real PostgreSQL) is exercised by the end-to-end run.
"""

import os
import typing
import uuid

import pytest
import respx
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.core.crypto import FORMAT_V1, FORMAT_V2, KEY_ID, StateBinding, StateIntegrityError, StateSealer
from app.mcp.executor import ToolOutput
from app.orchestration.graphs import execution as graph_module
from app.orchestration.state.models import ExecutionGraphState
from tests.test_tool_execution import EXECUTION, KEY, ORG, SECRET, TASK, CoreScript, Env, Provider, final, run, saved, tool_use

PLAINTEXT = b'{"system":"Be precise.","messages":[{"role":"user","content":"CONFIDENTIAL-TASK-TEXT account 4111"}]}'
THREAD = f"{ORG}:{EXECUTION}"
INVOCATION = uuid.uuid4()


def binding(**overrides) -> StateBinding:
    values = {"organization_id": ORG, "execution_id": EXECUTION, "thread_id": THREAD, "invocation_id": INVOCATION}
    return StateBinding(**{**values, **overrides})


def legacy_token(key: bytes, plaintext: bytes, org=ORG, execution=EXECUTION) -> str:
    """A format-1 blob exactly as the code wrote it before 8.3: the raw key, AAD ``organization:execution``."""
    nonce = os.urandom(12)
    return (bytes([FORMAT_V1]) + nonce + AESGCM(key).encrypt(nonce, plaintext, f"{org}:{execution}".encode())).hex()


# ---- the envelope ---------------------------------------------------------------------------------------------------------


def test_a_format_2_blob_round_trips_is_hex_and_contains_no_plaintext():
    sealer = StateSealer(KEY)
    token = sealer.seal(PLAINTEXT, binding())
    assert set(token) <= set("0123456789abcdef") and token.startswith("0201")                  # format 2, key id 1
    assert sealer.open(token, binding()) == PLAINTEXT
    raw = bytes.fromhex(token)
    assert b"CONFIDENTIAL" not in raw and b"Be precise" not in raw and len(raw) == 2 + 12 + len(PLAINTEXT) + 16
    assert sealer.seal(PLAINTEXT, binding()) != token                                           # a fresh nonce every time
    assert FORMAT_V2 == 2 and KEY_ID == 1


@pytest.mark.parametrize("field", ["organization_id", "execution_id", "invocation_id"])
def test_a_blob_cannot_be_opened_under_another_organization_execution_or_pause(field):
    sealer = StateSealer(KEY)
    token = sealer.seal(PLAINTEXT, binding())
    with pytest.raises(StateIntegrityError):
        sealer.open(token, binding(**{field: uuid.uuid4()}))


def test_a_blob_cannot_be_opened_under_another_thread_identity():
    sealer = StateSealer(KEY)
    token = sealer.seal(PLAINTEXT, binding())
    with pytest.raises(StateIntegrityError):
        sealer.open(token, binding(thread_id=f"{ORG}:{uuid.uuid4()}"))


def test_the_blob_of_an_earlier_pause_of_the_same_execution_cannot_be_replayed_at_a_later_pause():
    sealer = StateSealer(KEY)
    first_pause = sealer.seal(PLAINTEXT, binding(invocation_id=uuid.uuid4()))
    with pytest.raises(StateIntegrityError):
        sealer.open(first_pause, binding(invocation_id=uuid.uuid4()))                           # the Core API now names another pause


def test_a_wrong_key_and_the_environment_key_used_directly_both_fail():
    token = StateSealer(KEY).seal(PLAINTEXT, binding())
    with pytest.raises(StateIntegrityError):
        StateSealer(os.urandom(32)).open(token, binding())
    # a ciphertext made with the RAW key in the format-2 layout and AAD (purpose separation: the key is derived, not the env key)
    nonce = os.urandom(12)
    aad = StateSealer._aad_v2(binding())  # noqa: SLF001
    forged = (bytes([FORMAT_V2, KEY_ID]) + nonce + AESGCM(KEY).encrypt(nonce, PLAINTEXT, aad)).hex()
    with pytest.raises(StateIntegrityError):
        StateSealer(KEY).open(forged, binding())


def test_every_single_byte_of_the_envelope_is_authenticated():
    sealer = StateSealer(KEY)
    raw = bytearray(bytes.fromhex(sealer.seal(b"short plaintext", binding())))
    for index in range(len(raw)):
        tampered = bytearray(raw)
        tampered[index] ^= 0x01
        with pytest.raises(StateIntegrityError):
            sealer.open(bytes(tampered).hex(), binding())


@pytest.mark.parametrize("header", [bytes([0]), bytes([3]), bytes([255])])
def test_an_unknown_format_is_refused(header):
    sealer = StateSealer(KEY)
    raw = bytearray(bytes.fromhex(sealer.seal(PLAINTEXT, binding())))
    raw[0:1] = header
    with pytest.raises(StateIntegrityError):
        sealer.open(bytes(raw).hex(), binding())


def test_an_unknown_key_id_is_refused_and_so_are_garbage_and_truncated_tokens():
    sealer = StateSealer(KEY)
    raw = bytearray(bytes.fromhex(sealer.seal(PLAINTEXT, binding())))
    raw[1] = 9
    for bad in (bytes(raw).hex(), "", "zz", "01", "0201", "02" + "01" + "00" * 20, PLAINTEXT.hex()):
        with pytest.raises(StateIntegrityError):
            sealer.open(bad, binding())


def test_rewriting_a_format_2_header_as_the_legacy_format_does_not_downgrade_it():
    for accept in (True, False):
        sealer = StateSealer(KEY, accept_legacy=accept)
        raw = bytearray(bytes.fromhex(sealer.seal(PLAINTEXT, binding())))
        downgraded = bytes([FORMAT_V1]) + bytes(raw[2:])                                        # relabelled v1: other key, other AAD, other layout
        with pytest.raises(StateIntegrityError):
            sealer.open(downgraded.hex(), binding())


# ---- backward compatibility with the format written before 8.3 -----------------------------------------------------------------


def test_a_legacy_format_1_blob_still_opens_so_runs_paused_at_the_upgrade_are_not_lost():
    token = legacy_token(KEY, PLAINTEXT)
    assert StateSealer(KEY).open(token, binding()) == PLAINTEXT
    assert StateSealer(KEY).open(token, binding(invocation_id=uuid.uuid4())) == PLAINTEXT      # v1 never had an invocation binding


def test_a_legacy_blob_is_still_bound_to_its_organization_and_execution():
    token = legacy_token(KEY, PLAINTEXT)
    for wrong in (binding(organization_id=uuid.uuid4()), binding(execution_id=uuid.uuid4())):
        with pytest.raises(StateIntegrityError):
            StateSealer(KEY).open(token, wrong)
    with pytest.raises(StateIntegrityError):
        StateSealer(os.urandom(32)).open(token, binding())


def test_the_legacy_format_can_be_switched_off_and_is_then_refused():
    with pytest.raises(StateIntegrityError):
        StateSealer(KEY, accept_legacy=False).open(legacy_token(KEY, PLAINTEXT), binding())


def test_a_sealer_never_writes_the_legacy_format_and_survives_a_restart():
    token = StateSealer(KEY).seal(PLAINTEXT, binding())
    assert bytes.fromhex(token)[0] == FORMAT_V2
    assert StateSealer(KEY).open(token, binding()) == PLAINTEXT                                 # a brand-new instance: the key is the only state


def test_failures_carry_no_detail_and_the_key_must_be_32_bytes():
    token = StateSealer(KEY).seal(PLAINTEXT, binding())
    with pytest.raises(StateIntegrityError) as info:
        StateSealer(KEY).open(token, binding(invocation_id=uuid.uuid4()))
    assert "CONFIDENTIAL" not in str(info.value) and str(ORG) not in str(info.value) and info.value.__cause__ is not None
    with pytest.raises(ValueError):
        StateSealer(b"short")


# ---- what a checkpoint really holds ---------------------------------------------------------------------------------------------


@respx.mock
async def test_a_paused_checkpoint_holds_only_identifiers_counters_and_one_sealed_blob():
    env = Env()
    runner = await env.build()
    core = CoreScript(respx.mock)
    core.decide("toolu_note", "APPROVAL_REQUIRED", approvalId=str(uuid.uuid4()))
    Provider(tool_use("diagnostic_record_note", "toolu_note", {"text": "CONFIDENTIAL-ARGUMENT 4111"}), final())
    await run(runner)
    snapshot = await env.saver.aget_tuple({"configurable": {"thread_id": THREAD}})
    values = snapshot.checkpoint["channel_values"]
    allowed = set(typing.get_type_hints(ExecutionGraphState, include_extras=True)) | {"__start__", "branch:to:load_context"}
    unexpected = {k for k in values if k not in allowed and not k.startswith(("branch:", "__"))}
    assert not unexpected, f"unexpected channels in the checkpoint: {unexpected}"
    assert values["loop_blob"].startswith("0201")                                               # the new envelope
    # the ONLY sensitive channel is the sealed blob: identifiers and counters are everything else
    clear = {k: v for k, v in values.items() if k != "loop_blob" and not k.startswith("branch:")}
    for text in map(repr, clear.values()):
        assert TASK not in text and "CONFIDENTIAL" not in text and SECRET not in text
    everything = saved(env.saver)
    for forbidden in (TASK, "CONFIDENTIAL-ARGUMENT", "Be precise", SECRET):
        assert forbidden not in everything
    await env.close()


# ---- resume after the change ----------------------------------------------------------------------------------------------------


async def pause_then_rewrite_blob(saver, core_env, make_blob):
    """Pauses a run, replaces its sealed blob by ``make_blob(plaintext, awaiting_invocation)``, returns the checkpoint config."""
    config = {"configurable": {"thread_id": THREAD}}
    values = (await saver.aget_tuple(config)).checkpoint["channel_values"]
    sealer = StateSealer(KEY)
    plaintext = sealer.open(values["loop_blob"], binding(invocation_id=uuid.UUID(values["awaiting_invocation"])))
    compiled = graph_module.build_execution_graph(
        saver, graph_module.RunContext(organization_id=ORG, execution_id=EXECUTION), core=core_env.core,
        adapter_factory=core_env.providers.adapter_with, enabled_providers=("ANTHROPIC",), max_output_tokens=300, tools=core_env.tools,
    )
    await compiled.aupdate_state(config, {"loop_blob": make_blob(plaintext, values["awaiting_invocation"])})
    return config


@respx.mock
async def test_a_run_paused_before_the_upgrade_with_a_legacy_blob_resumes_and_runs_the_tool_exactly_once():
    from langgraph.checkpoint.memory import InMemorySaver

    saver = InMemorySaver()
    first = Env(saver=saver)
    runner = await first.build()
    core = CoreScript(respx.mock)
    core.decide("toolu_note", "APPROVAL_REQUIRED", approvalId=str(uuid.uuid4()))
    Provider(tool_use("diagnostic_record_note", "toolu_note", {"text": "Remember the demo"}), final("All noted."))
    await run(runner)
    await pause_then_rewrite_blob(saver, first, lambda plaintext, _inv: legacy_token(KEY, plaintext))   # what the old version had stored
    await first.close()
    second = Env(saver=saver)                                                                    # the new version, accept_legacy default True
    runner = await second.build()
    second.manager.output = ToolOutput(text='{"recorded": true}', truncated=False, is_error=False)
    core.decide("toolu_note", "APPROVED")
    await run(runner, resume=True)
    assert second.manager.calls == [("diagnostic", "record_note", {"text": "Remember the demo"})] and len(core.begins) == 1
    assert core.posted(core.complete)["result"] == "All noted." and core.fail.call_count == 0
    await second.close()


@respx.mock
async def test_with_the_legacy_format_switched_off_such_a_run_fails_closed_and_nothing_executes():
    from langgraph.checkpoint.memory import InMemorySaver

    saver = InMemorySaver()
    first = Env(saver=saver)
    runner = await first.build()
    core = CoreScript(respx.mock)
    core.decide("toolu_note", "APPROVAL_REQUIRED", approvalId=str(uuid.uuid4()))
    Provider(tool_use("diagnostic_record_note", "toolu_note", {"text": "Remember the demo"}), final("All noted."))
    await run(runner)
    await pause_then_rewrite_blob(saver, first, lambda plaintext, _inv: legacy_token(KEY, plaintext))
    await first.close()
    second = Env(saver=saver)
    second.tools = graph_module.ToolRuntime(manager=second.manager, sealer=StateSealer(KEY, accept_legacy=False), max_rounds=6, max_calls=8, output_max_chars=2000)
    runner = await second.build()
    core.decide("toolu_note", "APPROVED")
    await run(runner, resume=True)
    assert core.posted(core.fail)["errorCode"] == "STATE_UNAVAILABLE" and second.manager.calls == [] and core.begins == []
    await second.close()


@respx.mock
async def test_the_blob_of_another_pause_cannot_be_resumed_and_the_tool_is_not_executed():
    from langgraph.checkpoint.memory import InMemorySaver

    saver = InMemorySaver()
    first = Env(saver=saver)
    runner = await first.build()
    core = CoreScript(respx.mock)
    core.decide("toolu_note", "APPROVAL_REQUIRED", approvalId=str(uuid.uuid4()))
    Provider(tool_use("diagnostic_record_note", "toolu_note", {"text": "Remember the demo"}), final("All noted."))
    await run(runner)
    await first.close()
    other_pause = str(uuid.uuid4())
    core.resume_body = {"invocationId": other_pause, "approvalId": str(uuid.uuid4()), "decision": "APPROVED", "toolCode": "diagnostic.record_note"}
    second = Env(saver=saver)
    runner = await second.build()
    await run(runner, resume=True)                                                               # the Core API names a different pause than the blob's
    assert core.posted(core.fail)["errorCode"] == "STATE_UNAVAILABLE" and second.manager.calls == []
    await second.close()
