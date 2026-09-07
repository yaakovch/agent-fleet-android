#!/usr/bin/env python3
"""Bounded HTTPS reads with an explicit certificate-checked local fallback."""

from __future__ import annotations

from contextlib import contextmanager
import ipaddress
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


MAX_URL_LENGTH = 2048


class RejectRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(
            req.full_url, code, "release HTTPS redirects are forbidden", headers, fp
        )


DIRECT_HTTPS_OPENER = urllib.request.build_opener(RejectRedirects())


def canonical_https_url(value: str) -> str:
    if not isinstance(value, str) or not 1 <= len(value) <= MAX_URL_LENGTH or any(
        character.isspace()
        or character == "\\"
        or character == "\ufeff"
        or ord(character) < 32
        or 0x7F <= ord(character) <= 0x9F
        for character in value
    ) or not value.isascii() or "%" in value:
        raise ValueError("release URL must be canonical credential-free HTTPS")
    try:
        parsed = urllib.parse.urlsplit(value)
        hostname = parsed.hostname or ""
        port = parsed.port
    except ValueError as error:
        raise ValueError("release URL must be canonical credential-free HTTPS") from error
    if (
        parsed.scheme != "https"
        or not hostname
        or hostname != hostname.lower()
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
        or port not in (None, 443)
        or port == 443
        or not parsed.path.startswith("/")
        or not parsed.path
        or parsed.query
        or "//" in parsed.path
        or "/./" in parsed.path
        or "/../" in parsed.path
        or parsed.path.endswith("/.")
        or parsed.path.endswith("/..")
        or "%2f" in parsed.path.lower()
        or "%5c" in parsed.path.lower()
    ):
        raise ValueError("release URL must be canonical credential-free standard-port HTTPS")
    return value


def origin(value: str) -> tuple[str, str, int]:
    parsed = urllib.parse.urlsplit(canonical_https_url(value))
    return parsed.scheme, parsed.hostname or "", parsed.port or 443


def require_child_url(base_url: str, child_url: str) -> str:
    base = urllib.parse.urlsplit(canonical_https_url(base_url.rstrip("/")))
    child = urllib.parse.urlsplit(canonical_https_url(child_url))
    prefix = base.path.rstrip("/") + "/"
    if origin(base_url.rstrip("/")) != origin(child_url) or not child.path.startswith(prefix):
        raise ValueError("release artifact URL is outside the approved origin or path")
    suffix = child.path[len(prefix):]
    if not suffix or "/" in suffix:
        raise ValueError("release artifact URL is not an immediate child of the approved path")
    return child_url


def loopback_curl_command(url: str, timeout: int, maximum: int | None = None) -> list[str]:
    parsed = urllib.parse.urlsplit(canonical_https_url(url))
    host = parsed.hostname or ""
    command = [
        "curl",
        "--noproxy", "*",
        "--proto", "=https",
        "--fail",
        "--max-redirs", "0",
        "--silent",
        "--show-error",
        "--connect-timeout", str(min(timeout, 20)),
        "--max-time", str(timeout),
    ]
    target = os.environ.get("AGENT_FLEET_RELEASE_HTTPS_CONNECT_TO", "")
    if target:
        address, separator, port_text = target.rpartition(":")
        try:
            ip = ipaddress.IPv4Address(address)
            port = int(port_text)
            private_networks = ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
            if (
                not separator or str(ip) != address or str(port) != port_text or
                not 1 <= port <= 65535 or
                not (ip.is_loopback or any(ip in ipaddress.ip_network(net) for net in private_networks))
            ):
                raise ValueError
        except ValueError as error:
            raise ValueError("local HTTPS target must be a canonical private IPv4 address and port") from error
        # Change only the socket destination. The public HTTPS URL, TLS SNI,
        # certificate verification and HTTP Host remain the approved origin.
        command.extend(("--connect-to", f"{host}:443:{target}"))
    else:
        command.extend(("--resolve", f"{host}:443:127.0.0.1"))
    if maximum is not None:
        command.extend(("--max-filesize", str(maximum)))
    command.append(url)
    return command


def _remaining(deadline: float) -> float:
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError("release HTTPS deadline expired")
    return remaining


@contextmanager
def fetch(url: str, timeout: int, loopback_fallback: bool = False, maximum: int | None = None):
    canonical_https_url(url)
    try:
        response = DIRECT_HTTPS_OPENER.open(url, timeout=timeout)
    except urllib.error.HTTPError:
        raise
    except urllib.error.URLError:
        if not loopback_fallback:
            raise
        command = loopback_curl_command(url, timeout, maximum)
        print(
            "[release] direct HTTPS unavailable; retrying the local publisher through "
            "its configured TLS route with normal certificate validation",
            file=sys.stderr,
        )
        process = subprocess.Popen(command, stdout=subprocess.PIPE)
        assert process.stdout is not None
        try:
            yield process.stdout
            process.stdout.close()
            return_code = process.wait(timeout=timeout + 5)
            if return_code != 0:
                raise RuntimeError(f"loopback HTTPS fetch failed with curl status {return_code}")
        except BaseException:
            if process.poll() is None:
                process.kill()
                process.wait()
            raise
    else:
        try:
            if response.geturl() != url:
                raise ValueError("release HTTPS response URL changed")
            yield response
        finally:
            response.close()


def read_bytes(
    url: str,
    *,
    maximum: int,
    deadline: float,
    loopback_fallback: bool = False,
) -> bytes:
    if not 1 <= maximum <= 1024 * 1024 * 1024:
        raise ValueError("release HTTPS byte limit is invalid")
    timeout = max(1, min(600, int(_remaining(deadline) + 0.999)))
    with fetch(url, timeout=timeout, loopback_fallback=loopback_fallback, maximum=maximum) as response:
        chunks: list[bytes] = []
        observed = 0
        while True:
            remaining = _remaining(deadline)
            try:
                sock = response.fp.raw._sock  # type: ignore[attr-defined]
                sock.settimeout(max(0.1, remaining))
            except AttributeError:
                pass
            chunk = response.read(min(1024 * 1024, maximum + 1 - observed))
            if not chunk:
                break
            chunks.append(chunk)
            observed += len(chunk)
            if observed > maximum:
                raise ValueError("release HTTPS response exceeds its byte limit")
        return b"".join(chunks)
