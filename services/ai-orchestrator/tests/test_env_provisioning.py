"""Provisioning of the state-encryption key by ``infra/docker/scripts/ensure-env.sh`` and its Compose wiring.

Everything runs against TEMPORARY env files (``AIW_ENV_FILE`` / ``AIW_ENV_EXAMPLE``): the developer's real ``.env`` is never read
or written. Keys in fixtures are synthetic.
"""

import base64
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[3]
DOCKER_DIR = REPO / "infra" / "docker"
SCRIPT = DOCKER_DIR / "scripts" / "ensure-env.sh"
KEY_NAME = "ORCHESTRATOR_STATE_ENCRYPTION_KEY"
SYNTHETIC = base64.b64encode(bytes(range(32))).decode()


def find_bash() -> str | None:
    if sys.platform == "win32":  # never the WSL launcher in System32
        for candidate in (r"C:\Program Files\Git\bin\bash.exe", r"C:\Program Files (x86)\Git\bin\bash.exe"):
            if os.path.exists(candidate):
                return candidate
        return None
    return shutil.which("bash")


BASH = find_bash()
pytestmark = pytest.mark.skipif(BASH is None or shutil.which("openssl") is None, reason="bash and openssl are required")


def provision(env_file: Path, example: Path | None = None) -> subprocess.CompletedProcess[str]:
    env = {**os.environ, "AIW_ENV_FILE": env_file.as_posix(), "AIW_ENV_EXAMPLE": (example or DOCKER_DIR / ".env.example").as_posix()}
    return subprocess.run([BASH, SCRIPT.as_posix()], env=env, capture_output=True, text=True, timeout=60, check=False)  # noqa: S603


def values(env_file: Path) -> dict[str, str]:
    out = {}
    for line in env_file.read_bytes().decode().splitlines():
        if re.match(r"^[A-Z0-9_]+=", line):
            key, _, value = line.partition("=")
            out[key] = value
    return out


def is_aes_key(value: str) -> bool:
    try:
        return len(base64.b64decode(value, validate=True)) == 32
    except ValueError:
        return False


def test_first_provisioning_generates_a_valid_key_once_and_never_prints_it(tmp_path):
    env_file = tmp_path / ".env"
    result = provision(env_file)
    assert result.returncode == 0
    key = values(env_file)[KEY_NAME]
    assert is_aes_key(key)
    assert key not in result.stdout and key not in result.stderr
    assert values(env_file)["SECRETS_MASTER_KEY"] != key            # independent keys, never shared


def test_a_rerun_changes_nothing(tmp_path):
    env_file = tmp_path / ".env"
    provision(env_file)
    before = env_file.read_bytes()
    result = provision(env_file)
    assert result.returncode == 0 and env_file.read_bytes() == before
    assert "nothing added" in result.stdout


def test_existing_secrets_are_preserved_and_the_key_is_added_exactly_once(tmp_path):
    env_file = tmp_path / ".env"
    original = "DB_PASSWORD=keep-me-1\nSECRETS_MASTER_KEY=keep-me-2\nORCHESTRATOR_CLIENT_SECRET=keep-me-3\n"
    env_file.write_text(original, encoding="utf-8", newline="")
    assert provision(env_file).returncode == 0
    content = env_file.read_text(encoding="utf-8")
    assert content.startswith(original)                              # existing lines untouched, in place
    assert len(re.findall(rf"^{KEY_NAME}=", content, flags=re.M)) == 1
    assert is_aes_key(values(env_file)[KEY_NAME])


def test_an_existing_valid_key_is_never_replaced_even_with_crlf_line_endings(tmp_path):
    env_file = tmp_path / ".env"
    env_file.write_bytes(f"DB_PASSWORD=x\r\n{KEY_NAME}={SYNTHETIC}\r\n".encode())
    before = env_file.read_bytes()
    result = provision(env_file)
    assert result.returncode == 0
    assert env_file.read_bytes().startswith(before)                  # the key line is byte-identical
    assert env_file.read_bytes().count(SYNTHETIC.encode()) == 1


@pytest.mark.parametrize("bad", ["", "not-base64", base64.b64encode(b"short").decode(), SYNTHETIC[:-1], SYNTHETIC + "A"])
def test_a_malformed_or_empty_existing_key_stops_the_script_without_changing_anything(tmp_path, bad):
    env_file = tmp_path / ".env"
    env_file.write_text(f"DB_PASSWORD=x\n{KEY_NAME}={bad}\n", encoding="utf-8", newline="")
    before = env_file.read_bytes()
    result = provision(env_file)
    assert result.returncode != 0
    assert env_file.read_bytes() == before                            # nothing was added or regenerated
    assert KEY_NAME in result.stderr and (not bad or bad not in result.stdout + result.stderr)


def test_a_crlf_example_file_does_not_leak_a_carriage_return_into_values(tmp_path):
    example = tmp_path / ".env.example"
    example.write_bytes(f"PLAIN=value\r\n{KEY_NAME}=\r\n".encode())
    env_file = tmp_path / ".env"
    assert provision(env_file, example).returncode == 0
    parsed = values(env_file)
    assert parsed["PLAIN"] == "value" and is_aes_key(parsed[KEY_NAME]) and b"\r" not in env_file.read_bytes()


def test_the_example_file_carries_no_usable_default_and_no_source_embeds_a_key():
    example = (DOCKER_DIR / ".env.example").read_text(encoding="utf-8")
    assert re.search(rf"^{KEY_NAME}=\s*$", example, flags=re.M)         # empty: generated, never a shipped default
    for path in (DOCKER_DIR / "compose.yaml", REPO / "services" / "ai-orchestrator" / "Dockerfile"):
        text = path.read_text(encoding="utf-8")
        assert not re.search(r"AIO_STATE_ENCRYPTION_KEY[:=]\s*[A-Za-z0-9+/]{43}=", text)
        assert "ARG " + KEY_NAME not in text and "ARG AIO_STATE" not in text and "ENV AIO_STATE" not in text


def compose(env_file: Path, *extra: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(  # noqa: S603
        ["docker", "compose", "-f", str(DOCKER_DIR / "compose.yaml"), "--env-file", str(env_file), "--profile", "orchestrator", "config", *extra],  # noqa: S607
        capture_output=True, text=True, timeout=120, check=False,
    )


@pytest.mark.skipif(shutil.which("docker") is None, reason="docker is required")
def test_compose_requires_the_key_and_passes_it_only_as_a_runtime_environment_value(tmp_path):
    env_file = tmp_path / ".env"
    assert provision(env_file).returncode == 0
    key = values(env_file)[KEY_NAME]

    ok = compose(env_file, "--format", "json")
    assert ok.returncode == 0
    service = json.loads(ok.stdout)["services"]["ai-orchestrator"]       # nothing is ever printed: only booleans leave this test
    assert service["environment"]["AIO_STATE_ENCRYPTION_KEY"] == key
    assert key not in json.dumps(service.get("build", {}))                # not a build argument, so not in any image layer

    # Without the key the stack refuses to start (no default, no empty fallback).
    stripped = tmp_path / "no-key.env"
    stripped.write_text("".join(line for line in env_file.read_text().splitlines(keepends=True) if not line.startswith(KEY_NAME)))
    missing = compose(stripped)
    assert missing.returncode != 0 and KEY_NAME in missing.stderr and key not in missing.stderr
    empty = tmp_path / "empty-key.env"
    empty.write_text(stripped.read_text() + f"{KEY_NAME}=\n")
    assert compose(empty).returncode != 0
