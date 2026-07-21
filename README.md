# TV Assist

An Android TV app that combines the two halves of a smart‑home TV experience:

- **Home Assistant control** (like *QuickBars* - https://github.com/Trooped/QuickBars) — an overlay **sidebar** to control HA
  entities from the couch, **remote key‑mapping** to summon it, and a live **WebSocket**
  connection for real‑time state.
- **Information overlays** (like *TvOverlay* - https://github.com/gugutab/TvOverlay) — clock, on‑screen
  notifications, and a REST receiver.

The current build delivers the **MVP**: connect to Home Assistant, open a control
sidebar over any app, and toggle entities — navigable entirely by the TV remote.

## Status — what works today

| Feature | State |
|---|---|
| HA WebSocket connect + **token auth** (`auth_required → auth → auth_ok`) | ✅ verified |
| Load entities (`get_states`) + live updates (`subscribe_events`) | ✅ verified |
| Connection status UI + setup screen (URL + long‑lived token) | ✅ verified |
| Overlay **sidebar** drawn over other apps (`TYPE_APPLICATION_OVERLAY`) | ✅ verified |
| D‑pad navigation inside the sidebar + toggle via `call_service` | ✅ verified |
| Onboarding: enter credentials **on TV** or **from a phone** (web server on 8484) | ✅ verified |
| **Entity management**: search + Favorites/Controllable filters, favorites sorted to top | ✅ verified |
| **Manage sidebar** (QuickBars-style): multi-select picker w/ search, reorder, remove | ✅ verified |
| **Overlay**: reliable BACK dismiss + configurable auto-close (5/10/15/30/60s) | ✅ verified on real TV |
| Throttled entity updates (no GC churn with 1000s of entities) | ✅ verified on real TV |
| **Settings**: trigger‑key capture page + **backup/restore** of settings to JSON | ✅ verified |
| Remote **key‑capture** accessibility service (single/double/long press) | ✅ code complete · |


## Build & run

Requires Android Studio (with its bundled **JDK 21**) and an Android **TV emulator** or
device. From the repo root:

```bash
# Build (uses the wrapper; point JAVA_HOME at Studio's JBR on Windows)
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug

# Install & launch
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.tvassist/.ui.MainActivity
```

Or just open the project in Android Studio and Run on a TV AVD.

### Connecting to Home Assistant
1. In HA, create a **long‑lived access token** (Profile → Security).
2. On the app's first screen, choose how to enter credentials:
   - **Enter on this TV** — type the HA URL + token with the remote, then **Connect**.
   - **Connect from phone** — the TV runs a temporary web server on **port 8484** and
     shows an address like `http://<tv-ip>:8484`. Open it in a phone/laptop browser,
     enter the URL + token, and submit. The server **shuts off automatically** once the
     TV connects, and can be re‑opened later via **Edit connection → Connect from phone**
     to update credentials.
3. Grant **overlay permission** and enable the **key‑capture** accessibility service
   using the on‑screen shortcut buttons (these open the right system settings screens).

> `usesCleartextTraffic` is enabled so `http://` HA instances work on the local network.

### Managing entities
With a large HA instance the entity list can be thousands of rows. The main screen has:
- **Search** by friendly name or `entity_id`.
- **★ Favorites** filter — show only entities starred for the overlay sidebar.
- **Controllable** filter — hide read‑only sensors, leaving lights/switches/etc.
- Favorites are always sorted to the top. The **★** on each row adds/removes it from the
  overlay sidebar.

### Settings (trigger key + backup)
**Settings** (button on the main screen) has:
- **Overlay trigger key** — press *Set trigger key*, then press the remote button you want
  to open the overlay. (System‑reserved keys like HOME/MENU won't reach the app.)
- **Backup & restore** — writes all settings (connection, favorites, trigger key) to
  `tv-assist-backup.json` in the app's external files dir
  (`/sdcard/Android/data/com.tvassist/files/`, pullable via `adb pull`). *Restore* reads it
  back (tolerates a UTF‑8 BOM if you hand‑edit the file).

## Testing without a real HA

`tools/mock_ha_server.js` is a dependency‑free Node mock that speaks just enough of the
HA WebSocket API (auth, `get_states`, `subscribe_events`, `call_service`) with three demo
entities.

```bash
node tools/mock_ha_server.js          # listens on 0.0.0.0:8123
```

From the emulator the host is reachable at `http://10.0.2.2:8123`; the token is
`VALID_TEST_TOKEN`.

**Debug‑only intent hooks** (guarded by `BuildConfig.DEBUG`) make UI‑less testing easy:

```bash
# connect headlessly
adb shell am start -n com.tvassist/.ui.MainActivity \
  --es ha_url "http://10.0.2.2:8123" --es ha_token "VALID_TEST_TOKEN"
# also pop the sidebar / toggle first entity
  ... --es action open_sidebar
  ... --es action toggle_first
```
