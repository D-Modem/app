#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Neo-Desktop
#
#    settings_ref.py - docs/settings.md, the apps' settings from their code:
#                      Android's ModemSettings and the DataStore key each
#                      is kept under (SettingsStore), iOS's ModemSettings
#                      and the Keychain names of its secrets
#
#    tools/settings_ref.py           writes the page
#    tools/settings_ref.py --check   exit 1 if it is out of date, or an
#                                    Android setting has no key
"""docs/settings.md from the apps' ModemSettings."""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "settings.md")
KOTLIN = "app/src/main/java/org/dmodem/app/android/settings/ModemSettings.kt"
LOGFORMAT = "app/src/main/java/org/dmodem/app/android/modem/LogFormat.kt"
SWIFT = "ios/DModemKit/Sources/DModemKit/ModemSettings.swift"


def read(rel):
    """A file of the repository, by its path from the root."""
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def cell(s):
    """Text for a table cell."""
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return " ".join(s.replace("|", "\\|").split())


def sentence(s):
    """Text as a sentence: a capital, a full stop."""
    s = " ".join(s.split())
    if not s:
        return ""
    s = s[0].upper() + s[1:]
    return s if s.endswith(".") else s + "."


def doc_text(lines):
    """A doc comment's text, its markers gone."""
    out = []
    for l in lines:
        l = l.strip()
        l = re.sub(r"^(/\*\*|\*/|\*|///)", "", l)
        l = re.sub(r"\*/$", "", l).strip()
        if l:
            out.append(l)
    return " ".join(out)


def fields(body, pattern):
    """(name, type, default, doc, first) for each field matching pattern.
    A doc comment covers the fields right after it, up to a blank line or
    the next doc; first is whether the field is the first it covers."""
    out, doc, first = [], "", False
    lines = body.splitlines()
    i = 0
    while i < len(lines):
        s = lines[i].strip()
        if s.startswith(("/**", "///")):
            block = [s]
            if s.startswith("/**"):
                while "*/" not in lines[i]:
                    i += 1
                    block.append(lines[i])
            else:
                while i + 1 < len(lines) and lines[i + 1].strip().startswith("///"):
                    i += 1
                    block.append(lines[i])
            doc, first = doc_text(block), True
        elif not s:
            doc = ""
        else:
            m = re.match(pattern, s)
            if m:
                out.append((m.group("name"), m.group("type") or "", m.group("default").strip(), doc, first))
                first = False
        i += 1
    return out


def what(doc, first):
    """A field's what-it-is cell: its doc, or As above after the first field it covers."""
    if not doc:
        return ""
    return cell(sentence(doc)) if first else "As above."


def android():
    """Android's settings, keys, store name, constants and lines."""
    src = read(KOTLIN)
    start = src.index("data class ModemSettings(")
    end = src.index(") {", start)
    fs = fields(src[start:end], r"val (?P<name>\w+): (?P<type>[\w?]+) = (?P<default>.*?),?$")
    keys = dict(re.findall(r"val (\w+) = \w+PreferencesKey\(\"([^\"]+)\"\)", src))
    store = re.search(r'preferencesDataStore\("([^"]+)"\)', src).group(1)
    consts = dict(re.findall(r"const val (\w+) = (\"[^\"]*\")", src))
    consts.update({"LogFormat." + k: v for k, v in
                   re.findall(r"const val (\w+) = (\"[^\"]*\")", read(LOGFORMAT))})
    lines = sorted((k, v) for k, v in consts.items() if k.startswith("LINE_"))
    return fs, keys, store, consts, lines


def ios():
    """iOS's settings, Keychain names and lines."""
    src = read(SWIFT)
    start = src.index("public struct ModemSettings")
    end = src.index("/// the defaults", start)
    fs = fields(src[start:end], r"public var (?P<name>\w+)(?:: (?P<type>\w+))? = (?P<default>.*)$")
    secrets = {f: k for k, f in re.findall(r'\("([^"]+)", \\\.(\w+)\)', src)}
    m = re.search(r"case ([\w, ]+)$", src, re.M)
    lines = [c.strip() for c in m.group(1).split(",")] if m else []
    return fs, secrets, lines


def kind(typ, default):
    """A setting's kind (text, number, on/off, choice) from its type or default."""
    if typ:
        return typ.rstrip("?").replace("Int", "number").replace("Boolean", "on/off").replace("String", "text")
    if default in ("true", "false"):
        return "on/off"
    if re.fullmatch(r"-?\d+", default):
        return "number"
    if default.startswith('"'):
        return "text"
    return "choice"


def show_default(d, consts):
    """A default as the page shows it, constants resolved."""
    d = consts.get(d, d)
    if d == '""':
        return "(empty)"
    return "`%s`" % d.strip('"')


def page(problems):
    """The page; what is wrong goes in problems."""
    fs, keys, store, consts, alines = android()
    ifs, secrets, ilines = ios()
    b = ["<!-- Made by tools/settings_ref.py from the apps' ModemSettings: "
         "change the code (or the script), not this page. -->\n\n",
         "# Settings reference\n\n",
         "This page lists each setting that the apps keep, from their code. "
         "[The app, screen by screen](features.md) shows where you set each one. "
         "[The modem's line](lines.md) tells what the lines are.\n\n",
         "## Android\n\n",
         "`ModemSettings` ([`ModemSettings.kt`](../%s)). The app keeps each "
         "setting in its private DataStore `%s`, under the key in the table.\n\n" % (KOTLIN, store),
         "| setting | key | kind | default | what it is |\n|---|---|---|---|---|\n"]
    for name, typ, default, doc, first in fs:
        key = keys.get(name)
        if not key:
            problems.append("%s: the setting %s has no DataStore key in SettingsStore.Keys" % (KOTLIN, name))
        b.append("| `%s` | %s | %s | %s | %s |\n" % (
            name, "`%s`" % key if key else "", kind(typ, default), cell(show_default(default, consts)),
            what(doc, first)))
    b += ["\n`line` is one of these values: %s.\n\n" % ", ".join("`%s`" % v.strip('"') for _, v in alines),
          "## iOS\n\n",
          "`ModemSettings` ([`ModemSettings.swift`](../%s)). The app keeps "
          "each setting as JSON, under the name of the setting. But the app "
          "keeps the secrets in the Keychain, under the name in the table.\n\n" % SWIFT,
          "| setting | Keychain | kind | default | what it is |\n|---|---|---|---|---|\n"]
    for name, typ, default, doc, first in ifs:
        default = re.sub(r"^Line\.", "", default)
        b.append("| `%s` | %s | %s | %s | %s |\n" % (
            name, "`%s`" % secrets[name] if name in secrets else "", kind(typ, default),
            cell(show_default(default, {})), what(doc, first)))
    b.append("\n`line` is one of these values: %s.\n" % ", ".join("`%s`" % l for l in ilines))
    return "".join(b)


def main():
    """Write or check the page."""
    problems = []
    text = page(problems)
    old = open(OUT, encoding="utf-8").read() if os.path.exists(OUT) else None
    if "--check" in sys.argv[1:]:
        if old != text:
            problems.append("docs/settings.md is out of date: run tools/settings_ref.py")
    elif old != text:
        with open(OUT, "w", encoding="utf-8") as f:
            f.write(text)
    for p in problems:
        print("settings_ref: " + p, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
