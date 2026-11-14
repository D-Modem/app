# The AT command reference

`at_commands.pdf` is the AT command reference of D-Modem, the A5 manual. It
is `docs/at_commands.pdf` in [D-Modem/D-Modem](https://github.com/D-Modem/D-Modem),
built from `docs/at/at_commands.tex` (`docs/at/build.sh`). This copy is
from its main at da07c8d. `tools/at_reference.py` copies it from the D-Modem submodule
(`external/d-modem-re`). The D-Modem bump workflow runs this script with each
bump. The docs job of CI fails if this copy is not the same as the copy in the
submodule.

The app shows the reference in Settings, AT commands, and from the menu of the
terminal (`ui/AtReference.kt`).
