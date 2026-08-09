#!/usr/bin/env python3
"""Read release secrets and identity pins through one no-follow descriptor."""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import stat
import sys


class IdentityError(ValueError):
    pass


def secure_read(path: pathlib.Path, maximum: int, *, private: bool) -> bytes:
    flags = os.O_RDONLY | os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = os.open(path, flags)
    try:
        before = os.fstat(fd)
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_uid != os.geteuid()
            or (private and stat.S_IMODE(before.st_mode) != 0o600)
            or not 1 <= before.st_size <= maximum
        ):
            requirement = "user-owned mode-0600" if private else "user-owned"
            raise IdentityError(f"release identity file must be a {requirement} bounded regular file")
        payload = os.read(fd, maximum + 1)
        if len(payload) > maximum or os.read(fd, 1):
            raise IdentityError("release identity file exceeds its byte limit")
        after = os.fstat(fd)
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (
            after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns
        ):
            raise IdentityError("release identity file changed while it was read")
        return payload
    finally:
        os.close(fd)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("password", "certificate", "public-certificate"))
    parser.add_argument("path", type=pathlib.Path)
    args = parser.parse_args()
    try:
        payload = secure_read(args.path, 4096, private=args.kind != "public-certificate")
        try:
            value = payload.decode("utf-8")
        except UnicodeError as error:
            raise IdentityError("release identity file is not UTF-8") from error
        if args.kind == "password":
            if not value or "\n" in value or "\r" in value or "\x00" in value:
                raise IdentityError("signing password must be one non-empty line")
        else:
            value = value.removesuffix("\n")
            if not re.fullmatch(r"[a-f0-9]{64}", value):
                raise IdentityError("pinned certificate fingerprint is invalid")
        print(value, end="" if args.kind == "password" else "\n")
        return 0
    except (OSError, IdentityError) as error:
        print(f"release-identity: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
