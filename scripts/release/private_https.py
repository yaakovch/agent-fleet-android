#!/usr/bin/env python3
"""HTTPS reads with an explicit same-controller loopback fallback."""

from contextlib import contextmanager
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request


def loopback_curl_command(url: str, timeout: int) -> list[str]:
    parsed = urllib.parse.urlsplit(url)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.port not in (None, 443)
        or parsed.fragment
    ):
        raise ValueError("loopback fallback requires a standard-port HTTPS URL without credentials or fragments")
    host = parsed.hostname
    return [
        "curl",
        "--proto",
        "=https",
        "--fail",
        "--silent",
        "--show-error",
        "--connect-timeout",
        str(min(timeout, 20)),
        "--max-time",
        str(timeout),
        "--resolve", f"{host}:443:127.0.0.1",
        url,
    ]


@contextmanager
def fetch(url: str, timeout: int, loopback_fallback: bool = False):
    try:
        response = urllib.request.urlopen(url, timeout=timeout)
    except urllib.error.HTTPError:
        raise
    except urllib.error.URLError:
        if not loopback_fallback:
            raise
        command = loopback_curl_command(url, timeout)
        print(
            "[release] direct HTTPS unavailable; retrying the local publisher through "
            "loopback with normal certificate validation",
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
            yield response
        finally:
            response.close()
