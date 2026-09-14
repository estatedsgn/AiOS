# AiOS

An agent that lives on an Android phone and operates it the way a person does —
it reads what is on screen, decides what to touch, and touches it. You give it a
goal in plain language and your own Anthropic API key; it drives the phone one
action at a time until the goal is met or it decides to ask you something.

It is not a macro recorder and not a set of per-app integrations. It works on
apps it has never seen, because what it reads is the accessibility tree that
Android exposes for every app on the device.

## How it works

```
  ┌──────────────── one step of a run ────────────────┐
  │                                                   │
  │   read screen ──► decide ──► policy ──► execute   │
  │   (a11y tree)    (Claude)    (gate)     (gesture) │
  │        ▲                                     │    │
  │        └─────────── result fed back ─────────┘    │
  └───────────────────────────────────────────────────┘
```

Each turn the model receives a compact listing of the current screen:

```
SCREEN 1080x2340 app=com.android.settings
[0] button "Network & internet" @540,412
[3] switch "Wi-Fi" checked @980,556
[7] edittext "" editable focused @540,180
(+12 more elements omitted - scroll or narrow the view to reach them)
```

It answers with exactly one tool call — `tap`, `type_text`, `swipe`,
`launch_app`, `press_key`, `wait`, `ask_user` or `finish` — which is checked
against the safety policy, executed, and the outcome is fed back as the next
turn's input. Element ids are regenerated every turn and the loop refuses ids
that are not on the screen the model was actually shown.

## Layout

| Module  | What it is | Needs the Android SDK |
|---------|------------|-----------------------|
| `core/` | The agent: screen model, planner, safety policy, run loop. Pure Kotlin/JVM. | No |
| `app/`  | The phone: accessibility service, foreground run service, Compose UI. | Yes |

The split is deliberate. Everything that decides *what to do* lives in `core`
and is driven in tests by a fake device and a scripted planner, so the agent's
behaviour is verified without an emulator. `app` holds only the parts that
genuinely need hardware.

Key types:

- **`ScreenGraphBuilder`** flattens the raw accessibility tree. Android wraps a
  single visible button in layers of unlabelled containers; the builder lifts
  the text onto the clickable ancestor so one button reads as one element, and
  drops what is invisible, empty or off-screen.
- **`ClaudePlanner`** runs a manual tool loop against the Messages API. Manual
  rather than `BetaToolRunner` because these tool calls are not in-process
  functions: each has to clear the policy, may wait on a human, and then runs
  against hardware. It caches the system prompt and trims history in
  `tool_use`/`tool_result` pairs so a long run neither grows without bound nor
  orphans a tool result.
- **`ActionPolicy`** decides `Allow` / `NeedsConfirmation` / `Blocked`.
- **`AgentRunner`** is the loop, exposed as a `Flow` so cancelling the collector
  cancels the run — there is no separate stop flag to fall out of sync.

## Safety

An agent with an accessibility service can do anything you can do, so the
default is not "trust it":

- **Always refused, in every mode:** placing calls, and the accessibility and
  device-admin settings screens — the agent cannot widen its own permissions or
  switch itself off.
- **Confirmed by default:** payment and messaging apps, password fields, and any
  control labelled with a consequential word (`pay`, `delete`, `send`,
  `confirm`, `uninstall`, …). You see what it wants to do and why, and tap
  Approve or Reject.
- **A rejection is a decision.** The model is told, in the system prompt and in
  the feedback it receives, that a rejected action must not be retried by
  another route. There is a test asserting that wording.
- **Runs are visible.** A run holds a foreground notification with a Stop button
  for its whole life, so the phone can never be driven silently.
- **Bounded.** A step ceiling ends a run that never finishes, and repeating the
  same action on an unchanged screen three times aborts it.
- Your API key is held in `EncryptedSharedPreferences` and goes nowhere except
  Anthropic.

Three autonomy modes — confirm everything, confirm consequential actions
(default), don't ask — and the refusals above hold in all three.

## Building

```bash
# The agent's logic - runs anywhere, no Android SDK needed
./gradlew :core:test

# The full app - needs the Android SDK
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```

`settings.gradle.kts` includes `:app` only when an Android SDK is present, so
`:core:test` works on a machine or CI runner that has none.

## Running it

1. Install the APK and open AiOS.
2. Settings → paste your Anthropic API key, pick a model and an autonomy mode.
3. Turn on **AiOS agent control** in Android Settings → Accessibility. Only you
   can grant this; nothing in the app can grant it to itself.
4. Type a goal and press Run. Watch the step log, approve what it asks about.

## Status

`core` is complete and tested — 37 tests over tree flattening, policy verdicts,
tool-argument parsing and every branch of the run loop, including rejection
handling, blocked actions, stale element ids, stuck detection and the step
ceiling.

`app` is complete but has not been compiled or run: it needs the Android SDK,
AndroidX and the Android Gradle Plugin, all of which are served from
`dl.google.com`, which was unreachable from the environment this was written in.
Cross-module references, interface conformance and resource references were
verified statically instead. Expect to fix the ordinary things a first real
build turns up.
