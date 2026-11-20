# COMP3011 Assignment 3: Video Player with Speech-to-Text Subtitles

**Kimchhay Leng (a2942784)**: COMP3011 Cloud and Concurrent Programming, Adelaide University, 2026

This assignment extends the multi-threaded video player from Assignment 2 to show real-time subtitles. The speech
comes from the film's own audio. The player sends the audio to the speech-to-text web server from Assignment 1,
which calls OpenAI's Whisper model, lays the text out as subtitles and caches the result.

---

## Contents

- [What's new for the user](#whats-new-for-the-user)
- [Submission layout](#submission-layout)
- [Running it](#running-it)
- [Architecture](#architecture)
- [Video player design](#video-player-design)
- [Web server design](#web-server-design)
- [Concurrency and thread safety](#concurrency-and-thread-safety)
- [Resilience](#resilience)
- [Security and privacy](#security-and-privacy)
- [Testing](#testing)
- [Design decisions and trade-offs](#design-decisions-and-trade-offs)
- [Known limitations](#known-limitations)

---

## What's new for the user

| Feature | How |
|---|---|
| Subtitles on at start | `-S` or `--subtitle-frames` on the command line |
| Toggle during playback | the **CC** button (right of the mute button) or the **S** key |
| Button state | solid CC = on; outlined and crossed out = off. The tooltip shows `[S]` |
| Subtitle style | white bold sans-serif with a black outline and no background box, near the bottom of the frame |
| Layout | at most 42 characters per line and 2 lines (1 preferred); each subtitle shows for 1 to 7 seconds; breaks fall after punctuation where possible |

Subtitles work with every existing effect and stay in sync when pausing, seeking (←, →, Home) or starting over.
Toggling them never interrupts the video or audio.

---

## Submission layout

```
├── Assignment_1-0.0.1-SNAPSHOT.jar   Web server, ready to run (java -jar)
├── pom.xml, player/                     Video player (Maven / JavaFX project)
└── server/                           Web server source (Spring Boot project)
```

The web server is the Assignment 1 project extended with the subtitle and usage endpoints. The video player is the
Assignment 2 project extended with the subtitle feature.

---

## Running it

Requires **Java 25**. Start the web server first, then the player.

### 1. Web server

With an OpenAI API key, either in an environment variable or in a key file such as the course's `key.txt`:

```bash
java -jar Assignment_1-0.0.1-SNAPSHOT.jar
```

| Variable | Purpose |
|---|---|
| `OPENAI_API_KEY` | The API key itself |
| `OPENAI_API_KEY_FILE` | Path to a file holding the key; used when `OPENAI_API_KEY` is not set. The file is re-read on every call, so a replaced key works without a restart. |

PowerShell example:

```powershell
$env:OPENAI_API_KEY_FILE = "E:\key.txt"
java -jar Assignment_1-0.0.1-SNAPSHOT.jar
```

**Offline mode (no key, no cost).** Starting with `--spring.profiles.active=stub` swaps in a stand-in transcriber
that returns fixed sentences. It is used for development and the automated tests.

To rebuild the jar from source: `cd server`, then `./mvnw clean package -DskipTests`. The jar is written to
`server/target/`.

### 2. Video player

```bash
mvn javafx:run "-Djavafx.args=-a -S movie.mp4"
```

`mvn javafx:run -Djavafx.args=--help` lists every option. The player expects the server at
`http://localhost:8080`.

---

## Architecture

```
                      VIDEO PLAYER                                                                     
                                                                                                  
 movie.mp4 ─┬─▶ FrameDecoder ─▶ effects … ─▶ FrameSubtitler ─▶ ImageConverter ─▶ JavaFX                  
            │   (Assignment 2 pipeline threads)       ▲ cue for this frame's time                           
            │                                         │ (lock-free lookup)                            
            │                                   SubtitleTrack                                              
            │                                         ▲ cues                                          
            │                                         │                                                
            └─▶ subtitle-planner ─WAV clip─▶ subtitle-upload ×3 ── HTTP ──────────────────────────▶   WEB SERVER
                (own FFmpeg grabber)                                 ▲───────────────────────── POST /api/v1/subtitles
                                                                                                            |
                                                                                                            ▼
                                                                                                        SHA-256(audio)
                                                                                                            |
                                                                                                      hit ──┴── miss
                                                                                                       │        ▼
                                                                                                       |      Whisper
                                                                                                       |     (whisper-1)
                                                                                                       |         ▼
                                                                                                       |  SubtitleBuilder
                                                                                                       ▼        |
                                                                                          cache ◀─────cues ◀────────┘   
                                                        UsageService
                                              GET /api/v1/global/usage → {"usage": s}
```

The core idea is that each frame picks its subtitle by its own timestamp. The player fetches subtitles for ranges
of film time and stores them in a time-ordered map. The subtitle effect asks "which subtitle covers this frame's
time?" for every frame. Because of that, seeking, pausing and slow frames need no special handling: the frame's
timestamp always identifies the right subtitle.

---

## Video player design

### Getting the audio to the server

| Class | Role |
|---|---|
| `SubtitleFetcher` | Decides which clip is needed next. Runs one planner thread and up to 3 upload threads, and retries failures. |
| `AudioClipExtractor` | Cuts one clip of the film's audio and converts it to 16 kHz, 16-bit mono WAV (FFmpeg does the resampling). |
| `SubtitleClient` | Sends a clip to `POST /api/v1/subtitles` as `multipart/form-data` and parses the JSON reply (Jackson). |

**Clips.**
- The film is cut into fixed **30 second clips**: clip *k* covers film time [30k, 30k + 30) seconds.
- Each clip is sent with **2 seconds of extra audio** at the end, so a sentence that crosses a clip boundary is
  heard in full by the earlier clip.
- Subtitles that start in that overlap are dropped, because the next clip covers them.
- Fixed boundaries mean a seek always lands on clips the server may already have cached.

**What to fetch next.**
- The clip under the playback position comes first, then up to **3 clips ahead** (about 2 minutes of subtitles).
- After a seek, the new position's clip goes to the front automatically.
- Nothing is fetched while subtitles are off.

**Deterministic audio.**
- Each clip is read with a **new grabber of its own**, never the playback pipeline's.
- So the same part of the same film always produces the same bytes, which is what lets the server's cache
  recognise it, whichever player sent it.

### Drawing subtitles

| Class | Role |
|---|---|
| `FrameSubtitler` | The `-S` frame processor. Looks up the subtitle for the frame's timestamp and draws it. |
| `SubtitleRenderer` | Draws the text once per subtitle with Java2D, then alpha-blends that bitmap onto each frame. |
| `SubtitleTrack` | Thread-safe store of all subtitles fetched so far, looked up by film time. |
| `SubtitleManager` | Holds the on/off switch, the track and the fetcher in one place. |

- **A processor like the others.** `FrameSubtitler` is an ordinary frame processor in its own pipeline stage, as
  the specification requires, so it combines with the other effects in command-line order.
- **The button works without `-S`.** If `-S` is not given, one `FrameSubtitler` is added *after* all the other
  effects, so the text is drawn on top of them.
- **Java2D, not JavaFX, for text.** JavaFX can only draw on the JavaFX thread. Java2D can draw on the pipeline's
  own thread, so subtitle drawing never adds work to the UI thread.
- **Readable on any background.** The font size scales with the frame height, and shrinks if a 42-character line
  would not fit in 90% of the frame width. The outline is drawn first, with the white fill on top. There is no
  background box, so as little of the picture as possible is covered.
- **Efficient.** Each subtitle is drawn only once. Every frame then copies just the pixels inside the text.

### Toggling

- **Instant to switch.** The CC button and the `S` key flip an `AtomicBoolean` that `FrameSubtitler` reads.
  Nothing else changes: no threads start or stop and nothing waits, so playback continues untouched.
- **Showing.** Subtitles already fetched appear on the next processed frame.
- **Hiding.** They disappear once the few frames already past the subtitle stage have been shown (about 0.2 s).

---

## Web server design

New endpoints on top of Assignment 1:

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/subtitles` | `multipart/form-data` with an audio clip in the `file` field. Returns subtitle cues timed from the start of the clip. |
| `GET` | `/api/v1/global/usage` | `{"usage": s}`: total seconds of audio billed by the transcription service since the server started. |

Example response:

```json
{
  "cues": [
    { "start": 1.24, "end": 3.9, "lines": ["If you really want to know the truth,", "then sit down and listen."] }
  ],
  "cached": false
}
```

| Class | Role |
|---|---|
| `SubtitleController` | The `/subtitles` endpoint. |
| `SubtitleService` | Hashes the clip, checks the cache, and on a miss transcribes, records usage and builds cues. |
| `OpenAiWhisperService` | Calls `https://api.openai.com/v1/audio/transcriptions` with `model=whisper-1`, `response_format=verbose_json`, and `timestamp_granularities[]` set to both `word` and `segment`. |
| `StubWhisperService` | Offline stand-in under the `stub` profile. |
| `SubtitleBuilder` | Turns Whisper's segments and word timings into subtitle cues. |
| `SubtitleCache` | Content-addressed, single-flight cache of finished cues. |
| `UsageService` | Running total of billed seconds. |
| `ApiKeyProvider` | Supplies the API key from the environment variable or the key file. |

### From transcript to subtitles (`SubtitleBuilder`)

1. **Filter.** Segments with `no_speech_prob > 0.6` and `avg_logprob < -1.0` are dropped.
2. **Align.** Whisper's segment text has punctuation but no word timings; its word list has timings but no
   punctuation. The two are matched up so that every punctuated word gets its own start and end time.
3. **Group words into subtitles.** A subtitle is cut when the next word would not fit in two lines of 42
   characters, or would make it last more than 7 seconds. The cut goes at the best point inside it: a sentence
   end, then a comma, then a pause. New subtitles also start at sentence ends and at pauses of 0.5 s or more.
   A subtitle is ended before it needs a second line when a comma or short pause allows, so one line is used
   where possible.
4. **Timing.** Each subtitle stays up 0.5 s after its last word, is shown for at least 1 s and at most 7 s, and
   never overlaps the next.
5. **Line breaks.** Of the places where both lines fit, the most even split is chosen, with a strong preference
   for breaking after punctuation.

Building subtitles on the server means every client receives the same, already laid-out result, and the cache
stores the finished product.

### Cache (`SubtitleCache`)

- **Key.** The SHA-256 hash of the uploaded audio bytes. The same clip from any player gives the same key. A renamed
  file has the same audio, so it hits the cache too. The server computes the key itself, so a client cannot store
  subtitles under another clip's key.
- **One call per clip.** Each entry is a `CompletableFuture`. The first request for a clip inserts one with
  `putIfAbsent` and makes the Whisper call. Requests for the same clip that arrive meanwhile wait on that future
  instead of making a second, paid call.
- **Failures are not cached.** A failed entry is removed so the next request can try again.
- **Bounded.** At most 5000 clips are kept, oldest removed first. Only text is stored, never audio.
- **Usage.** A cache hit adds nothing to the usage figure, because nothing was billed.

---

## Concurrency and thread safety

The Assignment 2 pipeline is unchanged: one thread per stage, frames handed between threads through blocking
queues, and a generation number to discard stale frames after a seek. The subtitle feature adds these shared
values:

| Shared state | Written by | Read by | Mechanism | Why it is sufficient |
|---|---|---|---|---|
| Subtitles on/off | JavaFX thread | subtitle stage, planner | `AtomicBoolean` | A single value; guarantees visibility across threads |
| Playback position | JavaFX thread | planner | `volatile long` | Visibility only; a slightly stale value is harmless |
| Fetched subtitles (`SubtitleTrack`) | upload threads | subtitle stage | `ConcurrentSkipListMap`, plus `synchronized` writers | Frame lookups never block the pipeline; writers resolve overlapping subtitles atomically |
| Per-clip fetch state | planner, uploads | planner | `ConcurrentHashMap` of immutable records | Each clip's state is replaced as a whole |
| Uploads in flight | planner, uploads | planner | `AtomicInteger` | Counter updated from several threads |
| Last drawn subtitle | subtitle stage only | the same thread | thread confinement | Not shared |
| Server cache | request threads | request threads | `ConcurrentHashMap` + `CompletableFuture` | Atomic `putIfAbsent` gives one Whisper call per clip |
| Billed seconds | request threads | usage endpoint | `LongAdder` of milliseconds | Integer addition is exact and the result does not depend on thread order |

The server handles each request on a virtual thread (from Assignment 1). A thread waiting on Whisper therefore
costs almost nothing, and many players can be served at once.

---

## Resilience

| Situation | Behaviour |
|---|---|
| Server down, or network lost | Playback continues. Subtitles already fetched keep showing. Failed clips are retried with a delay that doubles each time, up to 8 s, so subtitles return within seconds of the network coming back. |
| Slow transcription | The player works up to 3 clips ahead. Frames never wait for subtitles; a frame with no subtitle loaded yet is simply shown without one. |
| Media file lost (USB stick removed) | Subtitle fetching retries until the file can be read again; playback is handled by the Assignment 2 error handling. Subtitles already fetched stay available. |
| Silent audio | Clips with no audio above a very low threshold are not uploaded. This saves money and avoids Whisper inventing words for silence. |
| Bad or missing API key | The server returns an error without passing on the upstream response; the player retries. |

The console reports each outage once and each recovery once, for example
`[subtitles] Cannot get subtitles (…); will keep retrying.` and later `The subtitle server is answering again.`

---

## Security and privacy

- **The API key is protected.** It is never logged, stored elsewhere, or returned to a client. Upstream error
  bodies, which can echo request details, are never forwarded.
- **Audio is only sent when wanted.** No audio leaves the computer while subtitles are switched off.
- **Minimal data is kept.** The server caches subtitle text only, never audio, keyed by a hash of the audio.
- **The cache cannot be poisoned.** The key is computed by the server from the audio itself.

---

## Testing

| Project | Command | Result |
|---|---|---|
| Web server | `cd server`, then `./mvnw test` | 34 tests, all passing |
| Video player | `mvn test` | 31 tests, all passing |

New tests for this assignment:

| Test | What it proves |
|---|---|
| `SubtitleBuilderTest` | Each subtitle guideline: line length, two lines, 1–7 s, sentence and punctuation breaks, non-speech filtering, punctuation alignment. |
| `SubtitleCacheConcurrencyTest` | 100 simultaneous requests for one clip make exactly one transcription; failures are retried; old entries are removed. |
| `OpenAiWhisperServiceTest` | The request uses `whisper-1`, `verbose_json` and both timestamp granularities; the key file is read; the response is parsed. Uses a mocked HTTP layer, so no key or cost. |
| `SubtitleControllerTest` | Over HTTP: cues are returned, a repeat upload is a cache hit, and usage increases only on a miss. |
| `SubtitleTrackTest` | Lookup by time, and resolving overlapping subtitles from neighbouring clips in either arrival order. |
| `SubtitleRendererTest` | White text with a black outline, placed near the bottom, leaving the rest of the picture untouched, and fitting narrow frames. |
| `SubtitleFetcherTest` | End to end against a fake server, with real audio cut by FFmpeg: subtitles land at the right film times; nothing is sent while subtitles are off; the player keeps retrying until the server answers; identical clips give identical bytes; silent clips are skipped. |
| `CommandLineControllerTest` | `-S` and `--subtitle-frames` parse correctly, `-s` still means scratches, and the help text is exact. |

---

## Design decisions and trade-offs

| Decision | Alternative considered | Why |
|---|---|---|
| Subtitles chosen by each frame's timestamp | A separate subtitle clock | Sync after seek and pause comes for free, with no extra coordination code |
| Subtitles drawn in a frame processor | A JavaFX overlay | Meets the "frame processor like the others" requirement and keeps work off the UI thread |
| Layout done on the server | Layout in the player | Every client gets identical subtitles, the cache stores the finished result, and the layout logic is easy to unit test |
| 30 s clips with 2 s overlap | Shorter or longer clips | Shorter clips mean more requests and more sentences split at boundaries; longer clips mean a slower first subtitle. 30 s transcribes in a few seconds, well within the 3-clip look-ahead. |
| Cache keyed by a hash of the audio | Keyed by file name or a client-supplied ID | Works across clients and renamed files, and cannot be poisoned |
| No uploads while subtitles are off | Always fetch in the background | Privacy and cost; subtitles already fetched still reappear instantly |

---

## Known limitations

- **Excerpts of a film don't hit the cache.** If an excerpt starts at a different point in the audio, its 30 s
  clips are cut at different places, so their bytes, and their cache keys, differ from the full film's.
- **Boundary sentences.** A sentence split exactly at a 30 s boundary can occasionally lose a word.
- **Subtitles start after a short delay.** At the very start of a film that has never been transcribed, the first
  subtitles appear only once the first clip has been transcribed, a few seconds in.
- **An audio output device is needed.** The Assignment 2 player requires one; this is unchanged.
