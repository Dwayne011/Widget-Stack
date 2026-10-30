# Widget Stack

An Android app that lets you stack several home-screen widgets in one slot and
swipe between them, the way Samsung One UI (and iOS) do, on any launcher.

Status: **exploration / feasibility**. No app code yet.

Start with [docs/FEASIBILITY.md](docs/FEASIBILITY.md). It covers:

- why a normal widget cannot embed another app's widget, and the three ways round it
- the recommended architecture (the app is an `AppWidgetHost`, and publishes a
  "stack" widget whose pages are live renders of the hosted widgets)
- exactly where Shizuku helps (silent widget binding via `appwidget grantbind`)
  and where it does not
- the open risks, with the spike to run first before committing to a build
