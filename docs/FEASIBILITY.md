# Widget stacking on Android: feasibility and proposed architecture

Goal: a standalone app that gives any launcher a Samsung-style widget stack.
The user drops one "Stack" widget on their home screen, adds existing widgets
from other apps into it, and swipes (or auto-rotates) between them.

## 1. The core constraint

A normal home-screen widget is described by `RemoteViews`. `RemoteViews` is a
whitelist of layouts and views (`FrameLayout`, `TextView`, `ImageView`,
`ListView`, `StackView`, `ViewFlipper`, …) that the launcher inflates in its own
process. There is no `RemoteViews` action for "put another app's widget here".
So a widget **cannot directly contain** another widget. Samsung can do it
because Samsung owns the launcher.

That leaves three strategies.

| Strategy | What it is | Verdict |
|---|---|---|
| A. Be the launcher | Ship a full home-screen replacement that hosts widgets natively, like Niagara / Smart Launcher / Action Launcher already do | Only "true" stacking, but it is a launcher project, not a widget app. Out of scope unless you want to build a launcher. |
| B. Overlay window | Draw a `SYSTEM_ALERT_WINDOW` over the launcher containing a live `AppWidgetHostView` | Fragile: must track launcher page scroll, folders, app-drawer state, and wallpaper offsets. Breaks on every launcher differently. Not recommended. |
| **C. Host + mirror** | The app is itself an `AppWidgetHost`. It binds the user's chosen widgets, inflates them off-screen, renders each to a bitmap, and publishes those bitmaps through its own "Stack" `AppWidgetProvider` | **Recommended.** Works on every launcher, uses public APIs for everything except the two optional Shizuku shortcuts. The trade-off is that pages are pictures of widgets, so interaction needs a separate path (section 3). |

The rest of this document is strategy C.

## 2. Architecture (strategy C)

```
+----------------------------------------------------------------------+
| Widget Stack app process                                              |
|                                                                       |
|  AppWidgetHost  --binds-->  widget A, widget B, widget C (other apps) |
|        |  onUpdate / RemoteViews pushed by providers                  |
|        v                                                              |
|  Off-screen AppWidgetHostView per page -> measure/layout/draw         |
|        |                                                              |
|        v                                                              |
|  Bitmap per page  --->  RemoteViews for the Stack widget              |
|                           (StackView / ViewFlipper of ImageViews)     |
|                                   |                                   |
+-----------------------------------|-----------------------------------+
                                    v
                        Any launcher renders the Stack widget
```

Components:

1. **Host service / repository.** Holds one `AppWidgetHost` with a fixed host id.
   Allocates app widget ids, binds providers, persists the stack
   configuration (which widget ids, order, size) in Room or DataStore. Calls
   `startListening()` while a render is needed and `stopListening()` otherwise
   to stay cheap on battery.
2. **Renderer.** For each hosted widget id: `host.createView(context, id, info)`
   gives an `AppWidgetHostView`. Measure it at the Stack widget's current cell
   size (from `getAppWidgetOptions` on the Stack widget), lay it out, draw to a
   bitmap. `AppWidgetHostView.updateAppWidgetSize` must be called so providers
   that use `RemoteViews` size mappings (Android 12+) pick the right layout.
   Collection widgets (`ListView` / `GridView` backed by `RemoteViewsService`)
   work as long as the host is listening; the adapter connects through the
   AppWidget service on the host's behalf.
3. **Stack widget provider.** A normal `AppWidgetProvider`. Its layout is a
   `StackView` (native flick-through 3D stack, supported in `RemoteViews`) or an
   `AdapterViewFlipper` for timed rotation. Pages come from a
   `RemoteViewsService` whose items are `ImageView`s showing the rendered
   bitmaps. On API 31+ `RemoteViews.RemoteCollectionItems` avoids the service.
   Memory: `RemoteViews` bitmaps are capped at roughly 1.5x screen pixels x 4
   bytes per update, so render at the cell size, not full resolution, and
   use `RGB_565` where the widget has no transparency.
4. **Picker / config activity.** Lists installed providers via
   `AppWidgetManager.getInstalledProviders()`, previews them, lets the user add
   to a stack, reorder, and set rotation. Also runs each inner widget's own
   configuration activity (`ACTION_APPWIDGET_CONFIGURE`) when the provider
   declares one.
5. **Update pipeline.** Re-render only the page whose `AppWidgetHostView`
   received an update, then push one `updateAppWidget` for the Stack widget.
   Debounce to avoid hitting the launcher with bursts.

### Binding: the step that needs a permission

`AppWidgetManager.bindAppWidgetIdIfAllowed()` succeeds only if the caller holds
`BIND_APPWIDGET` (signature/privileged, so unavailable to us) **or** the
package has been allow-listed by the system. Otherwise the app must launch the
system `ACTION_APPWIDGET_BIND` dialog for each widget it adds. That dialog is
the "Allow Widget Stack to create widgets and access their data?" prompt
with an "always allow" checkbox. Once the user ticks it, later binds are
silent. So **without Shizuku the flow is: one dialog, one checkbox, done.**

## 3. Interaction with stacked widgets

Pages are bitmaps, so a tap on the Stack widget does not reach the inner
widget. Options, in order of robustness:

1. **Expand on tap (ship this first).** Tapping a page opens a translucent
   activity that shows the *live* `AppWidgetHostView` of that page at the same
   screen position (pass the widget's bounds via the click `PendingIntent` and
   `sourceBounds`). Everything inside works exactly as in a launcher, because
   we are the host. Tap outside or swipe down to dismiss. This is the same
   pattern "popup widget" apps use, and it is fully supported API.
2. **Click passthrough (later, optional).** Overlay the page with an N x M grid
   of transparent hotspots, each with a `PendingIntent` broadcast carrying its
   cell coordinates. On receipt, find the inner view under that point in the
   off-screen `AppWidgetHostView` and call `performClick()`, which fires the
   provider's own `PendingIntent` from our process. Risk: background
   activity launch (BAL) rules on Android 14/15 can block the start unless the
   inner provider's `PendingIntent` opted in with
   `setPendingIntentCreatorBackgroundActivityStartMode`. We do not control
   other apps' intents, so expect this to work for some widgets and not others.
   Shizuku can rescue it (section 4).
3. Scrolling inside collection widgets only works in mode 1.

## 4. Where Shizuku helps

Shizuku runs our code with the `shell` uid (2000). The AOSP Shell package holds
both `BIND_APPWIDGET` and `MODIFY_APPWIDGET_BIND_PERMISSIONS`, and AOSP ships a
shell command that uses them:

```
appwidget grantbind --package <our.package> --user current
appwidget revokebind --package <our.package> --user current
```

Running that once through Shizuku allow-lists the app, after which
`bindAppWidgetIdIfAllowed()` returns true and **no system dialog ever appears**,
including when re-adding widgets after a reinstall or when adding widgets from
a background flow (for example an "add to stack" share target). This is the
same mechanism the system dialog's "always allow" checkbox uses, so it is
well-trodden, and it is the same shape of integration as a Shizuku-based
At a Glance app: a one-shot privileged call, then ordinary APIs.

Shizuku also helps with:

- **Click passthrough BAL blocks.** With shell uid we can start the target
  activity via `IActivityTaskManager` / `am start` regardless of BAL rules,
  making mode 2 in section 3 reliable.
- **Hidden API access.** Not strictly Shizuku, but the same user base: use
  `HiddenApiBypass` if we need `RemoteViews` internals (for example reading
  click actions to compute hotspots precisely instead of a grid).

Shizuku does **not** help with the core constraint. Nothing short of being the
launcher makes a widget literally contain another widget. It also does not
survive reboot on non-rooted phones unless the user restarts Shizuku via
wireless debugging, so every Shizuku path needs a non-Shizuku fallback (the
system dialog) and must degrade gracefully.

## 5. Risks and unknowns

- **Launcher widget bitmap limits.** Large stacks at 4x2 or bigger will hit the
  `RemoteViews` bitmap budget. Mitigation: render only the visible page plus
  neighbours, lower bit depth, and clamp max pages (Niagara and Samsung both
  cap around 4-7).
- **StackView UX.** `StackView` has a fixed 3D-card look and inconsistent swipe
  handling across launchers. If it feels wrong, fall back to `ViewFlipper`
  with left/right tap zones and an optional auto-advance.
- **Battery.** A listening `AppWidgetHost` receives every inner widget update.
  Only listen while the Stack widget is on screen (`APPWIDGET_ENABLED` /
  `APPWIDGET_DISABLED` plus a short idle timeout).
- **Widgets that render differently off-screen.** Some providers check
  `AppWidgetHostView` size or use `RemoteViews` layout mappings. Calling
  `updateAppWidgetSize` with the real cell bounds before draw covers most;
  test with Google Calendar, Gmail, Spotify, and a weather widget early.
- **Play Store.** Hosting widgets is allowed (Launchers do it). Shizuku use
  is allowed but must be optional and explained. `SYSTEM_ALERT_WINDOW` is not
  needed in this design, which avoids a review hurdle.
- **Interaction fidelity.** Mode 1 (expand on tap) is one extra tap compared to
  Samsung. That is the honest cost of not being the launcher.

## 6. Recommended first spike (one or two days)

Build a throwaway project that proves the two uncertain links end to end:

1. Bind two real third-party widgets via `ACTION_APPWIDGET_BIND` and render
   them to bitmaps off-screen at a 4x2 cell size. Confirm collection widgets
   render and update.
2. Publish them through a `StackView`-based Stack widget on Pixel Launcher and
   on one other launcher (Nova or Lawnchair). Measure update latency and check
   the bitmap budget.
3. Implement expand-on-tap with a live `AppWidgetHostView` and confirm clicks
   and scrolling work inside it.
4. With Shizuku, run `appwidget grantbind` and confirm the bind dialog
   disappears.

If 1 to 3 pass, strategy C is viable and the full build is mostly product
work (picker UI, persistence, rotation settings). If 2 fails on bitmap budget
or swipe feel, revisit `ViewFlipper` before anything else.

## 7. Suggested stack

- Kotlin, minSdk 31 (Android 12). This gives `RemoteCollectionItems`, widget
  size mappings and rounded-corner APIs, and matches the Shizuku audience.
- Jetpack Glance is **not** suitable for the Stack widget itself (no
  `StackView`, no bitmap collections); use classic `RemoteViews`.
- Compose for the picker and settings UI.
- `dev.rikka.shizuku:api` + `provider` for the optional privileged path;
  `org.lsposed.hiddenapibypass` if hidden APIs become necessary.

## Sources

- [Build a widget host (Android developers)](https://developer.android.com/develop/ui/views/appwidgets/host)
- [AppWidgetManager reference, `bindAppWidgetIdIfAllowed` / `ACTION_APPWIDGET_BIND`](https://developer.android.com/reference/android/appwidget/AppWidgetManager)
- [AOSP commit adding `BIND_APPWIDGET` and `MODIFY_APPWIDGET_BIND_PERMISSIONS` to the Shell package](https://gitlab.e.foundation/e/os/android_frameworks_base/-/commit/7e9c21547a950e1197dfb83c54c937030da1cb9d)
- [AOSP `appwidget grantbind` / `revokebind` shell command source](https://code.yawk.at/android/android-9.0.0_r35/com/android/commands/appwidget/AppWidget.java)
- [Background activity launch restrictions (Android 14/15)](https://developer.android.com/guide/components/activities/background-starts)
- [Android Authority: which launchers offer widget stacking](https://www.androidauthority.com/android-widget-stacking-3516219/)
