# Source layout


- `external/d-modem-re`: D-Modem (git submodule). It contains the modem core,
  the data pumps, the data link layer, the SIP user agent (on libre) and
  `libdmodem_sip`, which is the modem on a SIP line as a library.
- `app/src/main/cpp`: the native library, `libdmodem_jni.so`.
  `engine.cpp` runs the modem in its own thread. The DTE of the modem is on a
  socket pair. The other end of the socket pair belongs to the app, on SIP, a
  peer line (`peer_modem.cpp`) or a USB modem (`usb/`). `jni.cpp` binds the
  engine to `org.dmodem.app.android.NativeModem`.
- `app/src/main/java/org/dmodem/app/android`: `modem/` has the service, the
  state that the UI observes, and `DteHub`. `DteHub` is the DTE of the modem
  that the console, the terminal and the bridges share. Also `settings/` and
  `ui/` (Compose).
- `app/src/main/java/org/dmodem/app/android/terminal`: the terminal.
  `TerminalEmulator` (pure Kotlin, with unit tests), `TerminalView` (it draws
  and gets input), `TerminalSession` (the emulator on the DTE of the modem),
  `BoxDrawing`, `Themes`, the directory and the snippets.
- `native-test`: tests of the engine on the build host. The tests are
  GoogleTest tests (`engine_test.cpp`, `c_api_test.cpp`) and tests on fake
  USB modems (`usb_line_test.py`). They run the engine against a dmodemd
  over loopback SIP and through the C API. They test these items: the dial,
  data in the two directions, a restart, an incoming call, and the app when
  it closes its end.
