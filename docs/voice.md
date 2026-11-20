# Voice calls and the answering machine

The modem makes and receives voice calls. It can call a number or answer a
call that rings. It uses the earpiece or loudspeaker of the phone, and its
microphone. If you enable the answering machine, it answers the calls that
nobody answers and keeps the messages on the phone.

This is the V.253 voice mode of D-Modem, as fax uses its class 1. The
`VoiceJob` of the shared Kotlin (`shared/.../jobs/VoiceJob.kt`) gets the DTE
of the modem for the call, and sends these commands:

| Step | Command | The modem's answer |
| --- | --- | --- |
| Voice mode, G.711 μ-law at 8000 Hz | `AT+FCLASS=8`, `AT+VSM=4` | `OK` |
| Make a call | `ATD<number>` | `OK` when the far end answers. `BUSY`, `NO ANSWER` |
| Answer the call that rings | `AT+VLS=1` | `OK` |
| The sound of the call, in the two directions | `AT+VTR` | `CONNECT`, then the stream |
| Hang up | `<DLE>^`, then `ATH`, `AT+FCLASS=0` | `<DLE><ETX>`, `OK` |

The stream is μ-law octets in the two directions, with each `<DLE>` doubled.
The `<DLE>` codes of the modem tell what it heard:

- `l`: the far end hung up.
- `b`: a busy tone.
- `d`: a dial tone.
- The DTMF digits that the far end pushed.

A busy tone and a dial tone both mean that the exchange says that the caller
is gone. The app sends its samples at the rate of the line, by the clock,
60 ms ahead. If the microphone gives no sound, the app sends silence. The
digits of the keypad are DTMF tones in the sound of the app.

Each line of the modem can carry voice: SIP, the line to another phone, and
the phone line of a USB modem (if that modem has voice mode). A modem without
voice mode answers `ERROR` to `AT+FCLASS=8`, and the call stops with "the
modem has no voice mode".

## The answering machine

The answering machine is disabled until you enable it (Android: Settings ›
Phone and answering machine. iOS: the Phone tab). When it is enabled, it
answers the call after the set number of rings (4 by default). It plays the
greeting (or no greeting) and a beep. Then it records the caller until one
of these conditions occurs:

- The caller hangs up. The app cuts the start of the busy tone or dial tone from the message.
- The caller is silent for 7 s. The app keeps half a second of the silence.
- The maximum message length (2 minutes by default) is reached.

The app does not keep a message that is shorter than one second. Each
message is a WAV file (16-bit PCM, 8000 Hz, mono). Its name is its time and,
if caller ID gave it, the number of the caller:
`20261111-093000_5551234.wav`. On Android, the files are in the storage of
the app (`files/voicemail`). On iOS, they are in Documents/Voicemail (the
Files app shows them). No data goes out of the phone.

While a caller leaves a message, **Listen** plays it on the loudspeaker.
**Pick up** changes it into a call (the app does not keep the message). You
can record the greeting with the microphone (one minute maximum). You can
also select a sound file with any sample rate. The app mixes the file to one
channel and resamples it to 8000 Hz. The app reads these files itself, the
same on the two phones:

| File | Kinds |
| --- | --- |
| WAV | PCM of 8 to 32 bits, 32 and 64-bit floats, A-law, μ-law, IMA and Microsoft ADPCM |
| AIFF, AIFF-C | PCM, floats, A-law, μ-law |
| Sun/NeXT `.au` | PCM, floats, A-law, μ-law |

The decoders of the phone read all other files. On Android, MediaCodec does
this (MP3, AAC/M4A, FLAC, Ogg Vorbis, Opus, AMR, and all other formats that
the phone has). On iOS, AVAudioFile does this (MP3, AAC/M4A, FLAC, ALAC, CAF).

The fax settings and dial-in answer their calls on the first ring. If one of
them is enabled, the answering machine waits.

## Where it is

- Android: the **Phone** item on Home (call a number, the call while it
  continues, the messages), the popup for an incoming call (**Answer as a
  voice call**, **Take a message**), and Settings › **Phone and answering
  machine**. `voice/Voice.kt` runs the call. `voice/VoiceAudio.kt` runs its
  sound: an AudioTrack for the speaker, and an AudioRecord with the echo
  canceller of the phone (`VOICE_COMMUNICATION`) for the microphone.
- iOS: the **Phone** tab. `JobsModel` runs the call. `VoiceSound.swift` runs
  its sound (AVAudioEngine in the voice chat mode).

The tests (`shared/src/commonTest/.../voice/VoiceTest.kt`) run against a
fake V.253 modem. They test a call, a busy number, a modem without voice
mode, messages that end with a hang-up and with silence, and a message that
the user picks up.
