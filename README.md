# Watchcat

Server-side replay recording for Paper. Watchcat captures a suspected cheater's session
as a ReplayMod `.mcpr` file that your staff can open and watch back — without the recorded
player ever knowing, and without them installing anything.

Anticheat plugins tell you *that* something happened, with a confidence score. Watchcat
gives you the footage, so a human can decide.

## What makes it different

Recording a Minecraft session normally means running ReplayMod on the client that is being
recorded — which the suspect would have to install, and would obviously notice. Watchcat
records from the server instead, reconstructing the same file format from packets the
server was already sending.

It is strictly passive. Nothing is transmitted to the recorded player, no entity is hidden
or re-shown, no connection state changes, and no gameplay behaviour differs. Every packet
Watchcat produces is written to a file and nowhere else.

## Requirements

- **Paper** 1.21 or newer (developed and tested against Paper 26.2)
- **ProtocolLib** 5.4.0 or newer — without it the plugin still loads, but recording is
  disabled and `/watchcat status` says so
- **ReplayMod**, on the *reviewer's* client only, to watch the output

## Install

1. Drop `Watchcat-<version>.jar` and ProtocolLib into `plugins/`
2. Start the server
3. Have someone (literally anyone) log in once

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/watchcat status` | `watchcat.status` | Recording availability, active sessions, disk usage |
| `/watchcat record <player> start\|stop` | `watchcat.record` | Manual open-ended recording |
| `/watchcat flag <player> [duration] [reason]` | `watchcat.flag` | Record the next N seconds of a suspect |
| `/watchcat flags [player]` | `watchcat.flag` | Which flagged recordings are ready to review |
| `/watchcat reload` | `watchcat.admin` | Reload `config.yml` |

### Flagging

A flag is forward-looking: it starts recording at the moment it is raised and captures what
the player does **next**, for a fixed duration (120 seconds by default, 1–3600 allowed). It
is not a dump of a prior buffer, and it works whether or not `always-record` is enabled.

Flagging someone who is already being flag-recorded resets the clock rather than starting a
second recording, and the new reason is added to the ones already noted. A burst of
detections therefore produces one replay covering the whole episode, named after all of
them:

```
sjwz_flag_reach-killaura_180s_1786445760749.mcpr
```

Finished flag recordings land in `plugins/Watchcat/replays/flagged/` and are marked
protected in the index. Everything else goes to `plugins/Watchcat/replays/<player-uuid>/`.

## Hooking up your anticheat

Watchcat detects nothing on its own, by design. It is the camera, not the alarm.

Most detection plugins can run a console command on a detection. Point that at:

```
watchcat flag %player% 180 x-ray: 14 diamonds in 3 minutes
```

The duration is optional and only read as one if it parses as a number, so
`watchcat flag %player% suspected x-ray` works too. The reason is free text and ends up in
the filename and the replay metadata.

Java plugins can call the API directly, which also lets them be notified when the finished
file is ready:

```java
WatchcatAPI api = getServer().getServicesManager().load(WatchcatAPI.class);
if (api != null) {
    api.flagMoment(player, "reach: 3.4 blocks");
}
```

Every API method is safe to call from any thread, including your own detection thread, and
none of them throw for an ordinary rejection — inspect the returned `FlagResult` instead.
`FlagRecordingStartedEvent` and `FlagRecordingReadyEvent` let any plugin observe flags it
did not raise; the latter carries the path of the written replay.

## Configuration

`config.yml` is heavily commented. The keys that matter most:

| Key | Default | Meaning |
| --- | --- | --- |
| `always-record` | `false` | Record everyone continuously into a rolling buffer |
| `buffer-minutes` | `5` | How much history that buffer keeps per player |
| `default-flag-duration` | `120` | Seconds a flag records for when none is given |
| `auto-cleanup` | `false` | Master switch for deleting old replays |
| `retention-hours` | `36` | Delete replays older than this, when cleanup is on |
| `max-storage-mb` | `5000` | Disk budget; oldest go first once exceeded |
| `min-retention-hours` | `12` | Floor — never deleted under storage pressure |
| `protect-flagged-replays` | `true` | Flagged evidence is exempt from cleanup |

Recordings are large — expect tens of megabytes per player-hour. **Nothing is ever deleted
unless you enable `auto-cleanup`.**

## How it works

The interesting problem is that Watchcat starts recording a connection that is already deep
in the play phase, while a `.mcpr` file has to begin at login. Several things follow from
that:

- **The join sequence is reconstructed.** Login and configuration packets are captured
  passively from other players' joins and replayed as a prologue.
- **The world is snapshotted.** Chunks and entities around the player are read from server
  state and encoded as the packets a joining client would have received. `getChunkNow`
  returns null for non-resident chunks, so a snapshot never triggers generation or disk I/O.
- **The player is given a phantom self.** A server never tells you about yourself, so the
  suspect's own body is absent from a capture of their connection. The login packet is
  rewritten to point the viewer at entity id `-2`, freeing the real id to be spawned and
  animated like any other entity.
- **Self-state is synthesized.** Movement, head rotation, equipment, armour and arm swings
  are sampled from live server state, because the server broadcasts them only to *other*
  players. Block placements and breaks are client-predicted and merely acknowledged, so
  those are sampled from the world a tick after the event.
- **Bundles are expanded.** ProtocolLib intercepts upstream of the pipeline's unbundler, so
  bundled entity spawns are split into their sub-packets before being written.

## Building

```bash
JAVA_HOME=/path/to/jdk-21 ./mvnw clean package
```

Output lands in `target/`. The bytecode targets Java 17; the server itself needs whatever
its Minecraft version requires.

The POM compiles against `paper-api 1.21.4` while the plugin runs on much newer servers.
That skew is deliberate: everything version-sensitive is resolved reflectively against the
live server at runtime, deriving operand types from accessor return types rather than naming
classes that move between versions.

## Limitations

- **A player must join after the plugin loads** before any replay will open.
- **Long recordings can truncate.** A session is capped at 50,000 packets. Flag recordings
  keep the *oldest* packets when full — the start is the part you flagged — and report
  themselves as truncated in `/watchcat flags` and the console.
- **Self-state is sampled, not intercepted**, at 10 Hz. The recorded player's own movement
  is a close reconstruction rather than a byte-exact record of what their client did.
- **Bundle delimiters are not reproduced.** Their codec is bound to a canonical instance
  unreachable from a plugin. This costs at most one frame of atomicity.
- **Only the world the player is in at session start** gets a terrain snapshot; anything
  after that arrives through normal capture.
- **Block-break progress cracks** are not recorded — the swing is, the cracking is not.

## Privacy

This plugin records players without their knowledge. That is the point, and it is also
worth being deliberate about. Depending on where you and your players are, you may be
obliged to disclose that gameplay may be recorded — a line in your server rules or privacy
policy usually covers it. Replay files contain player positions, inventories, chat visible
to the recorded player, and UUIDs, so treat the `replays/` directory as personal data:
restrict access, and delete recordings you no longer need (`auto-cleanup` will do it for
you).

## License

MIT — see [LICENSE](LICENSE).
