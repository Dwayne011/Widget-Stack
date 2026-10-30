# Running the spike

The spike app has two display modes for the Stack widget, switchable at runtime:

| Mode | What the Stack widget contains | What it tests |
|---|---|---|
| **RELAY** (default) | Each inner widget's own `RemoteViews`, nested inside our `ViewFlipper` | Whether Android lets a widget carry another package's views. If yes, inner widgets are fully live and interactive on the home screen. |
| **BITMAP** | An off-screen render of each inner widget as an image | The known-good fallback; also useful to compare update timing. |

Everything the app does is logged under the Logcat tag `WidgetStack`, shown live
in the app, and written to `files/spike.log` (the **Share log** button exports it).

## Build

The project could not be compiled where it was written (no Android SDK, Google's
Maven blocked), so expect to fix small compile issues on first build. Requirements:
Android Studio Ladybug or newer, JDK 17+, an Android 12+ device.

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Test sequence

1. **Place the Stack widget.** Long-press the home screen, add "Widget Stack".
   It shows "Stack is empty". Log should contain `provider onEnabled` and `onUpdate`.
2. **Add an inner widget.** Open the app, **Add widget**, pick something simple and
   non-scrolling first (a clock, weather, or music widget). Expected log:
   - `allocated id=N`
   - `bindAppWidgetIdIfAllowed=false` then `launching system ACTION_APPWIDGET_BIND dialog`
   - `ACTION_APPWIDGET_BIND result=-1` (RESULT_OK)
   - `created host view id=N`, `host.startListening()`
   - `hostview updateAppWidget id=N rv pkg=<inner package> layout=0x...`
   - `render root.addView(flipper, page 0 inner=N pkg=<inner package>)`
   - `updateAppWidget accepted by AppWidgetService`
3. **Look at the home screen.** Three possible outcomes:
   - **Inner widget renders and its buttons work.** Relay is viable. Note the
     launcher (Pixel Launcher, Nova, Lawnchair) and Android version.
   - **"Problem loading widget".** AppWidgetService accepted the update but the
     launcher failed to inflate the cross-package child. Capture
     `adb logcat -s WidgetStack AppWidgetHostView RemoteViews` for the stack trace.
   - **Red error page inside the widget, or `AppWidgetService REJECTED update` in
     the log.** The system refused the mismatched package on the way in. The
     exception text is in the widget itself and in the log.
4. **Switch to BITMAP** (the **Mode** button) and confirm the same widget appears
   as an image. This proves the host and render path independently of the relay.
5. **Add a collection widget** (Gmail, Calendar agenda). In RELAY mode expect the
   list to be empty or error, because its `RemoteViewsService` must belong to the
   stack widget's own package. Record exactly what happens.
6. **Shizuku.** Start Shizuku, tap **Shizuku grantbind**, approve. Log should show
   `exec: appwidget grantbind --package dev.widgetstack.spike --user current` then
   `exit=0`. Add another widget: `bindAppWidgetIdIfAllowed=true` and no dialog.
7. **Resize the Stack widget** on the launcher. Log shows `onAppWidgetOptionsChanged`
   and inner widgets receive `updateAppWidgetSize`.

Use **Re-push** any time to rebuild and republish the stack without changing anything.

## What the log tells you

- `hostview updateAppWidget … actions=hidden(...)`: reflection into RemoteViews
  internals is blocked on this device. Not a problem, only less detail.
- `startForegroundService failed`: the host service could not start from the
  background. Inner updates stop when the app is backgrounded. Open the app once
  to restart it.
- `no RemoteViews received yet for inner id`: the provider has not pushed since
  the host started listening. Most widgets push within a second of
  `startListening`; some only on their own schedule. Re-push after a moment.
- `bitmap … bytes=`: compare against roughly 1.5 x (screen width x height) x 4.
  Going over makes the launcher drop the update silently.

## Collecting logs from the launcher side

The launcher inflates our widget in its own process, so its failures are not in
our log. Capture both at once:

```
adb logcat -c
adb logcat -s WidgetStack:V AppWidgetHostView:V RemoteViews:V AppWidgetService:V
```

Then reproduce, and paste the output back into the issue or chat.
