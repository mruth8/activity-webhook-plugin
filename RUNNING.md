# Running Activity Webhook locally (dev client)

You need JDK 11, Node 18+ and, as an example receiver, a checkout of the Pixel Pet Pals companion repo
(relay + widget), e.g. `../osrs-companion`.

## 1. Start the relay (loopback only)

```
cd ../osrs-companion
PET_SECRET=choose-a-secret node relay/server.js
# relay on http://127.0.0.1:8787  (widget at /widget/, POST /events)
```

`PET_SECRET` is optional for loopback use. If you set it, put the same value in the plugin's "Shared secret".

## 2. Open the widget

Open <http://127.0.0.1:8787/widget/?pet=olmlet> in a browser (or add it as an OBS Browser Source).
Options: `&size=compact`, `&variant=…`, `&player=<your display name>` (only show your events).

## 3. Run RuneLite in developer mode with the plugin

```
cd pixel-pet-pals-plugin   # repo checkout of the Activity Webhook plugin
./gradlew run
```

This runs `com.activitywebhook.ActivityWebhookPluginTest` (the same pattern as runelite/example-plugin): it loads the
plugin as a built-in and starts RuneLite with `--developer-mode --debug`. Log in with your account as usual.

## 4. Configure the plugin

Configuration → **Activity Webhook** → section "Data sharing (third party)":

1. Endpoint URL: `http://127.0.0.1:8787/events` (plain http is only allowed to localhost/127.0.0.1/[::1]; anything else needs https)
2. Shared secret: same as `PET_SECRET` (or leave empty if the relay has none)
3. Display name: whatever the widget should show, **or** turn on "Use my in-game name" to send your RuneScape name
   instead (this sends your RuneScape name to the relay; the Display name can then stay blank). If you filter the
   widget with `&player=`, use the same name.
4. Turn on **Send activity** (it is off by default)

Chop a tree, mine a rock, fish, attack an NPC, open the bank, or stand still for ~30 s, and the widget should follow.
The relay prints one line per accepted event (`event activity skilling <name>`).

## Troubleshooting

- Nothing arrives: check that "Send activity" is on, the URL starts with http(s)://, and the display name isn't empty
  (or "Use my in-game name" is on and you are logged in; the name is read on the first game tick).
- `401` in the relay or no events: the shared secret doesn't match `PET_SECRET`.
- Errors are only logged at debug level (`--debug` is on with `./gradlew run`); the plugin backs off up to 5 min
  after repeated failures. Toggle any plugin setting to reset the backoff.
