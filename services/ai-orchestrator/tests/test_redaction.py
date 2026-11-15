"""Security fix 8.2: recursive, bounded redaction of anything shown to people or written to logs (the orchestrator side).

The Core API has the same families in com.aiworkforce.core.common.redaction.Redactor (see RedactorTest there): the shared corpus below
is mirrored in both test suites so a rule cannot exist on one side only.
"""

import copy
import json
import time

import pytest

from app.core.logging import MAX_DEPTH, MAX_ELEMENTS, REDACTED, TRUNCATED, redact, redact_text
from app.orchestration.state.models import assert_state_safe

PEM = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7\nabcdefghijklmnopqrstuvwxyz0123456789+/=\n-----END PRIVATE KEY-----"

# (name, text, a fragment that must NOT survive)
TEXT_CASES = [
    ("bearer", "call with Authorization header Bearer abcdefghijklmnop1234 please", "abcdefghijklmnop1234"),
    ("basic", "curl -H 'Basic dXNlcjpwYXNzd29yZDEyMzQ='", "dXNlcjpwYXNzd29yZDEyMzQ"),
    ("jwt", "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r", "dBjftJeZ4CVPmB92K27uhbUJU1p1r"),
    ("openai/anthropic key", "key sk-ant-api03-ABCDEFGHIJKLMNOPQRSTUVWX0123456789", "ABCDEFGHIJKLMNOPQRSTUVWX0123456789"),
    ("stripe", "sk_" + "live_abcdefghijklmnop12345678", "abcdefghijklmnop12345678"),
    ("google", "AIzaSyD-abcdefghijklmnopqrstuvwxyz01234", "abcdefghijklmnopqrstuvwxyz01234"),
    ("aws access key id", "id AKIAIOSFODNN7EXAMPLE here", "AKIAIOSFODNN7EXAMPLE"),
    ("github", "ghp_abcdefghijklmnopqrstuvwxyz0123456789", "abcdefghijklmnopqrstuvwxyz0123456789"),
    ("github fine-grained", "github_pat_11ABCDEFG0abcdefghijklmnop_qrstuvwxyz", "abcdefghijklmnop_qrstuvwxyz"),
    ("slack", "xox" + "b-123456789012-abcdefghijklmnop", "abcdefghijklmnop"),
    ("gitlab", "glpat-abcdefghijklmnopqrstu", "abcdefghijklmnopqrstu"),
    ("PEM block", "key follows\n" + PEM + "\nend", "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7"),
    ("PEM without END", "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEAabcdefghijklmnopqrstuvwxyz", "MIIEowIBAAKCAQEA"),
    ("postgres connection string", "postgresql://admin:S3cretPassw0rd@db.internal:5432/app", "S3cretPassw0rd"),
    ("mongodb+srv", "mongodb+srv://svc:hunter2hunter2@cluster0.example.net/db", "hunter2hunter2"),
    ("amqp", "amqps://guest:guestpass@broker.example:5671", "guestpass"),
    ("url query token", "https://api.example.com/v1/items?id=7&access_token=abcDEF123456&limit=10", "abcDEF123456"),
    ("url query api key", "https://maps.example.com/x?api_key=AbCdEf1234567890&q=paris", "AbCdEf1234567890"),
    ("signed url", "https://bucket.s3.amazonaws.com/f?X-Amz-Signature=0123456789abcdef0123456789abcdef", "0123456789abcdef0123456789abcdef"),
    ("jdbc password", "jdbc:postgresql://h:5432/db;user=app;password=Sup3rS3cret;ssl=true", "Sup3rS3cret"),
    ("name=value pair", "password=hunter2 and client_secret: 'zzzzzzzzzz'", "hunter2"),
    ("cookie header", "Cookie: session=abcdef0123456789; theme=dark", "abcdef0123456789"),
    ("x-api-key header", "X-API-Key: 0123456789abcdef", "0123456789abcdef"),
]

# text that must come out unchanged (no secret shape in it)
NO_SECRET = [
    "The quarterly numbers were summarised in 3 bullet points.",
    "Visit https://example.com/docs/page?id=7&lang=fr for details",
    "Meeting at 10:30 with the finance team; budget=1200 EUR",
    "File path C:/reports/2026/q1.pdf and branch feat/tool-results",
    "sk is a prefix, ghp alone is not a token, AKIA is short",
]


@pytest.mark.parametrize("name,text,secret", TEXT_CASES, ids=[c[0] for c in TEXT_CASES])
def test_recognizable_secret_shapes_in_text_are_masked(name, text, secret):
    out = redact_text(text)
    assert secret not in out and REDACTED in out, out


@pytest.mark.parametrize("text", NO_SECRET)
def test_ordinary_text_is_not_touched(text):
    assert redact_text(text) == text


def test_the_url_keeps_its_host_and_path_so_a_reviewer_keeps_the_meaning():
    out = redact_text("postgresql://admin:S3cret@db.internal:5432/app?sslmode=require&password=x1y2z3")
    assert "db.internal:5432/app" in out and "sslmode=require" in out and "S3cret" not in out and "x1y2z3" not in out


@pytest.mark.parametrize("key", ["password", "PASSWORD", "Api_Key", "apiKey", "x-api-key", "Authorization", "client_secret", "accessToken",
                                 "refresh_token", "private_key", "Cookie", "session_id", "passphrase", "connection_string", "dsn", "jwt"])
def test_sensitive_keys_are_masked_whatever_the_case_or_the_value_type(key):
    for value in ("a-secret-value", 12345, True, ["x", "y"], {"nested": "value"}):
        assert redact({key: value}) == {key: REDACTED}


def test_nesting_arrays_and_mixed_types_are_all_walked():
    data = {"a": {"b": [{"c": {"password": "p"}}, "plain", 7, None, [{"token": "t"}, "Bearer abcdefghijkl1234"]]}, "n": 1.5}
    out = json.dumps(redact(data))
    for leaked in ('"p"', '"t"', "abcdefghijkl1234"):
        assert leaked not in out
    assert "plain" in out and "1.5" in out                                                    # context survives


def test_the_original_value_is_never_modified():
    data = {"password": "p", "list": [{"token": "t", "text": "Bearer abcdefghijkl1234"}], "t": ("a", "b")}
    before = copy.deepcopy(data)
    redact(data)
    redact(data, truncate=False)
    assert data == before


def test_very_deep_nesting_does_not_crash_and_never_shows_what_is_beyond_the_cap():
    node: object = {"password": "deep-secret", "leaf": "visible-leaf"}
    for _ in range(MAX_DEPTH + 40):
        node = {"child": node}
    out = json.dumps(redact(node))
    assert "deep-secret" not in out and "visible-leaf" not in out and TRUNCATED in out


def test_very_large_structures_are_cut_in_logs_but_not_in_the_state_comparison():
    big = {"items": list(range(1000)), **{f"k{i}": "v" for i in range(500)}}
    cut = redact(big)
    assert len(cut["items"]) == MAX_ELEMENTS + 1 and cut["items"][-1] == TRUNCATED and len(cut) <= MAX_ELEMENTS + 2
    assert redact(big, truncate=False) == big                                                  # the guard sees the whole value
    assert redact("x" * 10_000)[-len(TRUNCATED):] == TRUNCATED and len(redact("x" * 10_000)) < 5000


def test_the_placeholder_is_explicit_and_not_an_empty_value():
    assert redact({"password": ""}) == {"password": REDACTED}                                  # even an empty secret is not shown as empty
    assert REDACTED not in ("", None, "null")


@pytest.mark.parametrize("payload", [
    "A" * 1_000_000,
    "Bearer " + "a" * 200_000,
    "-----BEGIN PRIVATE KEY-----" + "A" * 200_000,
    "eyJ" + "a" * 200_000,
    "://" * 100_000,
    "a://" + "b" * 200_000,
    "password=" + "x" * 200_000,
    "Authorization:" + " " * 200_000,
    ("a:" * 100_000) + "@",
])
def test_pathological_input_is_processed_in_bounded_time(payload):
    started = time.perf_counter()
    redact_text(payload)
    assert time.perf_counter() - started < 3.0, "a redaction expression is not linear on this input"


def test_the_state_guard_still_rejects_secret_looking_state_and_accepts_long_legitimate_state():
    from app.orchestration.state.models import StateSafetyError

    with pytest.raises(StateSafetyError):
        assert_state_safe({"stage": "x", "api_key": "sk-ant-api03-ABCDEFGHIJKLMNOPQRSTUVWX0123456789"})
    with pytest.raises(StateSafetyError):
        assert_state_safe({"stage": "postgresql://admin:S3cret@db/app"})
    assert_state_safe({"steps": [f"stage_{i}" for i in range(500)], "stage": "invoke_model", "loop_blob": "02" + "ab" * 400, "rounds": 3})
