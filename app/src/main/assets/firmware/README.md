# The firmware of the MultiTech modems

| File | For | Size (octets) | SHA-1 | License | Source |
|---|---|---|---|---|---|
| mts_mt9234mu.fw | MultiTech MT9234MU and MT9234ZBA-USB (TI TUSB3410) | 13769 | `5537e93217c509129d5a870b9fe089cd58647070` | unknown | linux-firmware, before June 2026 (`WHENCE`: "Multi-Tech USB fax modems", "Licence: Unknown") |

The TUSB3410 of these modems has no firmware of its own. The app downloads
this file into the chip each time the line starts (`TiFirmwareLoader`).

Multi-Tech Systems, Inc. gave this file to the Linux kernel in 2010 for the
`ti_usb_3410_5052` driver. The file is TI's TUSB3410 UART firmware, compiled
again by MultiTech with some changes. The file has no copyright text and no
license text. MultiTech did not publish a license for it. In June 2026,
linux-firmware removed it with the other files that have an unknown license.

We include the file so that the modem operates without more steps. The
AGPL-3.0 license of D-Modem and the commercial license do not apply to this
file. Multi-Tech Systems, Inc. and Texas Instruments keep their rights in it.
If you are the holder of these rights and you do not agree, tell us and we
will remove the file.

In Settings, Line, you can import a different firmware file. The app then
uses that file in place of this one.
