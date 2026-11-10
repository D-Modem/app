#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Neo-Desktop
#
#    at_reference.py - the app's AT command reference (its assets'
#                      docs/at_commands.pdf) from the D-Modem submodule's
#                      docs/at_commands.pdf, and the commit its README names
#
#    tools/at_reference.py           copies it, if it differs
#    tools/at_reference.py --check   exit 1 if the app's copy is not the
#                                    submodule's
"""The app's AT command reference from the D-Modem submodule's."""

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DMODEM = os.path.join(ROOT, "external", "d-modem-re")
SOURCE = os.path.join(DMODEM, "docs", "at_commands.pdf")
ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "docs", "at_commands.pdf")
README = os.path.join(ROOT, "app", "src", "main", "assets", "docs", "README.md")


def read(path):
    """A file's bytes."""
    with open(path, "rb") as f:
        return f.read()


def commit():
    """The submodule's commit, short."""
    return subprocess.run(["git", "-C", DMODEM, "rev-parse", "--short=7", "HEAD"],
                          check=True, capture_output=True, text=True).stdout.strip()


def main():
    """Copy (or with --check, compare) the PDF; 1 when --check finds it stale."""
    if not os.path.exists(SOURCE):
        sys.exit(f"{SOURCE} not found: git submodule update --init external/d-modem-re")
    new = read(SOURCE)
    if new == read(ASSET):
        print("AT command reference: up to date")
        return 0
    if "--check" in sys.argv[1:]:
        print("AT command reference: the app's copy is not D-Modem's "
              "(tools/at_reference.py copies it)", file=sys.stderr)
        return 1
    with open(ASSET, "wb") as f:
        f.write(new)
    with open(README, encoding="utf-8") as f:
        text = f.read()
    text = re.sub(r"from its main at [0-9a-f]+\.", f"from its main at {commit()}.", text)
    with open(README, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"AT command reference: copied from D-Modem {commit()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
