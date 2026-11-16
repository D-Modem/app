# Serial adapters and real phone lines

## A modem for a vintage computer: pico-serial


The phone can be the external modem of a computer on a serial cable. The
connection can be one of these:

- A USB serial adapter. The app knows FTDI, CP210x, CH34x, PL2303 and CDC-ACM
  chips, with the IDs of the chip makers and the IDs of the 1100 (about)
  adapter makers that the Linux drivers know (tools/usb-serial-ids). It also
  knows the XR21V141x and XR21B14xx of Exar.
- Bluetooth serial.
- TCP.

[pico-serial](https://github.com/D-Modem/hardware) is a USB-C to DB25 adapter
for this use. When you connect it to the serial port of the computer, it is
the modem on that port. The app finds its control interface and uses its
lines and settings (refer to its firmware/PROTOCOL.md):

- The DCD, DSR and RI of the modem are each separate lines. An incoming call
  that rings raises RI on the computer, as the ring of a real modem does.
- The app reads the DTR of the computer from the board. Thus, when the
  computer drops DTR, the call hangs up.
- The serial port settings have these items: the role of the board
  (automatic, modem or computer port), the source of its speed (learned from
  the typed characters of the computer, as a Hayes modem does, or set by the
  app), RTS/CTS flow control, and the quantity of data that it carried.

The app also reads the line notifications (`SERIAL_STATE`) of all other
CDC-ACM adapters. Thus, if the computer drops DTR, the call also hangs up on
those adapters.

## A real phone line: USB modems


The line of the modem can also be a telephone line, through a USB modem that
carries the sound of the modem. The app uses `usb_line.cpp` of D-Modem, as
`dmodemd --usb` uses it to control these modems. The app supports these USB
modems:

- A Smart Link SmartUSB56 (USB 0483:7554, with caller ID, and its ST7554 or
  ST75951 DAA).
- A [pico-serial](https://github.com/D-Modem/hardware) Line stick
  (1209:0002, a Si3056 DAA). It has caller ID on hook, and the line settings
  of the country in its registers.
- An Agere USB 2.0 soft modem (047E:2892, 047E:2828). It has 8000 Hz and
  caller ID, and uses the isochronous stream of its ROM. The app downloads
  nothing into it. It needs a USB 2.0 port.
- The Apple USB Modem for Macs (A1101, 05AC:1401). It has a Si3056 DAA and
  uses USB audio for its samples (`usb_apple.cpp`). It has caller ID and the
  line settings of the country. We did not try it on a real modem yet.

The phone runs the modem. When you connect the stick to the phone (USB OTG),
the stick is the line of the modem, without a SIP account or network. Select
Settings, Line, "A phone line, through a USB modem". Android asks one time
if D-Modem can use the stick. When you connect a stick, Android offers the
app.

## A real phone line: a modem on a USB serial adapter


A separate external modem (a US Robotics, a Zoom, or any Hayes modem) can
replace the modem of D-Modem. Connect it to the phone through a USB serial
adapter. The adapter can be one that the serial bridges know: a Prolific
PL2303 (for example, PL2303GS), FTDI, CP210x, CH34x, CDC-ACM or Exar. Select
Settings, Line, "A phone line, through a modem on a USB serial adapter", and
set its speed (8N1) and RTS/CTS.

As with a Bluetooth modem, the terminal, the internet, fax and the
`/dev/ttyDM0` of the Linux send their own AT commands to the modem. The
status comes from its result codes and from its DCD, if the adapter reads
DCD. The app keeps DTR high while the modem runs. When the modem stops, the
app drops DTR (a modem at `&D2` hangs up). Android asks one time if D-Modem
can use the adapter. `SerialAdapters` does this. It also prevents conflicts
between the line of the modem, the `/dev/ttyUSB0` of the Linux and the USB
bridge on an adapter. `SerialModemTest`.

## A real phone line: a MultiTech MT9234MU USB modem


The MultiTech MultiMobile MT9234MU (USB 06E0:F114) is a V.92 data and fax
modem with a USB connector. The MT9234ZBA-USB (06E0:F115, and 06E0:0319 on
older units) is the same modem in a desktop case. These modems have a TI
TUSB3410 USB UART between USB and the modem. The app uses its own driver for
this chip (`TiSerialDriver`). Connect the modem to the phone (USB OTG), and
select Settings, Line, "A phone line, through a modem on a USB serial
adapter". After this step, the modem operates as an external modem on a
serial adapter (see the section above). Set the speed to 115200 and set
RTS/CTS on. The factory setting of the modem is `&K3`.

The TUSB3410 has no firmware of its own. Each time you connect the modem,
the modem shows only the boot ROM of the chip. The app must download the
firmware into the RAM of the chip before it can use the modem. The app does
not include the firmware file, because MultiTech gave no license that lets
other persons distribute it. For the same reason, linux-firmware removed the
file in 2026. Get the file `mts_mt9234mu.fw` from the Linux driver of
MultiTech or from a linux-firmware package of a Linux distribution from
before June 2026 (`/lib/firmware/mts_mt9234mu.fw`). The file
`mts_mt9234zba.fw` has the same code. Then, in Settings, Line, select
"Import the firmware file".

When the line starts, the app does these steps:

1. It sends the firmware to the boot ROM in packets of 64 octets.
2. It resets the modem (`USBDEVFS_RESET`). The modem connects again as a
   new USB device, with the configuration of the firmware.
3. Android asks again if D-Modem can use the modem.
4. The app selects configuration 2 and opens the UART.

The app reads DCD, CTS, DSR and RI from the port status of the chip. The
firmware of the chip stays in its RAM until you disconnect the modem.

The modem has its own AT commands (the MultiTech MT9234 AT Commands
Reference Guide, S000434). These items are important:

- Set the country of the line with `AT%T19,0,<code>` (hexadecimal), then
  `AT&F&W`. `ATI9` shows the country. `AT%T19,0,34` is the default (North
  America and Europe).
- `ATI11` shows the last call on two pages. After the first page, the modem
  waits for a key. Push a key in the terminal to see the second page.
- Commands for the serial port of the modem (`$SB`, `$EB`, `%DC` and other
  commands) do not operate on the USB models.

The MT9234MU-CDC and the MT9234MU-CDC-XR are CDC-ACM models. They do not
need a firmware file. The CDC-ACM driver of the app operates them.

We wrote the driver from the data sheet of the TUSB3410 and from the
ti_usb_3410_5052 driver of Linux. The unit tests (`TiSerialTest`) examine the
requests to a simulated chip. We did not test the driver yet on a phone with
a real MT9234MU.

## A real phone line: a Bluetooth modem


A separate modem with the Dial-Up Networking profile of Bluetooth can
replace the modem of D-Modem. An example is the BTA-NW1 of Sony (Bluetooth
1.1) on a phone line. Select Settings, Line, "A phone line, through a
Bluetooth modem", and select the paired modem. First, pair the modem in the
Bluetooth settings of Android. An older modem asks for its PIN. The app
connects to its DUN service (unencrypted, or its serial port, if necessary).
The terminal, the internet and fax send their own AT commands to the modem.
The status (online, its speed) comes from its result codes. If the
connection drops, the app connects again one time.

An app cannot list the USB devices itself. Android opens the device that the
user permitted and gives its descriptor. The app builds libusb for Android
(`app/src/main/cpp/usb/libusb.cmake`, the pinned release of D-Modem). It
builds `usb_line.cpp` with its three calls that look for the device renamed
to the calls of `usb/usb_android.cpp`. These calls start libusb without its
device discovery. They give it that descriptor, wrapped, as the only device
on the bus.

We tested this on the build host with the fake pico-serial Line sticks,
Agere modems and Apple USB Modems of D-Modem, and a simulated exchange
(`native-test/usb_line_test.py`). Its `agere` run uses the isochronous
stream of the ROM, and its `apple` run uses the streams of USB audio. The
tests cover: the dial, caller ID, the answer, CONNECT 33600, data in each
direction, the hang-up, and a disconnected stick. We did not test it yet on
a phone with a real stick. On this line, the app does not send sound to the
speaker of the phone.
