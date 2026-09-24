# Tracking admin/user actions on items — the Audit service

This turns on DSpace-CRIS's built-in `AuditService` / `AuditConsumer`, which was already wired
into the event pipeline (`event.dispatcher.default.consumers` in `dspace.cfg` already lists
`audit`) but never actually enabled. It replaces the ad-hoc action logging previously done by
`updateReportItem()` in `ItemServiceImpl` and `InstallItemServiceImpl` — a custom, out-of-spec
hook, not part of upstream DSpace, that wrote a line of text into `dc.subject` of a designated
"report item" (`dspace.action.report.item.uuid`) on update/publish/withdraw/reinstate/delete.

## Why replace the old hook

Beyond the `NullPointerException` it threw on every script-driven change (see the companion PR
that made it null-safe), the design itself has problems the Audit service does not share:

| | old hook (`dspace.action.report.*`) | Audit service |
|---|---|---|
| Storage | free-text line glued into `dc.subject` of a real Item | structured document (eperson, object, subject, event type, timestamp, detail) in its own Solr core |
| Coverage | 5 hardcoded actions (update, publish, withdraw, reinstate, delete) | every event the dispatcher sees — configured as `event.consumer.audit.filters = All+All` |
| Access | the "report item" is excluded from the search index, but is still a normal Item — anyone with its handle/UUID can read the raw log directly | REST endpoints are `@PreAuthorize("hasAuthority('ADMIN')")`; no separate Item, nothing indexed for search |
| Concurrency | de-dup key held in `ItemServiceImpl.reportLogger`, a `public static String` mutated with no synchronization — a shared, unguarded field across every concurrent request in the whole application | each event is a separate Solr document; no shared mutable state |
| Querying | none — read the raw metadata by hand | `findByObject`, paginated, sortable by time |

## What was changed

| File | Change |
|---|---|
| `dspace/config/modules/audit.cfg` | `audit.enabled = true` |
| `dspace/src/main/docker/dspace-solr/Dockerfile` | copies `dspace/solr/audit/conf` into the Solr image, alongside the other cores |
| `docker-compose.yml` | `dspacesolr`'s entrypoint now also runs `precreate-core audit` and copies the configset into it |
| `dspace/solr/audit/conf/solrconfig.xml` | adds a 15s soft commit, matching the fix already applied to the statistics core — otherwise a newly recorded event stays invisible to the REST API for up to the 15 minute hard-commit interval |

Nothing else needs to change: `event.dispatcher.default.consumers` already includes `audit`, and
`AuditConsumer.consume()` already checks `audit.enabled` itself, so simply flipping that one
property is what turns the whole pipeline on.

`AuditService.store()` handles a script-driven `Context` (no current user) correctly already —
unlike the old hook, it does not assume one exists:

```java
EPerson eperson = context.getCurrentUser();
if (eperson != null) {
    audit.setEpersonUUID(eperson.getID());
}
```

An event triggered by a script (`doi-organiser`, `curate`, harvesting, …) is recorded with no
`eperson`, which is the honest answer — there is no human actor — rather than crashing.

## Deploying

The Solr image has to be rebuilt for the new core, and the container recreated (not just
restarted, since it needs the new build):

```bash
docker compose build dspace dspacesolr
docker compose up -d dspace dspacesolr
```

Confirm the core exists and is receiving documents:

```bash
docker exec dspacesolr curl -s 'http://localhost:8983/solr/audit/select?q=*:*&rows=0'
```

Then do something to an item (edit a metadata field, say) and re-run that query a few seconds
later — `numFound` should have gone up by one.

## Using it

All of this is behind `@PreAuthorize("hasAuthority('ADMIN')")` on the REST side, and the frontend
already ships a UI for it — `dspace-angular`'s `audit-page` module (`src/app/audit-page/`),
mounted at `/auditlogs`. It was simply unreachable in practice while `audit.enabled` was off:
every request 404s via `returnNotFoundIfDisabled()`, so the screens rendered empty regardless of
who visited them. Once this PR is deployed, three screens become live, with no frontend changes
needed:

* **`/auditlogs`** — every recorded event, newest first: id, event type, object, subject,
  acting eperson, timestamp. Rows link into the other two screens.
* **`/auditlogs/object/<uuid>`** — one object's full history. This is also wired into every
  item's admin context menu already: the "⋮" actions menu on an item page has an **Audit**
  button (`src/app/shared/context-menu/audit-item/audit-item-menu.component.ts`, visible only to
  admins via `FeatureID.AdministratorOf`) that opens exactly this view for that item. It has been
  there all along; it only ever showed "No audits found" for lack of any enabled backend to ask.
* **`/auditlogs/<event-id>`** — the detail of a single event.

None of the three screens has a search box, a filter, or a clickable column sort — they are
read-only, paginated tables ordered by time, navigable only by clicking a row (to its detail, or
to its object's full history) or paging through. The `AuditDataService` on the frontend
(`src/app/core/audit/audit-data.service.ts`) only calls `findAll()` and `findByObject()`, and the
REST repository only exposes `findByObject` as a `@SearchRestMethod` — there is nothing to build
a richer filter UI against yet without adding a new REST search method first (by event type, by
eperson, or by date range, say). The detail screen also does not render the `detail` field the
`Audit` model carries, so today it is unused by the UI even where the backend has it.

`findByObject` is exactly the "what happened to this item" view the old report item was trying
to approximate by hand — it is now a paginated, admin-only table instead of parsing
semicolon-separated text out of `dc.subject`, and it was already one click away on every item.

There is currently no link to `/auditlogs` from the admin sidebar/menu — reaching the general
overview means typing the URL directly, or arriving via a specific item's Audit button. Adding a
sidebar entry is a small, separate frontend change if the client wants the general overview to be
discoverable without knowing the URL.

## Retention

`filters = All+All` means every event on every object type is recorded — with ~26,000+ items and
regular editing, this core will grow continuously, and nothing in this codebase prunes it
automatically (`dspace/config/launcher.xml` has no audit-related command). Decide a retention
window and prune periodically with a direct Solr delete-by-query, for example to drop anything
older than a year:

```bash
docker exec dspacesolr curl -s 'http://localhost:8983/solr/audit/update?commit=true' \
  -H 'Content-Type: application/json' \
  -d '{"delete": {"query": "timeStamp:[* TO NOW-1YEAR]"}}'
```

Run that from cron at whatever interval suits the retention policy the client wants; there is no
automatic expiry configured in `dspace/solr/audit/conf/schema.xml`.

## Retiring the old hook

Once the new system has been observed working for a while:

1. Set `dspace.action.report.active = false` in `local.cfg` (leave the property itself in
   `dspace.cfg` alone, as with every other override in this deployment).
2. The old "report item" (`dspace.action.report.item.uuid`) and its accumulated `dc.subject`
   history can be kept as a historical record, or exported and the item withdrawn, once nobody
   needs to cross-reference it against the old format any more.
3. `updateReportItem()` in `ItemServiceImpl` / `InstallItemServiceImpl` and the
   `reportLogger` field can be deleted from the codebase entirely in a later cleanup PR — left in
   place for now since #10 (the null-safety fix) already reduced it to a config-gated no-op once
   `dspace.action.report.active = false`.
