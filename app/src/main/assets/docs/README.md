# The AT command reference

`at_commands.pdf` is D-Modem's AT command reference, the A5 manual:
`docs/at_commands.pdf` in [D-Modem/D-Modem](https://github.com/D-Modem/D-Modem),
built from `docs/at/at_commands.tex` (`docs/at/build.sh`). This copy is
from its main at ba5ba93. `tools/at_reference.py` copies it from the D-Modem submodule
(`external/d-modem-re`); the D-Modem bump workflow runs it with each bump,
and CI's docs job fails when this copy isn't the submodule's.

The app shows it in Settings, AT commands, and from the terminal's menu
(`ui/AtReference.kt`).
