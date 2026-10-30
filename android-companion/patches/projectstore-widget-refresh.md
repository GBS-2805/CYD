# ProjectStore: take a widget card's text from its descriptor

Handoff snippet, not applied here: this repo's `ProjectStore.java` predates the
registry change, so the method below does not exist in this tree. Apply it in the
app chat's tree, on top of the registry commit that introduced `WidgetCatalog`.

## Why

That commit renames the legacy `deck` record in place to `widget:phone_deck`, which
keeps its log, but it leaves the record's card text alone. An upgraded install
therefore shows `ESP32 Phone_Widget` and the old `INSIDE PROJECT` tagline while a
fresh install shows whatever the descriptor says — and the app's docs now call the
card **Phone Deck**. This refresh makes the descriptor authoritative for the card
text and leaves the user's own fields untouched.

It also heals records seeded by an earlier build of the registry change, before
`cyd_phone_deck/widget.json` was renamed, without anyone clearing app data.

## Replace this block

```java
    /**
     * Keeps the notebook in step with the widgets the APK carries: the old `deck`
     * record is renamed in place so an upgrade keeps its log, and any widget with
     * no record yet gets its seed. Writes only when something actually changed.
     */
    private static List<Project> withWidgets(Context context, List<Project> projects) {
        boolean changed = false;
        for (Project project : projects) {
            if (LEGACY_DECK_ID.equals(project.id)) {
                project.id = WIDGET_PREFIX + "phone_deck";
                changed = true;
            }
        }
        for (WidgetCatalog.Widget widget : WidgetCatalog.all(context)) {
            String id = WIDGET_PREFIX + widget.id;
            boolean present = false;
            for (Project project : projects) {
                if (id.equals(project.id)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                projects.add(widgetSeed(widget));
                changed = true;
            }
        }
        if (changed) save(context, projects);
        return projects;
    }
```

## With this block

```java
    /**
     * Keeps the notebook in step with the widgets the APK carries: the old `deck`
     * record is renamed in place so an upgrade keeps its log, any widget with no
     * record yet gets its seed, and a record whose card text no longer matches its
     * descriptor is refreshed. Writes only when something actually changed.
     */
    private static List<Project> withWidgets(Context context, List<Project> projects) {
        boolean changed = false;
        for (Project project : projects) {
            if (LEGACY_DECK_ID.equals(project.id)) {
                project.id = WIDGET_PREFIX + "phone_deck";
                changed = true;
            }
        }
        for (WidgetCatalog.Widget widget : WidgetCatalog.all(context)) {
            String id = WIDGET_PREFIX + widget.id;
            Project record = null;
            for (Project project : projects) {
                if (id.equals(project.id)) {
                    record = project;
                    break;
                }
            }
            if (record == null) {
                projects.add(widgetSeed(widget));
                changed = true;
            } else if (refreshFromWidget(record, widget)) {
                changed = true;
            }
        }
        if (changed) save(context, projects);
        return projects;
    }

    /**
     * Card text belongs to the descriptor; the log fields — status, build, touch,
     * notes and the timestamp — stay on the record, because that is what the user
     * typed. Notes are left alone: they can carry text merged from FirmwareNotes.
     */
    private static boolean refreshFromWidget(Project project, WidgetCatalog.Widget widget) {
        String libraries = String.join(", ", widget.libraries);
        boolean stale = !widget.name.equals(project.name)
                || !widget.tagline.equals(project.tagline)
                || !widget.body.equals(project.body)
                || !libraries.equals(project.libraries)
                || project.kind != KIND_INSIDE;
        if (!stale) return false;
        project.name = widget.name;
        project.tagline = widget.tagline;
        project.body = widget.body;
        project.libraries = libraries;
        project.kind = KIND_INSIDE;
        return true;
    }
```

## Notes

- `refreshFromWidget` writes only on a real difference, so `save()` and the version
  bump that `ProjectsTab` watches do not fire on every `all()` call.
- `status`, `build`, `touch`, `notes` and `updatedMs` survive: a renamed record
  keeps the log the rename exists to protect.
- `widget.name` is compared with `equals`, not `==`, and tolerates a null record
  field from an older JSON store.
- Paired with `cyd_phone_deck/widget.json` in this repo, which now says
  `"name": "Phone Deck"`. The firmware still advertises `ESP32 Phone_Widget <TAG>`,
  so the scan list shows that name while the card shows `Phone Deck`; the app docs
  that claim the deck advertises "Phone Deck" are still wrong until the firmware or
  those lines change.
