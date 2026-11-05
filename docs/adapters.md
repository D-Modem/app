# Serial adapters and real phone lines

## A modem for a vintage computer: pico-serial


The phone can be a computer's external modem on a serial cable: on a
USB serial adapter (FTDI, CP210x, CH34x, PL2303 and CDC-ACM chips, under
the chip makers' IDs and the 1100 or so adapter makers' own that Linux's
drivers know: tools/usb-serial-ids; and Exar's XR21V141x and XR21B14xx),
Bluetooth serial, or TCP. [pico-serial](https://github.com/D-Modem/hardware)
is a USB-C to DB25 adapter made for it: plugged into the computer's
serial port it is the modem there. The app finds its control interface
and uses its lines and settings (its firmware/PROTOCOL.md):

- the modem's DCD, DSR and RI each their own: a call ringing in raises RI
  on the computer, as a real modem's ring does;
- the computer's DTR read from the board, so dropping it hangs up;
- the board's role (automatic, modem or computer port), where its speed
  comes from (learned from the computer's typing, as a Hayes modem does,
  or the app's), RTS/CTS flow control, and what it has carried, in the
  serial port settings.

Any other CDC-ACM adapter's line notifications (`SERIAL_STATE`) are read
too, so the computer dropping DTR hangs up there as well.

## A real phone line: USB modems


The modem's line can also be a telephone line, through a USB modem that
carries the modem's sound (D-Modem's `usb_line.cpp`, as
`dmodemd --usb` drives them): a Smart Link SmartUSB56 (USB 0483:7554,
with caller ID, its ST7554 or ST75951 DAA), or a
[pico-serial](https://github.com/D-Modem/hardware) Line stick
(1209:0002, a Si3056 DAA: caller ID on hook, and the country's line
settings in its registers), or an Agere USB 2.0 soft modem (047E:2892,
047E:2828: 8000 Hz, caller ID, on its ROM's own isochronous stream,
nothing downloaded into it; a USB 2.0 port), or Apple's USB Modem for
Macs (A1101, 05AC:1401: a Si3056 DAA, USB audio for its samples,
`usb_apple.cpp`; caller ID and the country's line settings; not yet
tried on a real one). The phone runs the modem; plugged into it
(USB OTG) the stick is its line, with no SIP account or network. Settings,
Line, "A phone line, through a USB modem"; Android asks once whether
D-Modem may use it, and offers the app when one is plugged in.

## A real phone line: a modem on a USB serial adapter


An external modem of its own (a US Robotics, a Zoom, any Hayes modem) on a
USB serial adapter plugged into the phone (a Prolific PL2303, PL2303GS
among them, FTDI, CP210x, CH34x, CDC-ACM, Exar: the adapters the serial
bridges know) can take D-Modem's modem's place: Settings, Line, "A phone
line, through a modem on a USB serial adapter", its speed (8N1) and
RTS/CTS. As with a Bluetooth modem, the terminal, the internet, fax and the
Linux's `/dev/ttyDM0` talk to it with its own AT commands; the status comes
from its result codes and its DCD, where the adapter reads it. DTR is held
up while the modem runs, and stopping it drops DTR (a modem at `&D2` hangs
up). Android asks once whether D-Modem may use the adapter
(`SerialAdapters`, which also keeps the modem's line, the Linux's
`/dev/ttyUSB0` and the USB bridge off each other's adapter). `SerialModemTest`.

## A real phone line: a Bluetooth modem


A modem of its own with Bluetooth's Dial-Up Networking profile, such as
Sony's BTA-NW1 (Bluetooth 1.1) on a phone line, can take D-Modem's
modem's place: Settings, Line, "A phone line, through a Bluetooth modem",
and the paired modem chosen (pair it in Android's Bluetooth settings
first; an older one asks for its PIN). The app connects to its DUN
service (unencrypted, or its serial port, if need be) and the terminal,
the internet and fax talk to it with its own AT commands; the status
(online, its speed) comes from its result codes. Its connection
dropping, the app connects again once.

An app can't list the USB devices itself: Android opens the one the user
allowed and gives its descriptor. libusb is built for Android in the app
(`app/src/main/cpp/usb/libusb.cmake`, D-Modem's pinned release) and
`usb_line.cpp` built with its three calls that look for the device
renamed to `usb/usb_android.cpp`'s, which start libusb without its device
discovery and give it that descriptor, wrapped, as the only device on the
bus. Tested on the build host on D-Modem's fake pico-serial Line
sticks, Agere modems and Apple USB Modems, and simulated exchange (`native-test/usb_line_test.py`, its `agere` run on the ROM's isochronous stream, its `apple` run on USB audio's streams: dialling,
caller ID, answering, CONNECT 33600, data each way, hanging up, unplugged);
not yet on a phone with a real stick. The phone's speaker isn't fed on
this line.
