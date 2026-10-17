# Remote Screens

Remote Screens turns Android tablets into Home Assistant wall screens. You lay out tiles in the editor (the
**Remote Screens** panel in the sidebar); the add-on draws each screen as a Remote Compose document and sends it
to the tablets, which show it with the Remote Screens app. Taps come back to the add-on, which calls Home
Assistant. The tablets never talk to Home Assistant themselves, and need no token.

## Getting started

1. Start the add-on and open **Remote Screens** in the sidebar.
2. Make a screen with **New screen**. Pick the size of your tablet, or make it after the tablet has shown up and
   pick that.
3. Install the Remote Screens app on the tablet: **Download the player app** in the editor, or the APK from the
   GitHub release. Android asks you to allow installing apps from your browser.
4. Open the app. It finds the add-on on your network and shows a six-digit code.
5. In the editor, the tablet appears under **Tablets** with its code. Check that the codes match, choose the screen
   and press **Pair**. The tablet shows the screen at once.
6. Open the screen in the editor: add pages, drag entities from the library onto the grid, and press
   **Save & send**. **Preview on device** shows your changes on a tablet without saving them.

If the app does not find the add-on (mDNS blocked between VLANs, for example), tap **Enter address** and give the
address of your Home Assistant host with port 8100.

### A tablet on the wall

- Make Remote Screens the tablet's **home app** (Android settings, Apps, Default apps, Home app). It then opens when
  the tablet starts and after every update, and the Home button cannot leave it.
- If you would rather keep your launcher, open the app's settings (long press the top bar) and choose **Open by
  itself after a restart or update**. Android calls this permission "Display over other apps".
- Keep the tablet plugged in. The screen dims on its own after the standby time and at night.
- To turn the screen fully off in standby instead of dimming it, tick **Turn the screen off in standby** for the
  screen, then on the tablet open the app's settings and choose **Allow turning the screen off in standby** (Android
  asks to activate Remote Screens as a device admin). A screen that is off does not wake on a touch: press the power
  button, or call `remote_screens.wake` from Home Assistant.

## Waking from standby

Besides a touch (when the panel is only dimmed) or the power button, a screen can wake from:

1. **Home Assistant** — call `remote_screens.wake` (or fire `remote_screens_wake`). This is the reliable way to wake
   when someone enters the room: use a PIR, mmWave, ultrasonic, or occupancy `binary_sensor` in an automation.
2. **Proximity** (optional) — tick **Wake on proximity** for the screen. Uses the tablet's proximity sensor when it
   has one. On many tablets that is only a few centimetres, so it is not a substitute for a room sensor. Ignored if
   there is no sensor. Can keep **Turn the screen off in standby**.
3. **Face** (optional) — tick **Wake on face**. Uses the front camera and on-device face detection while the screen is
   asleep. The tablet asks for camera permission; Android shows its privacy indicator while the camera runs. Needs a
   front camera and is skipped on low-RAM (Android Go) tablets. When face wake is active, standby keeps a black screen
   instead of turning the panel fully off (so the camera can keep running). Proximity and face can both be on at once;
   each wakes the screen on its own.

Example: wake screens when a hallway motion sensor trips.

```yaml
automation:
  - alias: Wake screens on motion
    triggers:
      - trigger: state
        entity_id: binary_sensor.hallway_motion
        to: "on"
    actions:
      - action: remote_screens.wake
      # Or pick devices under screens, or fire event: remote_screens_wake
```

## Kiosk lock

With **Kiosk lock** on, a screen's tablet stays on Remote Screens: Home, Recents, the notifications and the power
menu cannot leave it. Turn it on with the screen's **Kiosk lock** switch in Home Assistant, or **Lock the tablet to
this screen** in the editor. The lock is strongest on a tablet enrolled as a kiosk (Android calls this the *device
owner*); any other tablet gets Android's screen pinning instead.

### Enroll with a QR code

No computer is needed. The tablet must be factory reset (or new), with no Google account added.

1. In the editor, under **Tablets**, choose **Enroll a tablet**. Optionally give the Wi-Fi the tablet should join,
   then **Show QR code**. The Wi-Fi password only goes into the code; the add-on does not keep it.
2. On the tablet's first Welcome screen, tap the same spot six times. A QR scanner opens (some tablets ask for Wi-Fi
   first).
3. Scan the code. The tablet joins Wi-Fi, downloads the player app from the add-on and sets itself up as a kiosk.
4. Pair it with a screen as usual.

The code works for an hour, for as many tablets as you like. An enrolled tablet starts locked and stays locked until
it is paired; from then on it follows its screen's **Kiosk lock**, so turn that on (before pairing, to keep it
locked throughout).

### Enroll with adb

For tablets without the QR scanner (some AOSP and Fire tablets) or ones already set up, connect the tablet with USB
debugging on and run, from this repository:

```sh
./scripts/enroll-device-owner.sh --fresh remote-screens.apk
```

`--fresh` reinstalls the app, which clears its data, so pair the tablet again afterwards. Android only accepts this
on a tablet with no accounts (Settings, Accounts), so remove them or factory reset first. Run the script with
`--help` for the other options.

### Without enrolling

A tablet that is not enrolled pins the app instead. Android asks to confirm the first time. Pinning keeps the
navigation bar, and holding Back and Recents unpins the app; turn on Android's **Ask for PIN before unpinning**
(Settings, Security, App pinning) so only you can. The app pins itself again when it comes back to the front.

### Unlocking on the tablet

Give the screen a **Kiosk PIN** (4 to 8 digits) in Home Assistant or the editor. Long pressing the top bar then
asks for the PIN, and the settings offer **Unlock tablet**; **Lock again** undoes it. The tablet stays unlocked,
even after a restart, until it is locked again there or the screen's Kiosk lock is turned off and on. Without a PIN,
the tablet can only be unlocked from Home Assistant or the editor.

### Stop managing a tablet

To give an enrolled tablet back its normal use, unlock it (turn Kiosk lock off, or use the PIN), open the settings
and choose **Stop managing this tablet**. The app can then be uninstalled like any other. A factory reset does the
same.

## Tiles

Every tile follows its entity live. A tap does the obvious thing for its type (toggle a light, open a cover's
card); a long press opens the card with everything else (brightness and colour, modes, the media library,
history). The editor lets you change the tap and the long press to: toggle, open the card, run a service, go to a
page, open a URL on the tablet, fire a Home Assistant event, or nothing.

Tiles that are not an entity: clock, bedside clock, page navigation, history graph, energy flow, map, and a
favourite (a playlist on a player). Entities whose domain has no dedicated tile still appear in the library as a
generic on/off tile (`homeassistant.toggle`).

The **map card** (person, device tracker, or the map tile's detail) is an interactive OpenStreetMap on current
players: pinch to zoom and drag to pan. Older players still get a still image drawn by the add-on. Grid map tiles
stay as still images.

Besides the grid, a page can be **free-form**: a tree of layout and control components (columns, dials, tabs,
markdown, QR codes, and the rest in the component palette). The demo screen includes a Compose page that shows
several of them.

A page can be hidden: it has no page dot and is only reached from a navigation tile, with a Back key.

## Themes

A screen wears a theme: RPI dark (the default), Light, OLED black, High contrast, or one of your own. In the
screen's settings, pick its **Theme** and its **Dark theme**; the dark theme is worn in dark mode and, with
**Dark at night**, during the night hours. The editor's Day / Dark switch previews both.

To make your own, open **Themes** on the editor's home page, duplicate any theme and change its colours. Each
colour you leave alone follows the theme it was made from. Saving sends the change to every tablet wearing it;
deleting it puts those screens back on RPI dark.

## Languages

The screens follow Home Assistant's language, or the one set in the editor. The words for states ("Open", "Heat",
"Armed away") come from Home Assistant itself, so they match its own dashboards in every language. The add-on's
own texts are in English, Dutch, German, French, Spanish, Italian, Polish and Portuguese, and fall back to
English in other languages.

## Settings from Home Assistant

The **Remote Screens** integration (installed with HACS) adds, for each screen, entities for brightness, dark
mode, standby, night mode, swipe, back-to-home and the page shown, and the services
`remote_screens.show_alert`, `remote_screens.open_page`, `remote_screens.wake` and `remote_screens.close`. The
add-on announces itself, so Home Assistant offers to set the integration up.

Alerts can also come from an event, without the integration. In a script or automation:

```yaml
- event: remote_screens_alert
  event_data:
    title: Someone is at the door
    level: warning
    camera: camera.front_door
    timeout: 60
    buttons:
      - title: Open
        service: lock.open
        data: {entity_id: lock.front_door}
      - title: Ignore
```

A button fires `remote_screens_alert_action` with its `action` (its title when it has none), so an automation can
answer it. The events `remote_screens_open_page` (with `page`), `remote_screens_wake` and `remote_screens_close`
work the same way.

## Updating the tablets

When the add-on has a newer player app than a tablet, the tablet offers to install it. Android asks once for
permission to install apps from Remote Screens, then asks you to confirm each update.

The player app's ID changed from `dev.remotescreens.player` to `tech.shaffersoftworks.remotescreens.player`, so a
tablet running the old app cannot update to it. Uninstall the old app, install the new one and pair the tablet again.
A tablet enrolled as a kiosk has to be factory reset and enrolled again.

## Network

| Port | What |
| ---- | ---- |
| 8100 | Tablets connect here (WebSocket), and the integration's API |
| (free port) | The editor, through ingress only; the Supervisor picks a free port so it never clashes |

The add-on uses the host network so tablets can find it over mDNS (`_remotescreens._tcp`).

## Data

Layouts and paired tablets are kept in `/data/layouts.json` and included in Home Assistant backups. A tablet's
token is stored as a hash only. To remove a tablet, use **Forget** in the editor; it must then be paired again.

## Options

| Option | What |
| ------ | ---- |
| `map_tiles` | Where map tiles come from, as a URL with `{z}`, `{x}` and `{y}`. Empty is OpenStreetMap, whose tile servers ask for light use: the add-on caches tiles for still map images on the grid (and on older players). Interactive map cards on current players fetch tiles on the tablet using the same URL. With many tablets, or for another map style, use a tile provider you have a key for. |
