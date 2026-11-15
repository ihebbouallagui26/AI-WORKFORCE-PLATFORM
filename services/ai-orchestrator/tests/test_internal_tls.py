"""Security fix 8.7 against REAL TLS handshakes (local TLS servers, real certificates generated per test run, no mock of the TLS layer).

The production client construction (``httpx.AsyncClient`` + ``ServiceTokenProvider`` + ``CoreApiClient``, exactly as ``app.main``)
must verify the certificate chain, the expiry and the host name, honour a configured internal CA, never fall back to plain HTTP and
never write the service credential in the logs. Not mTLS: only the server is authenticated at the transport layer.
"""

import datetime
import ipaddress
import ssl
import threading
import uuid
from collections.abc import Iterator
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import httpx
import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID
from pydantic import SecretStr

from app.clients.core_api_client import CoreApiClient, CoreApiInvalidResponse, CoreApiRequestError, CoreApiUnavailable, ServiceTokenProvider
from app.core.config import is_secure_url

SECRET = "client-secret-DO-NOT-LOG"
ACCESS = "access-token-DO-NOT-LOG"
ORG, AGENT = uuid.uuid4(), uuid.uuid4()
CONTEXT_PATH = f"/api/v1/internal/organizations/{ORG}/agents/{AGENT}/execution-context"


def _key() -> ec.EllipticCurvePrivateKey:
    return ec.generate_private_key(ec.SECP256R1())


def _name(common_name: str) -> x509.Name:
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, common_name)])


def _make_ca(common_name: str) -> tuple[ec.EllipticCurvePrivateKey, x509.Certificate]:
    key, now = _key(), datetime.datetime.now(datetime.UTC)
    cert = (
        x509.CertificateBuilder()
        .subject_name(_name(common_name))
        .issuer_name(_name(common_name))
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=30))
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
        .add_extension(x509.KeyUsage(False, False, False, False, False, True, True, False, False), critical=True)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
        .sign(key, hashes.SHA256())
    )
    return key, cert


def _make_leaf(ca_key, ca_cert, *, dns: str, expired: bool = False) -> tuple[ec.EllipticCurvePrivateKey, x509.Certificate]:
    key, now = _key(), datetime.datetime.now(datetime.UTC)
    start, end = (
        (now - datetime.timedelta(days=10), now - datetime.timedelta(days=1))
        if expired
        else (now - datetime.timedelta(days=1), now + datetime.timedelta(days=30))
    )
    cert = (
        x509.CertificateBuilder()
        .subject_name(_name(dns))
        .issuer_name(ca_cert.subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(start)
        .not_valid_after(end)
        .add_extension(x509.SubjectAlternativeName([x509.DNSName(dns)]), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
        .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .sign(ca_key, hashes.SHA256())
    )
    return key, cert


def _write(directory: Path, name: str, key, cert) -> tuple[Path, Path]:
    cert_path, key_path = directory / f"{name}.crt", directory / f"{name}.key"
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    return cert_path, key_path


class Recorder:
    def __init__(self) -> None:
        self.requests: list[tuple[str, str, str | None]] = []  # method, path, Authorization header


def _handler(recorder: Recorder, redirect_to: str | None = None):
    class Handler(BaseHTTPRequestHandler):
        def _answer(self) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            if length:
                self.rfile.read(length)
            recorder.requests.append((self.command, self.path, self.headers.get("Authorization")))
            if redirect_to:
                self.send_response(302)
                self.send_header("Location", redirect_to)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            body = (f'{{"access_token":"{ACCESS}","expires_in":300}}' if self.path.startswith("/token") else "{}").encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        do_GET = do_POST = _answer

        def log_message(self, *args) -> None:  # silence the stdlib server
            pass

    return Handler


class Server:
    def __init__(self, recorder: Recorder, tls: tuple[Path, Path] | None, redirect_to: str | None = None) -> None:
        self.recorder = recorder
        self._httpd = ThreadingHTTPServer(("127.0.0.1", 0), _handler(recorder, redirect_to))
        if tls:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(str(tls[0]), str(tls[1]))
            self._httpd.socket = context.wrap_socket(self._httpd.socket, server_side=True)
        self.port = self._httpd.server_address[1]
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)
        self._thread.start()

    def close(self) -> None:
        self._httpd.shutdown()
        self._httpd.server_close()


@pytest.fixture
def pki(tmp_path: Path):
    ca_key, ca_cert = _make_ca("Internal Test CA")
    ca_pem = tmp_path / "ca.pem"
    ca_pem.write_bytes(ca_cert.public_bytes(serialization.Encoding.PEM))
    other_key, other_cert = _make_ca("Some Other CA")
    other_pem = tmp_path / "other-ca.pem"
    other_pem.write_bytes(other_cert.public_bytes(serialization.Encoding.PEM))
    return {"dir": tmp_path, "ca_key": ca_key, "ca_cert": ca_cert, "ca_pem": ca_pem, "other_pem": other_pem}


@pytest.fixture
def servers() -> Iterator[list[Server]]:
    started: list[Server] = []
    yield started
    for server in started:
        server.close()


def _tls_server(pki, servers, *, dns: str = "localhost", expired: bool = False, redirect_to: str | None = None) -> Server:
    key, cert = _make_leaf(pki["ca_key"], pki["ca_cert"], dns=dns, expired=expired)
    server = Server(Recorder(), _write(pki["dir"], f"leaf-{len(servers)}", key, cert), redirect_to)
    servers.append(server)
    return server


def _plain_server(servers) -> Server:
    server = Server(Recorder(), None)
    servers.append(server)
    return server


def _production_clients(base_url: str, token_url: str) -> tuple[httpx.AsyncClient, httpx.AsyncClient, ServiceTokenProvider, CoreApiClient]:
    """The very construction of ``app.main.create_app``: default verification, no redirects, no custom transport."""
    core_http = httpx.AsyncClient(base_url=base_url, timeout=5, follow_redirects=False)
    idp_http = httpx.AsyncClient(timeout=5, follow_redirects=False)
    tokens = ServiceTokenProvider(idp_http, token_url, "ai-orchestrator", SecretStr(SECRET))
    return core_http, idp_http, tokens, CoreApiClient(core_http, tokens, max_retries=0)


def _trust(monkeypatch: pytest.MonkeyPatch, ca_pem: Path | None) -> None:
    if ca_pem is None:
        monkeypatch.delenv("SSL_CERT_FILE", raising=False)
    else:
        monkeypatch.setenv("SSL_CERT_FILE", str(ca_pem))  # the documented way to configure an internal CA


async def _close(*clients: httpx.AsyncClient) -> None:
    for client in clients:
        await client.aclose()


async def test_a_certificate_signed_by_the_configured_internal_ca_is_accepted(pki, servers, monkeypatch):
    _trust(monkeypatch, pki["ca_pem"])
    idp, core = _tls_server(pki, servers), _tls_server(pki, servers)
    core_http, idp_http, tokens, client = _production_clients(f"https://localhost:{core.port}", f"https://localhost:{idp.port}/token")
    try:
        token = await tokens.get_token()
        assert token.get_secret_value() == ACCESS
        with pytest.raises(CoreApiInvalidResponse):  # the fake answers `{}`: invalid for the contract, but the TLS path and the call worked
            await client.get_execution_context(ORG, AGENT)
        assert [r[:2] for r in idp.recorder.requests] == [("POST", "/token")]
        assert core.recorder.requests == [("GET", CONTEXT_PATH, f"Bearer {ACCESS}")]  # correct authorization on the verified channel
    finally:
        await _close(core_http, idp_http)


async def test_a_certificate_from_an_untrusted_ca_is_rejected_and_nothing_is_sent(pki, servers, monkeypatch):
    _trust(monkeypatch, pki["other_pem"])  # trusts a different CA only
    idp = _tls_server(pki, servers)
    core_http, idp_http, tokens, _ = _production_clients("https://localhost:1", f"https://localhost:{idp.port}/token")
    try:
        with pytest.raises(CoreApiUnavailable):
            await tokens.get_token()
        assert idp.recorder.requests == []  # the client secret never left the process
    finally:
        await _close(core_http, idp_http)


async def test_the_default_trust_store_does_not_accept_a_private_ca(pki, servers, monkeypatch):
    _trust(monkeypatch, None)
    idp = _tls_server(pki, servers)
    core_http, idp_http, tokens, _ = _production_clients("https://localhost:1", f"https://localhost:{idp.port}/token")
    try:
        with pytest.raises(CoreApiUnavailable):
            await tokens.get_token()
        assert idp.recorder.requests == []
    finally:
        await _close(core_http, idp_http)


async def test_an_expired_certificate_is_rejected(pki, servers, monkeypatch):
    _trust(monkeypatch, pki["ca_pem"])
    idp = _tls_server(pki, servers, expired=True)
    core_http, idp_http, tokens, _ = _production_clients("https://localhost:1", f"https://localhost:{idp.port}/token")
    try:
        with pytest.raises(CoreApiUnavailable):
            await tokens.get_token()
        assert idp.recorder.requests == []
    finally:
        await _close(core_http, idp_http)


async def test_a_host_name_mismatch_is_rejected(pki, servers, monkeypatch):
    _trust(monkeypatch, pki["ca_pem"])
    idp = _tls_server(pki, servers, dns="other.internal.example")  # valid chain, but not issued for "localhost"
    core_http, idp_http, tokens, _ = _production_clients("https://localhost:1", f"https://localhost:{idp.port}/token")
    try:
        with pytest.raises(CoreApiUnavailable):
            await tokens.get_token()
        assert idp.recorder.requests == []
    finally:
        await _close(core_http, idp_http)


async def test_an_ip_address_is_not_accepted_for_a_certificate_issued_to_a_name(pki, servers, monkeypatch):
    _trust(monkeypatch, pki["ca_pem"])
    idp = _tls_server(pki, servers)  # SAN is DNS:localhost only
    core_http, idp_http, tokens, _ = _production_clients("https://localhost:1", f"https://127.0.0.1:{idp.port}/token")
    assert ipaddress.ip_address("127.0.0.1")
    try:
        with pytest.raises(CoreApiUnavailable):
            await tokens.get_token()
        assert idp.recorder.requests == []
    finally:
        await _close(core_http, idp_http)


async def test_a_plain_http_server_behind_an_https_url_fails_without_any_plaintext_fallback(pki, servers, monkeypatch):
    _trust(monkeypatch, pki["ca_pem"])
    plain = _plain_server(servers)
    core_http, idp_http, tokens, _ = _production_clients("https://localhost:1", f"https://localhost:{plain.port}/token")
    try:
        with pytest.raises(CoreApiUnavailable):
            await tokens.get_token()
        assert plain.recorder.requests == []  # no request was ever delivered in clear: no downgrade
    finally:
        await _close(core_http, idp_http)


async def test_a_tls_failure_on_the_core_channel_is_not_retried_over_http_and_leaks_no_token(pki, servers, monkeypatch, capsys):
    _trust(monkeypatch, pki["ca_pem"])
    idp = _tls_server(pki, servers)
    rogue_key, rogue_cert = _make_ca("Rogue")
    untrusted = _tls_server({**pki, "ca_key": rogue_key, "ca_cert": rogue_cert}, servers)  # a Core impersonator
    plain = _plain_server(servers)
    core_http, idp_http, tokens, client = _production_clients(f"https://localhost:{untrusted.port}", f"https://localhost:{idp.port}/token")
    try:
        with pytest.raises(CoreApiUnavailable):
            await client.get_execution_context(ORG, AGENT)
        assert untrusted.recorder.requests == [] and plain.recorder.requests == []  # the bearer token was never presented to the impostor
        output = capsys.readouterr()
        assert ACCESS not in output.out + output.err and SECRET not in output.out + output.err
    finally:
        await _close(core_http, idp_http)


async def test_an_https_redirect_to_http_is_not_followed(pki, servers, monkeypatch):
    _trust(monkeypatch, pki["ca_pem"])
    plain = _plain_server(servers)
    core = _tls_server(pki, servers, redirect_to=f"http://127.0.0.1:{plain.port}/stolen")
    idp = _tls_server(pki, servers)
    core_http, idp_http, tokens, client = _production_clients(f"https://localhost:{core.port}", f"https://localhost:{idp.port}/token")
    try:
        with pytest.raises(CoreApiRequestError):
            await client.get_execution_context(ORG, AGENT)
        assert plain.recorder.requests == []  # the Authorization header never reached the plain-HTTP location
    finally:
        await _close(core_http, idp_http)


def test_the_orchestrator_application_code_never_disables_verification():
    forbidden = ("verify=False", "verify = False", "CERT_NONE", "check_hostname = False", "_create_unverified_context", "trust_env=False")
    offenders = [
        f"{path.name}: {token}" for path in Path("app").rglob("*.py") for token in forbidden if token in path.read_text(encoding="utf-8")
    ]
    assert offenders == []


@pytest.mark.parametrize(
    "url", ["http://core.internal:8081", "http://localhost:8081", "http://127.0.0.1:8081", "ftp://core", "https://u:p@core"]
)
def test_production_accepts_no_url_other_than_credential_free_https(url):
    assert not is_secure_url(url, allow_loopback_http=False)


def test_a_plain_https_url_is_secure_and_loopback_http_is_a_development_only_exception():
    assert is_secure_url("https://core.internal:8443", allow_loopback_http=False)
    assert is_secure_url("http://localhost:8081") and not is_secure_url("http://core.internal:8081")
