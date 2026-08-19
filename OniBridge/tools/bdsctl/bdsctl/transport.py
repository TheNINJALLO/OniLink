from __future__ import annotations

from dataclasses import dataclass
import socket
import ssl
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

from .errors import SecurityError, ValidationError


USER_AGENT = "OniBridge-bdsctl/0.1 (+https://github.com/Onistone)"
TRANSIENT_HTTP = {408, 429, 500, 502, 503, 504}
APPROVED_HOSTS = {
    "net-secondary.web.minecraft-services.net",
    "www.minecraft.net",
    "minecraft.net",
    "download.microsoft.com",
    "aka.ms",
    "minecraft.azureedge.net",
}
APPROVED_SUFFIXES = (
    ".minecraft.net",
    ".minecraft-services.net",
    ".microsoft.com",
    ".azureedge.net",
)


def validate_url(url: str) -> None:
    parsed = urlparse(url)
    host = (parsed.hostname or "").lower().rstrip(".")
    if parsed.scheme != "https":
        raise SecurityError(f"refusing non-HTTPS URL: {url}")
    if not host or not (
        host in APPROVED_HOSTS
        or any(host.endswith(suffix) for suffix in APPROVED_SUFFIXES)
    ):
        raise SecurityError(
            f"refusing redirect or download from unapproved host {host!r}"
        )
    if parsed.username or parsed.password:
        raise SecurityError("URLs containing credentials are forbidden")


class ApprovedRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        validate_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


@dataclass(slots=True)
class Response:
    body: bytes
    content_type: str
    final_url: str


class HttpTransport:
    def __init__(
        self,
        connect_timeout: float = 120.0,
        total_timeout: float = 900.0,
        retries: int = 3,
    ):
        self.connect_timeout = connect_timeout
        self.total_timeout = total_timeout
        self.retries = retries
        self.opener = build_opener(ApprovedRedirectHandler())

    def _open(self, url: str):
        validate_url(url)
        request = Request(
            url,
            headers={
                "User-Agent": USER_AGENT,
                "Accept": "application/json, application/zip",
            },
        )
        return self.opener.open(request, timeout=self.connect_timeout)

    def get_bytes(self, url: str, max_size: int) -> Response:
        last_error: Exception | None = None
        for attempt in range(self.retries):
            try:
                started = time.monotonic()
                with self._open(url) as response:
                    validate_url(response.geturl())
                    declared = response.headers.get("Content-Length")
                    if declared and int(declared) > max_size:
                        raise ValidationError(f"response exceeds {max_size} byte limit")
                    chunks: list[bytes] = []
                    size = 0
                    while True:
                        if time.monotonic() - started > self.total_timeout:
                            raise TimeoutError("total transfer timeout exceeded")
                        chunk = response.read(min(1024 * 1024, max_size - size + 1))
                        if not chunk:
                            break
                        chunks.append(chunk)
                        size += len(chunk)
                        if size > max_size:
                            raise ValidationError(
                                f"response exceeds {max_size} byte limit"
                            )
                    return Response(
                        b"".join(chunks),
                        response.headers.get("Content-Type", ""),
                        response.geturl(),
                    )
            except HTTPError as exc:
                last_error = exc
                if exc.code not in TRANSIENT_HTTP:
                    raise
            except (TimeoutError, socket.timeout) as exc:
                last_error = exc
            except URLError as exc:
                last_error = exc
                if not isinstance(exc.reason, (TimeoutError, socket.timeout)):
                    raise
            except (ssl.SSLError, SecurityError, ValidationError):
                raise
            if attempt + 1 < self.retries:
                time.sleep(0.25 * (2**attempt))
        raise ValidationError(f"download failed after transient retries: {last_error}")
