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

All of this is behind `@PreAuthorize("hasAuthority('ADMIN')")` — only a repository administrator
can read it, via the REST API:

```
# Every audit event, newest first
GET /server/api/system/auditevents?sort=timeStamp,desc

# The full history of one item
GET /server/api/system/auditevents/search/findByObject?object=<item-uuid>&sort=timeStamp,desc

# One event, embedding who did it and to what
GET /server/api/system/auditevents/<event-uuid>?embed=eperson,object,subject
```

`findByObject` is exactly the "what happened to this item" view the old report item was trying
to approximate by hand — it is now a single paginated, sortable REST call instead of parsing
semicolon-separated text out of `dc.subject`.

There is no admin-UI screen for this out of the box in this fork; if the client wants one, it is
a small addition on top of an endpoint that already exists and is already permission-checked,
rather than new backend work.

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
