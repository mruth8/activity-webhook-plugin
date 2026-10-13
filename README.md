# Pixel Pet Pals (RuneLite plugin)

Drives a pixel-pet stream widget from **your own** in-game activity. The plugin sends small JSON events
(event format v1) to an HTTP endpoint **you** choose, usually your own relay
(`node relay/server.js` from the Pixel Pet Pals companion repo), which feeds the widget page.

> **Third-party data warning.** When enabled, this plugin sends your activity to a server you choose. That server
> is **not controlled or verified by Jagex or RuneLite**. It is **off by default** and sends nothing until you
> turn it on, set an endpoint URL and type a display name. Not affiliated with Jagex Ltd.

No art is bundled. The pets live in the widget/pack repo, not in this plugin.

## Config (section "Data sharing (third party)")

| key | default | meaning |
|---|---|---|
| `enabled` | **off** | Master switch. Off = nothing is sent. |
| `endpointUrl` | empty | `http://` or `https://` URL, e.g. `http://127.0.0.1:8787/events`. Empty or invalid = nothing is sent. |
| `sharedSecret` | empty | Optional. Sent as the `X-Pet-Secret` header; must match the relay's `PET_SECRET`. |
| `displayName` | empty | The name put in the `player` field. **You type it**; the plugin never reads your account name. Empty = nothing is sent. |
| `includeNpcName` | off | Adds the name of the NPC you are fighting to combat events. |
| `minIntervalSeconds` | 5 (2–60) | Never send more often than this. |

## Exactly what is sent

One HTTP `POST` per event to **`endpointUrl`** (and nowhere else), with `Content-Type: application/json` and,
if set, `X-Pet-Secret: <sharedSecret>`. The body is one v1 event with these fields only:

| field | value |
|---|---|
| `v` | always `1` |
| `player` | your typed `displayName` (trimmed, max 64 chars) |
| `ts` | send time, ISO 8601 UTC to the second, e.g. `2026-10-13T16:50:14Z` |
| `type` | one of `login`, `logout`, `activity`, `idle`, `level_up`, `heartbeat` |
| `activity.category` | `skilling`, `combat`, `banking` or `idle` (on `activity`, `idle` and `heartbeat` events) |
| `activity.skill` | `Woodcutting`, `Mining` or `Fishing`, only when `category` is `skilling` |
| `activity.target` | the NPC's name, **only** for `combat` and **only** if `includeNpcName` is on |
| `detail.skill`, `detail.level` | only on `level_up`: the skill name and your new real level |

Never sent: other players (names or anything else), chat, location/region/world, your account name or login,
items, inventory, bank contents, XP amounts, coordinates, client or system info. `activity.region` is never set.

### When

| event | when |
|---|---|
| `login` | you reach the logged-in state |
| `logout` | you return to the login screen (sent immediately, still subject to backoff) |
| `activity` / `idle` | your derived activity **changes** (see below), at most once per `minIntervalSeconds` |
| `level_up` | one of your real skill levels goes up (queued, at most once per `minIntervalSeconds`) |
| `heartbeat` | with your current activity, only after **60 s** with nothing else sent |

How activity is derived, only from your own client state:
- **banking**: the bank interface is open.
- **combat**: your player is interacting with an NPC (if you interact with a player, it is ignored).
- **skilling**: you gained Woodcutting, Mining or Fishing XP in the last ~6 s while animating.
- **idle**: none of the above (and no animation) for ~30 s. Otherwise the last activity is kept.

### Failure handling

Requests use RuneLite's shared `OkHttpClient` asynchronously (`enqueue`); the plugin starts no threads.
At most one request is in flight. Errors (network, non-2xx) are logged at debug level only and back off
exponentially: 5 s, 10 s, 20 s, … up to 5 min. A failed state change is retried after the backoff.
Up to 5 queued one-off events (level-ups, login/logout) are kept; older ones are dropped.

## Build & test

```
./gradlew build test            # JDK 11
scripts/e2e.sh ../osrs-companion  # posts plugin-built events to the real relay, which validates them
```

See [RUNNING.md](RUNNING.md) to try it in a RuneLite dev client with the local relay and widget.

License: BSD 2-Clause (see LICENSE).
