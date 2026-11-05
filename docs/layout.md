# Source layout


- `external/d-modem-re`: D-Modem (git submodule): the modem core, the
  data pumps, the data link layer, the SIP user agent (on libre) and
  `libdmodem_sip`, the modem on a SIP line as a library.
- `app/src/main/cpp`: the native library, `libdmodem_jni.so`:
  `engine.cpp` runs the modem in a thread of its own with its DTE on a
  socket pair whose other end is the app's, on SIP, a peer line
  (`peer_modem.cpp`) or a USB modem (`usb/`); `jni.cpp` binds it to
  `org.dmodem.app.android.NativeModem`.
- `app/src/main/java/org/dmodem/app/android`: `modem/` the service, the
  state the UI observes and `DteHub`, the modem's DTE shared by the
  console, the terminal and the bridges; `settings/`; `ui/` (Compose).
- `app/src/main/java/org/dmodem/app/android/terminal`: the terminal:
  `TerminalEmulator` (pure Kotlin, unit tested), `TerminalView` (drawing
  and input), `TerminalSession` (the emulator on the modem's DTE),
  `BoxDrawing`, `Themes`, the directory and snippets.
- `native-test`: the engine on the build host, against a dmodemd over
  loopback SIP (dialling out, data both ways, restarting, a call coming
  in, the app closing its end) and through the C API, as GoogleTest
  tests (`engine_test.cpp`, `c_api_test.cpp`), and on fake USB modems
  (`usb_line_test.py`).
