# Activity Webhook

A [RuneLite](https://runelite.net) plugin that sends your own in-game activity as small JSON events (format **v1**) to an HTTP endpoint **you** choose. Events cover login, logout, heartbeat, level-ups, and activity changes (skilling, combat, banking, idle).

One example receiver is the **Pixel Pet Pals** relay (`node relay/server.js` in its companion repo), which can drive a pixel-pet stream widget.

Package: `com.activitywebhook` · Config group: `activitywebhook`

<!-- TODO: add icon.png (max 48x72 px at repo root) and README screenshots for Plugin Hub -->

## Privacy and data sharing

> **Third-party server warning.** When enabled, this plugin sends your activity to a server you choose. That server is **not controlled or verified by Jagex or RuneLite**. Only enable it with an endpoint you trust.

- **Opt-in:** sending is off by default. Nothing is sent until you turn on **Send activity**, set a valid endpoint URL, and provide a player name (typed display name, or opt in to the in-game name).
- **Use my in-game name** is **off by default**. When off, the `player` field is a name you type. When on, your RuneScape name is read from the client and sent to your chosen server.
- **HTTPS is required** for non-loopback endpoints. Plain `http://` is allowed only to `localhost`, `127.0.0.1`, or `[::1]` (for a local relay).
- The plugin **never** sends credentials, login/email, chat, inventory, bank contents, other players, location/region/world, XP amounts, coordinates, or client/system info.
- Optional shared secret is sent only as the `X-Pet-Secret` request header (not in the JSON body).

**Not affiliated with Jagex.** *RuneScape* and *Jagex* are trademarks of Jagex Ltd.

## Configuration

Configuration → **Activity Webhook** → section **Data sharing (third party)**:

| Option | Config key | Default | Meaning |
|---|---|---|---|
| **Send activity** | `enabled` | **off** | Master switch. Off = nothing is sent. |
| **Endpoint URL** | `endpointUrl` | empty | Must be `https://`, except plain `http://` to loopback (`localhost`, `127.0.0.1`, `[::1]`), e.g. `http://127.0.0.1:8787/events`. Empty or invalid = nothing is sent. |
| **Shared secret** | `sharedSecret` | empty | Optional. Sent as the `X-Pet-Secret` header (e.g. match the Pixel Pet Pals relay's `PET_SECRET`). |
| **Display name** | `displayName` | empty | Value for the `player` field when **Use my in-game name** is off. You type it; it is not read from the game. Empty (with that option off) = nothing is sent. |
| **Use my in-game name** | `useInGameName` | **off** | When on, `player` is your RuneScape name from the game (typed display name is ignored). If the name is not available yet (e.g. right after login or a world hop), nothing is sent until it can be read. |
| **Include NPC name in combat** | `includeNpcName` | **off** | When on, combat events include the name of the NPC you are fighting. Other players are never sent. |
| **Minimum interval** | `minIntervalSeconds` | **5** (range 5–60) | Never send more often than this (values below 5 s are treated as 5 s). A heartbeat is sent at most every 60 s of silence. |

## Event JSON format (v1)

One HTTP `POST` per event to your **Endpoint URL**, with `Content-Type: application/json; charset=utf-8` and, if set, header `X-Pet-Secret: <sharedSecret>`.

| Field | Value |
|---|---|
| `v` | Always `1` |
| `player` | Typed display name, or RuneScape name if **Use my in-game name** is on (trimmed, max 64 chars) |
| `ts` | When the event was created (queued events such as login, logout and level-up keep their original time), ISO 8601 UTC to the second, e.g. `2026-10-13T16:50:14Z` |
| `type` | One of `login`, `logout`, `activity`, `idle`, `level_up`, `heartbeat` |
| `activity.category` | `skilling`, `combat`, `banking`, or `idle` (on `activity`, `idle`, and `heartbeat` events) |
| `activity.skill` | `Woodcutting`, `Mining`, or `Fishing` when `category` is `skilling` |
| `activity.target` | NPC name, only for `combat` and only if **Include NPC name in combat** is on |
| `detail.skill`, `detail.level` | Only on `level_up`: skill name and new real level |

`activity.region` is never set. Null fields are omitted from the JSON.

### Example

```http
POST /events HTTP/1.1
Host: 127.0.0.1:8787
Content-Type: application/json; charset=utf-8
X-Pet-Secret: your-optional-secret
```

```json
{
  "v": 1,
  "player": "Matthew",
  "ts": "2026-10-13T16:50:14Z",
  "type": "activity",
  "activity": {
    "category": "skilling",
    "skill": "Woodcutting"
  }
}
```

Other examples: `{"v":1,"player":"Matthew","ts":"…","type":"login"}`, `{"v":1,"player":"Matthew","ts":"…","type":"level_up","detail":{"skill":"Mining","level":61}}`, heartbeat with current `activity`, and combat with optional `"target":"Hill Giant"`.

### When events are sent

| Event | When |
|---|---|
| `login` | You reach the logged-in state (with in-game name on, on the first tick the name is available) |
| `logout` | You return to the login screen (sent immediately, still subject to backoff) |
| `activity` / `idle` | Your derived activity **changes**, at most once per minimum interval |
| `level_up` | One of your real skill levels goes up (queued, at most once per minimum interval) |
| `heartbeat` | Current activity, only after **60 s** with nothing else sent |

How activity is derived (from your own client state only):

- **banking** — bank interface is open
- **combat** — you are interacting with an NPC (player targets are ignored)
- **skilling** — Woodcutting, Mining, or Fishing XP in the last ~6 s while animating
- **idle** — none of the above (and no animation) for ~30 s; otherwise the last activity is kept

Failed network/non-2xx responses back off exponentially (5 s … up to 5 min). Failed `level_up` / `login` / `logout` are dropped, not retried. See the source for full queue/reset behaviour.

## Example receiver: Pixel Pet Pals relay

This plugin is a general exporter. One ready-made receiver is the Pixel Pet Pals relay + widget:

```bash
# in the companion Pixel Pet Pals / osrs-companion checkout
PET_SECRET=choose-a-secret node relay/server.js
# relay: http://127.0.0.1:8787  → POST /events, widget at /widget/
```

Point the plugin at `http://127.0.0.1:8787/events`, set the same shared secret if used, choose a display name (or enable **Use my in-game name**), then turn on **Send activity**.

For a full local RuneLite developer-client walkthrough, see [RUNNING.md](RUNNING.md).

## Building from source

Requires **Java 11** and the Gradle wrapper in this repo:

```bash
./gradlew test build
# or, with a specific JDK 11 install:
# JAVA_HOME=~/jdk/jdk-11.0.32.1+1 ./gradlew test build
```

Developer client (loads the plugin as a built-in):

```bash
./gradlew run
```

Optional end-to-end check against a local Pixel Pet Pals relay:

```bash
scripts/e2e.sh ../osrs-companion
```

## License

[BSD 2-Clause](LICENSE) (required for RuneLite Plugin Hub).

## Disclaimer

**Not affiliated with Jagex.** This is a third-party RuneLite plugin. *RuneScape®* and *Jagex®* are trademarks of Jagex Ltd. Use of those names does not imply endorsement.
