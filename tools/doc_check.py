#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Neo-Desktop
#
#    doc_check.py - every function the app defines has a comment: on the
#                   line just above it, or above its annotations (Kotlin,
#                   Swift, shell), or a docstring (Python)
#
#    usage: tools/doc_check.py [FILE...]
#
#    Without FILEs, git's tracked *.kt, *.kts, *.swift, *.py, *.sh, *.c,
#    *.cpp and *.h outside external/.  C and C++ go to D-Modem-RE's own
#    check (external/d-modem-re/cmake/doc_check.py), the same rule.
#    Prints each undocumented function as file:line and exits 1 if there
#    are any.
#
#    Kotlin and Swift: a function is `fun` or `func` (and Swift's init)
#    starting a declaration, after any modifiers; local functions,
#    overrides and ones without a body (an interface's) count too, not
#    `fun interface` nor an anonymous `fun(`.  Block comments and raw
#    strings are blanked first.

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RE_CHECK = os.path.join(ROOT, "external", "d-modem-re", "cmake", "doc_check.py")
C_EXTS = (".c", ".cpp", ".h", ".hpp")
EXTS = (".kt", ".kts", ".swift", ".py", ".sh") + C_EXTS

MODS = (r"(?:public|private|internal|protected|open|override|final|abstract|inline|"
        r"noinline|crossinline|suspend|operator|infix|tailrec|external|actual|expect|"
        r"static|class|mutating|nonmutating|fileprivate|convenience|required|dynamic|"
        r"nonisolated|isolated|lazy|optional|indirect|consuming|borrowing)")
ANNOT = r"@[\w.]+(?:\([^()]*(?:\([^()]*\)[^()]*)*\))?"
FUN = re.compile(r"^\s*(?:" + ANNOT + r"\s+)*(?:" + MODS + r"\s+)*(?:fun|func)\s+(?!interface\b)[\w`<]")
INIT = re.compile(r"^\s*(?:" + ANNOT + r"\s+)*(?:" + MODS + r"\s+)*(?:init[?!]?\s*[(<]|deinit\b)")
ANNOT_LINE = re.compile(r"^\s*(?:" + ANNOT + r"\s*)+$")
SH_FUN =re.compile(r"^\s*(?:function\s+)?[A-Za-z_][\w-]*\s*\(\s*\)\s*\{?\s*$")
PY_DEF = re.compile(r"^(\s*)(?:async\s+)?def\s+\w+")


def tracked():
    """git's tracked sources of the kinds checked, outside external/"""
    out = subprocess.run(["git", "-C", ROOT, "ls-files"], capture_output=True, text=True,
                         check=True).stdout.split("\n")
    return [f for f in out if f.endswith(EXTS) and not f.startswith("external/")]


def blank(text):
    """block comments and triple-quoted strings blanked, their lines kept
    (a comment's last line keeps its "*/", so it still documents what
    follows)"""
    out, i, n = [], 0, len(text)
    while i < n:
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            body = text[i:j]
            out.append(re.sub(r"[^\n]", " ", body[:-2]) + "*/" if body.endswith("*/") else
                       re.sub(r"[^\n]", " ", body))
            i = j
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append('""' + re.sub(r"[^\n]", " ", text[i + 2:j - 1]) + '"' if j - i >= 6 else
                       re.sub(r"[^\n]", " ", text[i:j]))
            i = j
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def commented(lines, i, raw):
    """a comment on the line above line i, past its annotations (lines of
    annotations alone: not an annotated property's, as @Volatile var)"""
    j = i - 1
    while j >= 0 and ANNOT_LINE.match(lines[j]):
        j -= 1
    if j < 0:
        return False
    p = raw[j].strip()
    return p.startswith("//") or p.startswith("/*") or p.startswith("*") or p.endswith("*/")


def check_kt_swift(path, text):
    """Kotlin's or Swift's undocumented functions: (line, text) each"""
    raw = text.split("\n")
    lines = blank(text).split("\n")
    bad = []
    swift = path.endswith(".swift")
    for i, l in enumerate(lines):
        if FUN.match(l) or (swift and INIT.match(l)):
            if not commented(lines, i, raw):
                bad.append((i + 1, raw[i].strip()))
    return bad


def check_sh(path, text):
    """a shell script's functions without a # comment above: (line, text) each"""
    raw = text.split("\n")
    bad = []
    for i, l in enumerate(raw):
        if SH_FUN.match(l) and not l.strip().startswith("#"):
            p = raw[i - 1].strip() if i else ""
            if not p.startswith("#"):
                bad.append((i + 1, l.strip()))
    return bad


def check_py(path, text):
    """Python's functions without a docstring or a # comment above: (line, text) each"""
    raw = text.split("\n")
    bad = []
    for i, l in enumerate(raw):
        m = PY_DEF.match(l)
        if not m:
            continue
        # (past a signature over several lines, to its colon)
        k = i
        while k < len(raw) and not re.search(r":\s*(#.*)?$", raw[k]):
            k += 1
        nxt = next((x.strip() for x in raw[k + 1:] if x.strip()), "")
        j = i - 1
        while j >= 0 and raw[j].strip().startswith("@"):
            j -= 1
        above = raw[j].strip() if j >= 0 else ""
        if not (nxt.startswith(('"""', "'''", 'r"""')) or above.startswith("#")):
            bad.append((i + 1, l.strip()))
    return bad


def main(argv):
    """the files checked (all tracked ones without arguments): 1 if any is undocumented"""
    files = argv[1:] or tracked()
    total = 0
    c_files = []
    for f in files:
        path = f if os.path.isabs(f) else os.path.join(ROOT, f)
        rel = os.path.relpath(path, ROOT)
        if rel.endswith(C_EXTS):
            c_files.append(rel)
            continue
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except OSError as e:
            print("%s: %s" % (rel, e))
            total += 1
            continue
        if rel.endswith((".kt", ".kts", ".swift")):
            bad = check_kt_swift(rel, text)
        elif rel.endswith(".py"):
            bad = check_py(rel, text)
        else:
            bad = check_sh(rel, text)
        for line, what in bad:
            print("%s:%d: undocumented: %s" % (rel, line, what))
        total += len(bad)
    print("%d files, %d undocumented functions" % (len(files) - len(c_files), total))
    rc = 1 if total else 0
    if c_files:
        if not os.path.exists(RE_CHECK):
            print("%s: missing (git submodule update --init external/d-modem-re)" % RE_CHECK)
            return 1
        r = subprocess.run([sys.executable, RE_CHECK, ROOT] + c_files, cwd=ROOT)
        rc = rc or r.returncode
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv))
