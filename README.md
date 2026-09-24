<p align="center">
  <img src="docs/logo.svg" width="96" alt="TV Assist logo">
</p>

<h1 align="center">TV Assist</h1>

<p align="center">
  <strong>Home Assistant on your TV. Control your home from the couch, and let your home talk back.</strong>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue" alt="License: Apache 2.0"></a>
  <img src="https://img.shields.io/badge/Android%20TV-8.0%2B-3DDC84?logo=androidtv&logoColor=white" alt="Android TV 8.0+">
  <img src="https://img.shields.io/badge/Home%20Assistant-WebSocket%20API-41BDF5?logo=homeassistant&logoColor=white" alt="Home Assistant">
</p>

<p align="center">
  <a href="https://tvassist.org/">Website</a> ·
  <a href="#quick-start">Quick start</a> ·
  <a href="#cameras">Cameras</a> ·
  <a href="#notifications">Notifications API</a> ·
  <a href="#troubleshooting">Troubleshooting</a>
</p>

---

TV Assist is an Android TV app with two jobs. It opens a **control sidebar** over whatever you
are watching, so you can dim the lights, check the doorbell or lock the door with the remote. And
it gives Home Assistant a way to reach the screen: **notifications, live camera video and spoken
announcements**, pushed from your automations.

It brings together what [QuickBars](https://github.com/Trooped/QuickBars) does for control and
[TvOverlay](https://github.com/gugutab/TvOverlay) does for on-screen information, in one app that
works entirely with the TV remote.

> [!CAUTION]
> **Prefer a direct RTSP URL for cameras, in both camera cards and notifications.** When the TV
> plays a Home Assistant camera, Home Assistant has to pull the camera's stream and repackage it
> as HLS for the TV, for as long as it plays. On a slow Home Assistant host, such as a Raspberry
> Pi, that load can make Home Assistant itself sluggish, and the video starts late. A direct URL
> goes straight from the camera to the TV and costs Home Assistant nothing:
> - **Camera cards:** add the camera under *Settings → Cameras* with its `rtsp://…` address.
> - **Notifications:** send `media_url` with the `rtsp://…` address, rather than `camera_stream`.

## Highlights

- **A sidebar over any app.** One button opens it over Netflix, live TV or the launcher. Arrange
  it as rows of tiles, with a live preview while you edit.
- **Real controls, not just toggles.** Brightness and colour, thermostat modes, cover position,
  alarm keypads, media transport, firmware updates. Each control appears only when the device
  supports it.
- **Cameras that start instantly.** Point it straight at an RTSP or HLS stream, or use your Home
  Assistant cameras. It automatically falls back to a smaller stream when another app is using
  the TV's video decoder.
- **Notifications from your automations.** Doorbell snapshots, live camera clips, sounds and
  text-to-speech, sent over a small HTTP API on the TV.
- **Voice with Assist.** Press the remote's mic key and talk to your Home Assistant voice
  pipeline.
- **Always-on extras.** A clock, screen dimming, live sensor pills and maps of where people are.
- **Light on your system.** One WebSocket connection, and only the entities you choose are
  tracked, so large installations stay fast.

## Quick start

1. **Install** the APK on your TV (see [Install](#install)).
2. **Connect to Home Assistant.** You need its URL and a long-lived access token, created in your
   HA profile under **Security**. Type them with the remote, or choose **Connect from phone** and
   paste them into a browser instead.
3. **Allow the two permissions** when asked: *Display over other apps* (for the sidebar and
   notifications) and *Key capture* (so a remote button can open the sidebar).
4. **Import your entities** from *Home → Import entities*.
5. **Lay out the sidebar** under *Overlay*, and pick its button under
   *Settings → Triggers & keys*.

That's it: press the button over any app.

## Install

**Requirements:** an Android TV or Google TV device on Android 8.0 or newer, and a Home Assistant
the TV can reach. Tested on Sony BRAVIA TVs running Android 10 and 12.

Each release ships three APKs:

| APK | Choose it for |
|---|---|
| `app-armeabi-v7a-release.apk` | **Most TVs.** Almost every Android TV runs 32-bit apps. |
| `app-arm64-v8a-release.apk` | 64-bit streaming boxes. |
| `app-universal-release.apk` | When you're not sure. Works everywhere, about 40 MB larger. |

Install with a file manager on the TV, or from a computer with
[adb](https://developer.android.com/tools/adb):

```bash
adb connect <tv-ip>:5555
adb install -r app-armeabi-v7a-release.apk
```

Updates install over the top and keep all your settings.

> [!TIP]
> **Connect from phone** saves typing a token of about 180 characters with a remote. The TV shows an
> address like `https://<tv-ip>:8484` and a PIN; open it in any browser on the same network. The
> TV uses a self-signed certificate, so the browser warns once. The page shuts itself down once
> the TV is connected.

## How it works

```mermaid
flowchart LR
    Cam["IP camera"]
    HA["Home Assistant"]
    subgraph TV["Android TV"]
        App["TV Assist<br/>sidebar · cards · voice"]
        Overlay["Overlay<br/>notifications · clock · pills"]
    end

    HA <-- "WebSocket: states and service calls" --> App
    HA -- "HTTP: /notify, /speak, /play" --> Overlay
    Cam == "Direct RTSP (preferred)" ==> App
    Cam == "Direct RTSP (preferred)" ==> Overlay
    Cam -. "RTSP" .-> HA
    HA -. "HLS, repackaged by Home Assistant" .-> App
```

TV Assist keeps one WebSocket open to Home Assistant for live states and commands, and runs a
small HTTP server so your automations can push to the screen. Camera video takes one of two
routes: **direct** from the camera (solid arrows: *Settings → Cameras* and a notification's
`media_url`), or **through Home Assistant** (dotted arrows: Home Assistant cameras and a
notification's `camera_stream`). Prefer direct; the caution at the top says why.

## Using TV Assist

### The sidebar

Press your trigger button over any app to open the sidebar. **Back** closes it, and it closes
itself after a few idle seconds (adjustable).

- **Layout:** rows of tiles, 1 to 12 columns per row, plus optional header rows showing live
  sensor values.
- **Tile styles:** *Icon tap*, *Normal*, *Compact* (the most useful control inline) and *Full*
  (every control inline). The editor only offers the styles each entity can use.
- **Per entity** (*Home → Customize*): a custom name, any icon from the Home Assistant set, and
  what a press, long press and double press do.

### Control cards

Hold **OK** on a tile, or on the Home list, to open its full control card.

| Entity | What you can do |
|---|---|
| Lights | On/off, brightness, warmth, colour, saturation |
| Thermostats | Target temperature, HVAC mode, fan mode, presets, swing |
| Fans | Speed steps or percentage, presets |
| Covers | Open, close, stop, position, tilt |
| Locks | Lock, unlock, open the latch |
| Alarm panels | Arm in every supported mode, disarm, on-screen keypad |
| Media players | Play/pause, skip, seek, volume and mute, source, sound mode, shuffle, repeat |
| Updates | See installed and latest versions and the release summary; install with progress |
| Air quality | Every pollutant reading, with units |
| Switches, buttons, scenes, scripts | Toggle or run |

### Cameras

Home Assistant cameras work as they are. For the direct route, add a camera under
**Settings → Cameras**, or from the phone setup page:

| Field | Purpose |
|---|---|
| Stream URL | The main stream, e.g. `rtsp://user:pass@192.168.1.20:554/stream1` |
| **Low-res stream URL** | Optional. The camera's smaller stream, often the same address ending in `/stream2`. |
| Snapshot URL | Optional. A still image for the tile, and for the popup when live video isn't possible. |
| Player | Auto (ExoPlayer, or VLC for RTSP), ExoPlayer, or VLC. |
| Keep refreshing | For cameras that serve a short looped video rather than a stream. The clip is fetched every 30 s and looped on the TV. |

> [!IMPORTANT]
> **Set a Low-res stream URL for any 4K camera.** Many TVs can decode only one high-resolution
> video in hardware at a time, and a video app keeps that decoder for as long as it has a video
> open, **even while paused**.

Streaming apps that use copy protection, such as **Netflix**, **Prime Video**, **Disney+** and
**Apple TV**, lock every other app out of the decoder entirely. (This was measured on a Sony
BRAVIA with Netflix.) **YouTube** usually plays unprotected video and may leave room, but its
paid movies and some other content are protected too.

So before a camera plays, TV Assist checks what the TV can handle:

| When you open a camera | You see |
|---|---|
| The decoder is free | The main stream, at full quality |
| Another app holds it, and the camera has a low-res stream | The low-res stream, live, while the other app keeps playing |
| Another app holds it, and there's no low-res stream | The camera's snapshot, refreshed every few seconds, with a note explaining why |

A stream larger than 1080p is never decoded in software. On a 2 GB TV, that froze the picture
and then the whole TV, so if software would be the only option, playback stops and says why.

### Notifications

TV Assist runs a small HTTP server on the TV, on port **8455** by default. Turn it on under
*Settings → Notifications*. If you set a token there, every request must include it as
`?token=…` or an `X-Token` header.

A Home Assistant `rest_command` to get started:

```yaml
rest_command:
  tv_notify:
    url: "http://<tv-ip>:8455/notify?token=<your-token>"
    method: POST
    content_type: "application/json"
    payload: >
      {"title": "{{ title }}", "message": "{{ message }}",
       "media_url": "rtsp://user:pass@192.168.1.20:554/stream2",
       "camera": "camera.front_door", "duration": 15}
```

`media_url` plays the camera directly, and `camera` supplies the snapshot shown until the video
starts. The example uses the camera's low-res stream (`/stream2`), which suits a small card and
plays even when another app holds the TV's decoder; see [Cameras](#cameras).

<details>
<summary><strong>Endpoints</strong></summary>

| Endpoint | Does |
|---|---|
| `POST /notify` | Shows a notification |
| `POST /notify/clear` | Removes notifications |
| `POST /notify_fixed` | Pins a persistent pill |
| `POST /notify_fixed/clear` | Clears pinned pills |
| `POST /speak` | Speaks a message aloud |
| `POST /play` | Plays a sound from a URL |
| `POST /set/overlay` | Changes overlay settings |

</details>

<details>
<summary><strong>Main <code>/notify</code> fields</strong></summary>

| Field | Meaning |
|---|---|
| `title`, `message` | The text |
| `image` | A picture URL |
| `camera` | An HA camera entity, shown as a snapshot |
| `camera_stream` | An HA camera entity, played live through Home Assistant (prefer `media_url`) |
| `media_url`, `media_type` | A stream or video URL, played directly; for a video file set `media_type: video` |
| `duration` | Seconds on screen |
| `position` | `top-right` (default), `top-left`, `top-center`, `bottom-left`, `bottom-center`, `bottom-right` |
| `size` | `small`, `medium` (default) or `large` |
| `id` | Reuse to replace a notification in place |
| `interactive` | `true` lets the viewer press **OK** to open it full size, useful for a doorbell |
| `color`, `icon`, `background_color` | Styling |

</details>

### Voice (Assist)

Bind the remote's mic key under *Settings → Triggers & keys*, then press it to talk to Home
Assistant's **Assist**. The voice bar shows what it heard and the reply, and can read the reply
aloud. Choose the Assist pipeline on the same page, or type a question instead of speaking.

### Backup and restore

*Settings → Backup & restore* saves every setting (connection, entities, sidebar layout, cameras,
colours, notification and map settings, trigger key) to a file named like
`tv-assist-<TV model>-<date>.json`, and lists saved backups for restoring or deleting.

- **App folder** is easy to pull with `adb`, but is **deleted if the app is uninstalled**.
- **Download** or **USB** survive an uninstall.

**Include secrets** is off by default. Turn it on to add the HA token, the Google Maps key and
the notification token, encrypted with a passphrase you choose.

<details>
<summary><strong>All settings pages</strong></summary>

| Page | What's there |
|---|---|
| Connection | Home Assistant URL and token, phone setup |
| Permissions | Display over other apps, key capture, microphone |
| Security | Tokens and keys, HA certificate checking |
| Triggers & keys | The sidebar button, the Assist mic key, the Assist pipeline |
| Appearance & timing | Theme, position, style, auto-close |
| Notifications | The notification server, its port and token |
| Audio & announcements | Speech and sound volume, ducking, language |
| On-screen display | Always-on clock, screen dimming |
| Cameras | Direct cameras and their low-res streams |
| Maps | Map cards and the Google Maps source |
| Backup & restore | Save and restore settings |
| About | Version and update check |

</details>

## Troubleshooting

| Problem | What to do |
|---|---|
| The trigger button does nothing | Check *Settings → Permissions → Key capture*. Android turns accessibility services off when an app is force-stopped. |
| Connected, but pictures and cameras don't load | Include `http://` or `https://` in the Home Assistant URL. |
| A camera shows only a still, or is choppy, while another video app is open | Add a low-res stream for it; see [Cameras](#cameras). |
| The sidebar feels sluggish | Look at what's behind it. Animated launchers cost far more than video does. |

## Known issues

> [!NOTE]
> - **A camera with neither a low-res stream nor a Snapshot URL shows a black popup** (with the
>   explanatory note) while another app holds the video decoder, because there's no picture to
>   show.
> - **Closing an enlarged video notification just as it expires** restarts the small card's video
>   for a fraction of a second before the card disappears. It's harmless, but it briefly opens a
>   decoder for nothing and logs a misleading "stream never started" line.

## Security and privacy

- **Your Home Assistant token only goes to your Home Assistant**: the exact scheme, host and port
  you connected to. It is never sent to camera, image or map servers.
- **Secrets are encrypted on the TV** with the Android Keystore (AES-256-GCM), and are excluded
  from Android's own backups.
- **Certificate checking** can be relaxed only for a Home Assistant on your private network, and
  never for anything else.
- **Logs never contain credentials.** Camera URLs are logged with `user:password@` removed, and
  the video player's own logging is switched off in release builds.
- **The phone setup page** is protected by HTTPS and a PIN shown on the TV.

## Development

You need Android Studio (for its bundled JDK) and an Android TV emulator or device.

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"   # Windows; Studio's bundled JDK

./gradlew :app:assembleDebug        # build
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:lintDebug            # lint; CI fails on errors

# The build makes one APK per ABI; TVs and the TV emulator use armeabi-v7a.
adb install -r app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
adb shell am start -n com.tvassist/.ui.MainActivity
```

From an emulator, the computer running it is `10.0.2.2`, so a Home Assistant on your computer is
`http://10.0.2.2:8123`, not `localhost`.

<details>
<summary><strong>Testing without a real Home Assistant</strong></summary>

`tools/mock_ha_server.js` is a dependency-free Node mock of just enough of the Home Assistant
WebSocket API (auth, `get_states`, `subscribe_events`, `call_service`), with three demo entities.

```bash
node tools/mock_ha_server.js          # listens on 0.0.0.0:8123; token: VALID_TEST_TOKEN
```

Debug builds also accept intent extras, so you can drive the app without the UI:

```bash
# Connect headlessly
adb shell am start -n com.tvassist/.ui.MainActivity \
  --es ha_url "http://10.0.2.2:8123" --es ha_token "VALID_TEST_TOKEN"

# Add one of these to also open the sidebar, or toggle the first entity
  --es action open_sidebar
  --es action toggle_first
```

</details>

## Acknowledgements

Video playback uses [libVLC](https://www.videolan.org/vlc/libvlc.html) and
[Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer); maps use
[OpenStreetMap](https://www.openstreetmap.org/) data. Thanks to QuickBars and TvOverlay, credited
above, for the ideas this app grew from.

## License

[Apache License 2.0](LICENSE)
