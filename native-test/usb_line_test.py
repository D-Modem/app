#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Neo-Desktop
#
# usb_line_test.py <usb_host> <line_exchange> [agere]: the app's engine on
# a USB modem's line (usb_modem.h), two of them (usb_host) on D-Modem-RE's
# fake pico-serial Line sticks (or, agere, its fake Agere USB 2.0 soft
# modems at 8000 Hz, their ROM patches the tests' own in a firmware
# directory, DMODEM_AGERE_FIRMWARE, as the app installs Agere's) whose
# lines are the simulated exchange's, 1001 and 1002 (as D-Modem-RE's
# pico_line_test.py and agere_test.py run dmodemd --usb):
#
# - each says what it's on (ATI3);
# - 1001 dials 1002 (ATX4: after the dial tone, in DTMF); 1002 rings,
#   shows the caller ID (AT#CID=1), answers at the second ring (ATS0=2);
#   both CONNECT, and the engine's status says online; a line of text
#   goes each way; 1001 hangs up (+++ ATH), 1002 NO CARRIER;
# - the exchange gone (the sticks unplugged): both engines stop with an
#   error (EXIT -1), as the app's modem does when the stick is pulled.
import os, re, shutil, subprocess, sys, tempfile, threading, time

host, exchange = sys.argv[1], sys.argv[2]
agere = len(sys.argv) > 3 and sys.argv[3] == "agere"
rate = "8000" if agere else "9600"
tmp = tempfile.mkdtemp(prefix="dmodem-usb-line-")
device = b"Agere USB 2.0 \\(Mercury\\)" if agere else b"pico-serial Line \\(Si3056\\)"
firmware = os.path.join(tmp, "firmware")
if agere:
    # the tests' patches (D-Modem-RE's agere_patch.h test_patch(), as its
    # agere_test.py makes them), a file each, as Agere's are installed
    os.makedirs(firmware)
    LENS = (748, 748, 1156, 508)
    for i, n in enumerate(LENS):
        with open(os.path.join(firmware, "patch%d.bin" % i), "wb") as f:
            f.write(bytes((k * 31 + i * 77 + 5 + (k >> 8)) & 0xff for k in range(n)))

procs = []
events = []
ex = subprocess.Popen([exchange, rate, tmp, "1001", "1002"], stdin=subprocess.PIPE,
                      stdout=subprocess.PIPE, text=True)
procs.append(ex)
threading.Thread(target=lambda: [events.append(l.strip()) for l in ex.stdout], daemon=True).start()
t0 = time.time()
while "ready" not in events and time.time() - t0 < 5:
    time.sleep(0.05)


class Modem:
    """the engine (usb_host) on line <n>'s fake stick: its DTE, its log"""
    def __init__(self, n):
        self.n = n
        sock = os.path.join(tmp, n + ".sock")
        if agere:
            env = dict(os.environ, FAKE_AGERE_SOCKET=sock, DMODEM_AGERE_FIRMWARE=firmware,
                       DMODEM_AGERE_TEST_PATCHES="1")
        else:
            env = dict(os.environ, FAKE_PICO_LINE_SOCKET=sock)
        self.p = subprocess.Popen([host, rate], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, env=env)
        procs.append(self.p)
        self.buf = b""
        self.log = []
        self.lock = threading.Lock()
        threading.Thread(target=self._dte, daemon=True).start()
        threading.Thread(target=self._log, daemon=True).start()

    def _dte(self):
        while True:
            d = os.read(self.p.stdout.fileno(), 4096)
            if not d:
                return
            with self.lock:
                self.buf += d

    def _log(self):
        for l in self.p.stderr:
            self.log.append(l.decode(errors="replace").rstrip())

    def write(self, b):
        self.p.stdin.write(b)
        self.p.stdin.flush()

    def expect(self, pat, t):
        end = time.time() + t
        while time.time() < end:
            with self.lock:
                m = re.search(pat, self.buf)
                if m:
                    self.buf = self.buf[m.end():]
                    return m
            time.sleep(0.05)
        return None

    def cmd(self, c, pat=rb"OK\r\n", t=5):
        self.write(c + b"\r")
        return self.expect(pat, t)

    def logged(self, pat, t):
        end = time.time() + t
        while time.time() < end:
            if any(re.search(pat, l) for l in list(self.log)):
                return True
            time.sleep(0.1)
        return False


ok = True
def check(what, cond):
    global ok
    print("%s: %s" % (what, "yes" if cond else "NO"))
    ok = ok and bool(cond)


try:
    a = Modem("1001")
    b = Modem("1002")
    for m in (a, b):
        check("%s ATE0" % m.n, m.cmd(b"ATE0", t=15))
        check("%s ATI3: %s" % (m.n, device.decode().replace("\\", "")),
              m.cmd(b"ATI3", device + rb"\r\n(\r\n)?OK\r\n"))
    check("1002 answers at the 2nd ring", b.cmd(b"ATS0=2"))
    check("1002 caller ID on", b.cmd(b"AT#CID=1"))
    check("1001 waits for the dial tone", a.cmd(b"ATX4"))

    t = time.time()
    a.write(b"ATD1002\r")
    check("1002 RING", b.expect(rb"RING\r\n", 15))
    cid = b.expect(rb"NMBR = (\d+)\r\n", 6)
    check("1002 caller ID 1001", cid and cid.group(1) == b"1001")
    ca = a.expect(rb"(CONNECT[^\r]*|NO CARRIER|BUSY|NO DIALTONE)\r\n", 50)
    cb = b.expect(rb"(CONNECT[^\r]*|NO CARRIER)\r\n", 10)
    print("1001: %s, 1002: %s, %.1f s" % (ca and ca.group(1).decode(),
                                         cb and cb.group(1).decode(), time.time() - t))
    check("both CONNECT", ca and cb and ca.group(1).startswith(b"CONNECT")
          and cb.group(1).startswith(b"CONNECT"))
    check("the exchange heard 1002 dialled", "1001 dialled 1002" in events)
    check("1001's status: online, off hook", a.logged(r"^STATUS 2 1 [1-9]\d* [1-9]\d*$", 5))
    a.write(b"hello from 1001\r\n")
    check("1002 gets 1001's line", b.expect(rb"hello from 1001", 10))
    b.write(b"hello from 1002\r\n")
    check("1001 gets 1002's line", a.expect(rb"hello from 1002", 10))
    time.sleep(1.2)
    a.write(b"+++")
    check("1001 +++ OK", a.expect(rb"OK\r\n", 5))
    check("1001 ATH", a.cmd(b"ATH"))
    check("1002 NO CARRIER", b.expect(rb"NO CARRIER\r\n", 30))
    check("1001's status: idle, on hook", a.logged(r"^STATUS 0 0 0 0$", 5))

    ex.terminate()
    ex.wait(5)
    for m in (a, b):
        check("%s stops once unplugged (EXIT -1)" % m.n, m.logged(r"^EXIT -1$", 15))
finally:
    for p in reversed(procs):
        if p.poll() is None:
            p.terminate()
    for p in procs:
        try:
            p.wait(5)
        except subprocess.TimeoutExpired:
            p.kill()
    if not ok:
        for m in ("a", "b"):
            if m in globals():
                print("--- %s's log" % globals()[m].n)
                print("\n".join(globals()[m].log[-60:]))
    shutil.rmtree(tmp, ignore_errors=True)
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
