# Voice calls and the answering machine

The modem makes and takes voice calls: a number called, or the call
ringing answered, with the phone's earpiece or loudspeaker and its
microphone. When it's turned on, the answering machine takes the calls
nobody answers and keeps the messages on the phone.

It is D-Modem's V.253 voice mode, as a fax is its class 1: the shared
Kotlin's `VoiceJob` (`shared/.../jobs/VoiceJob.kt`) is lent the modem's
DTE for the call, and sends

| Step | Command | The modem's answer |
| --- | --- | --- |
| Voice mode, G.711 μ-law at 8000 Hz | `AT+FCLASS=8`, `AT+VSM=4` | `OK` |
| A call made | `ATD<number>` | `OK` once the far end answers; `BUSY`, `NO ANSWER` |
| The ringing call answered | `AT+VLS=1` | `OK` |
| The call's sound, both ways | `AT+VTR` | `CONNECT`, then the stream |
| Hung up | `<DLE>^`, then `ATH`, `AT+FCLASS=0` | `<DLE><ETX>`, `OK` |

The stream is μ-law octets both ways, a `<DLE>` doubled. The modem's
`<DLE>` codes say what it heard: `l` the far end hung up, `b` a busy
tone, `d` a dial tone (both: the exchange saying the caller has gone),
the DTMF digits pressed. Our samples go at the line's pace, by the clock,
60 ms ahead; silence when the microphone has none. The keypad's digits
are DTMF tones in our sound.

Any line the modem has carries it: SIP, the line to another phone, a USB
modem's phone line (when that modem has voice mode). A modem without it
answers `ERROR` to `AT+FCLASS=8`, and the call stops with "the modem has
no voice mode".

## The answering machine

Off until it's turned on (Android: Settings › Phone and answering
machine; iOS: the Phone tab). When it's on, after the rings set (4 by
default) it answers the call, plays the greeting (or none) and a beep,
and records the caller until

- they hang up (a busy or dial tone's start is cut from the message),
- they have been quiet for 7 s (half a second of the quiet is kept), or
- the longest message (2 minutes by default) has gone.

A message under a second isn't kept. Each one is a WAV file (16-bit PCM,
8000 Hz, mono) named for its time and the caller's number when caller ID
gave it, `20261111-093000_5551234.wav`: on Android in the app's own
storage (`files/voicemail`), on iOS in Documents/Voicemail (the Files
app shows it). Nothing leaves the phone.

While a message is being left, **Listen** plays it on the loudspeaker
and **Pick up** turns it into a call (the message isn't kept). The
greeting is recorded with the microphone (a minute at most) or chosen
from a WAV file (PCM, A-law or μ-law, any rate: mixed to one channel and
resampled to 8000 Hz).

Calls answered as faxes by the fax settings, or by dial-in, are answered
on the first ring: with either on, the answering machine waits.

## Where it is

- Android: Home's **Phone** (call a number, the call as it goes, the
  messages), the incoming call's popup (**Answer as a voice call**,
  **Take a message**), Settings › **Phone and answering machine**.
  `voice/Voice.kt` runs the call, `voice/VoiceAudio.kt` its sound (an
  AudioTrack for the speaker; an AudioRecord with the phone's echo
  cancelling, `VOICE_COMMUNICATION`, for the microphone).
- iOS: the **Phone** tab. `JobsModel` runs the call, `VoiceSound.swift`
  its sound (AVAudioEngine in the voice chat mode).

The tests (`shared/src/commonTest/.../voice/VoiceTest.kt`) run a call,
a busy number, a modem without voice mode, messages that end by a
hang-up and by quiet, and one picked up, against a fake V.253 modem.
