# DOI registration with DataCite — University of Cyprus

DOIs are minted for **doctoral theses only** and registered with DataCite under prefix
`10.82357`. This note covers what was configured, what still has to be verified against the
live data, and how to roll the registration out in controlled batches.

Reference: <https://wiki.lyrasis.org/spaces/DSDOC7x/pages/104566767/DOI+Digital+Object+Identifier>

## Credentials — never commit these

`identifier.doi.user` and `identifier.doi.password` are placeholders in `dspace.cfg`, which is
under version control. Put the real values in `[dspace]/config/local.cfg`, which is git-ignored:

```properties
identifier.doi.user     = <the DataCite repository account id>
identifier.doi.password = <the DataCite password>
```

The password was shared over chat during setup, so rotate it in DataCite Fabrica once the
integration is working.

## What was configured

| File | Change |
|---|---|
| `dspace.cfg` | `identifier.doi.prefix = 10.82357`, `identifier.doi.namespaceseparator = gnosis/`, new `identifier.doi.datacite.host`, `doi` added to `event.dispatcher.default.consumers` |
| `spring/api/identifier-service.xml` | Enables `VersionedDOIIdentifierProvider`, the `DataCiteConnector` and the default DOI generation strategy |
| `spring/api/item-filters.xml` | `uc-doctoral-thesis-doi_filter` — the rule deciding which items get a DOI |
| `spring/api/crosswalks.xml` | `referCrosswalkUcPublicationDataciteXml` and the resource type converter bean |
| `crosswalks/template/uc-publication-datacite-xml.template` | The DataCite kernel-4 XML that is sent |
| `crosswalks/mapConverter-ucThesisDataciteResourceTypes.properties` | `dc.type` value → DataCite `resourceTypeGeneral` |

`VersionedDOIIdentifierProvider` is used rather than `DOIIdentifierProvider` because item level
versioning is active on this installation (the `versioning` consumer is in
`event.dispatcher.default.consumers`); the plain provider throws at startup in that case.

### Which items get a DOI

`uc-doctoral-thesis-doi_filter` requires **all** of:

- the item is archived,
- it is not withdrawn,
- it is a doctoral thesis: `dc.type` ends with `doctoralThesis`, or `dc.type.uhtype` is
  exactly `Doctoral Thesis`,
- it does not already carry a DOI under prefix `10.82357`,
- it has at least one bitstream.

### What is sent to DataCite

The XML is built from `uc-publication-datacite-xml.template`, a copy of the stock
`publication-datacite-xml.template` with the client's requirements applied. The generic export
crosswalk was deliberately left untouched.

| DataCite element | Source |
|---|---|
| `identifier` | the minted DOI |
| `creators` | `dc.contributor.author` (+ ORCID and affiliation when linked) |
| `titles` | `dc.title` |
| `publisher` | fixed text `University of Cyprus` |
| `publicationYear` | year of `dc.date.issued` |
| `subjects` | `dc.subject` |
| `contributors` | `HostingInstitution` and `DataManager`, both fixed to `University of Cyprus` |
| `dates` | `dc.date.issued`, `datacite.available` or `dc.date.available` |
| `language` | `dc.language.iso` |
| `resourceType@resourceTypeGeneral` | `dc.type` (`info:eu-repo/semantics/doctoralThesis`) through `mapConverter-ucThesisDataciteResourceTypes.properties` → `Dissertation` |
| `resourceType` text | `dc.type.uhtype` |
| `alternateIdentifiers` | `dc.identifier.uri` |
| `rightsList`, `descriptions` | as in the stock template |

Publisher, hosting institution and data manager are written into the template rather than taken
from `crosswalk.dissemination.DataCite.publisher` / `.dataManager` / `.hostingInstitution`.
Those properties belong to the XSLT crosswalk path; this fork's `DataCiteConnector` uses the CRIS
refer crosswalks instead and **never reads them** — only
`crosswalk.dissemination.DataCite.namespace` is used, and it already matches kernel-4.

The resource type needs its own converter because the stock
`mapConverterDataciteToPublicationTypes` bean sets `useAuthority=true`: it keys on the COAR
*authority* of `dc.type` and ignores plain text values. The new bean uses `useAuthority=false`
and keys on the value. Unmapped values fall back to `Text`, so the XML stays schema valid even
for a type nobody anticipated.

## Where the type comes from

Checked against live items:

```
dc.type         info:eu-repo/semantics/doctoralThesis
dc.type.uhtype  Doctoral Thesis
```

`dc.type` is the field the filter and the resource type mapping key on. Worth knowing when
maintaining this: no submission form writes `dc.type` — the phd form only writes
`dc.type.uhtype` from the `common_types_phd` value pairs — and the string
`info:eu-repo/semantics/doctoralThesis` appears nowhere in `[dspace]/config`. It reaches the
items through the migration/import rather than through DSpace configuration, so grepping the
config for it finds nothing and proves nothing.

### Counting the theses

Connect to the database container. Check the name first with `docker ps`; in the compose file it
is `dspacedb`, and the DSpace postgres image creates the `dspace` role and database:

```bash
docker exec -i dspacedb psql -U dspace -d dspace
```

**What the type fields actually hold**, over archived, non withdrawn items only:

```sql
SELECT coalesce(mfr.qualifier, '(no qualifier)') AS field,
       mv.text_value,
       count(*) AS occurrences
FROM item i
JOIN metadatavalue mv           ON mv.dspace_object_id    = i.uuid
JOIN metadatafieldregistry mfr  ON mfr.metadata_field_id  = mv.metadata_field_id
JOIN metadataschemaregistry msr ON msr.metadata_schema_id = mfr.metadata_schema_id
WHERE msr.short_id = 'dc' AND mfr.element = 'type'
  AND i.in_archive AND NOT i.withdrawn
GROUP BY 1, 2
ORDER BY 3 DESC
LIMIT 40;
```

**How many items the filter will match** — the number to know before running `doi-organiser`:

```sql
SELECT count(DISTINCT i.uuid) AS doctoral_theses
FROM item i
JOIN metadatavalue mv           ON mv.dspace_object_id    = i.uuid
JOIN metadatafieldregistry mfr  ON mfr.metadata_field_id  = mv.metadata_field_id
JOIN metadataschemaregistry msr ON msr.metadata_schema_id = mfr.metadata_schema_id
WHERE msr.short_id = 'dc' AND mfr.element = 'type' AND mfr.qualifier IS NULL
  AND mv.text_value LIKE '%doctoralThesis'
  AND i.in_archive AND NOT i.withdrawn;
```

That count is an upper bound: the filter additionally requires at least one bitstream and no
existing DOI, so `doi-organiser -l` will report the same number or fewer.

**Whether the dc.type.uhtype safety net earns its place.** `dc.type` is repeatable, so this has
to test each item for *any* matching value rather than collapsing its values into one:

```sql
SELECT t.dc_says_thesis, t.uh_says_thesis, count(*) AS items
FROM (
  SELECT i.uuid,
         bool_or(mfr.qualifier IS NULL    AND mv.text_value LIKE '%doctoralThesis') AS dc_says_thesis,
         bool_or(mfr.qualifier = 'uhtype' AND mv.text_value = 'Doctoral Thesis')    AS uh_says_thesis
  FROM item i
  JOIN metadatavalue mv           ON mv.dspace_object_id    = i.uuid
  JOIN metadatafieldregistry mfr  ON mfr.metadata_field_id  = mv.metadata_field_id
  JOIN metadataschemaregistry msr ON msr.metadata_schema_id = mfr.metadata_schema_id
  WHERE msr.short_id = 'dc' AND mfr.element = 'type'
    AND i.in_archive AND NOT i.withdrawn
  GROUP BY i.uuid
) t
GROUP BY 1, 2
ORDER BY 3 DESC;
```

### Measured, 2026-09-08

| | items |
|---|---|
| archived, non withdrawn, with any `dc.type*` | 26,175 |
| both fields say doctoral thesis | 1,331 |
| only `dc.type.uhtype` says so | **5** |
| only `dc.type` says so | 0 |
| **candidates for a DOI** | **1,336** |

Two things follow from these numbers.

**The `dc.type.uhtype` safety net stays.** Five items carry `Doctoral Thesis` with no matching
`dc.type`; without the condition they would silently never get a DOI. Before the first run, look
at what those five actually are — their `dc.type` is what drives `resourceTypeGeneral`:

```sql
SELECT i.uuid,
       string_agg(DISTINCT CASE WHEN mfr.qualifier IS NULL    THEN mv.text_value END, ' | ') AS dc_type,
       string_agg(DISTINCT CASE WHEN mfr.qualifier = 'uhtype' THEN mv.text_value END, ' | ') AS uhtype
FROM item i
JOIN metadatavalue mv           ON mv.dspace_object_id    = i.uuid
JOIN metadatafieldregistry mfr  ON mfr.metadata_field_id  = mv.metadata_field_id
JOIN metadataschemaregistry msr ON msr.metadata_schema_id = mfr.metadata_schema_id
WHERE msr.short_id = 'dc' AND mfr.element = 'type'
  AND i.in_archive AND NOT i.withdrawn
GROUP BY i.uuid
HAVING bool_or(mfr.qualifier = 'uhtype' AND mv.text_value = 'Doctoral Thesis')
   AND NOT bool_or(mfr.qualifier IS NULL AND mv.text_value LIKE '%doctoralThesis');
```

**Two items hold a `dc.type` that ends in `doctoralThesis` but is not the usual string.** 1,331
items match the pattern while `info:eu-repo/semantics/doctoralThesis` occurs only 1,329 times.
Whatever those two values are, they are not in
`mapConverter-ucThesisDataciteResourceTypes.properties`, so those items would be registered as
`Text` instead of `Dissertation`. Find them and add them to the converter:

```sql
SELECT mv.text_value, count(*) AS occurrences
FROM item i
JOIN metadatavalue mv           ON mv.dspace_object_id    = i.uuid
JOIN metadatafieldregistry mfr  ON mfr.metadata_field_id  = mv.metadata_field_id
JOIN metadataschemaregistry msr ON msr.metadata_schema_id = mfr.metadata_schema_id
WHERE msr.short_id = 'dc' AND mfr.element = 'type' AND mfr.qualifier IS NULL
  AND mv.text_value LIKE '%doctoralThesis'
  AND mv.text_value <> 'info:eu-repo/semantics/doctoralThesis'
  AND i.in_archive AND NOT i.withdrawn
GROUP BY 1;
```

The same gap exists on master theses (1,063 `Master Thesis` against 917
`info:eu-repo/semantics/masterThesis`), so the two type fields disagreeing is a known trait of
this data rather than a one-off. Worth remembering if the DOI filter is ever widened.

Then see how many items the filter will actually match, before it can act:

```bash
docker exec -it dspace /dspace/bin/dspace doi-organiser -l   # list pending DOIs
```

## The shape of the DOIs

`identifier.doi.namespaceseparator = gnosis/`, after the repository name, so every DOI reads

```
10.82357/gnosis/<id>
```

This is baked into every DOI ever minted and cannot be changed afterwards. Changing it later
would not rewrite the DOIs already registered, it would only make new ones inconsistent with the
old ones.

The namespace is applied by `defaultDoiGenerationStrategy` in `identifier-service.xml`. That bean
is not optional decoration: `DoiGenerationStrategy.getApplicableStrategy()` returns `null` when no
strategy of type `DEFAULT` is registered, and `VersionedDOIIdentifierProvider.getBareDOI()`
dereferences the result while assembling the DOI. Without it, minting fails with a
`NullPointerException`. Its own `filter` is `always_true_filter` and decides which *namespace*
applies, not which items get a DOI — that is the provider's `filter` property.

## Controlled rollout

Nothing is sent to DataCite at the moment an item is installed: the provider only records the DOI
in the `doi` table with a pending status. `doi-organiser` is what talks to DataCite, so keeping it
off cron means nothing leaves the building until you run it.

```bash
# 1. See what is queued, and check a single record first
docker exec -it dspace /dspace/bin/dspace doi-organiser -l

# 2. Register one DOI and verify it in DataCite Fabrica before going further
docker exec -it dspace /dspace/bin/dspace doi-organiser -r -d <doi>

# 3. Once that record looks right, work through the rest
docker exec -it dspace /dspace/bin/dspace doi-organiser -r
```

To see the exact XML for an item before anything is sent, download the DataCite dissemination of
that item from the REST API:

```
/server/api/core/items/<uuid>/crosswalk?type=publication-datacite-xml
```

Note this returns the **generic** publication crosswalk, not the UC one; use it to sanity check the
metadata, and read `uc-publication-datacite-xml.template` for the differences.

Once a batch has gone through cleanly, add the daily job:

```
0 3 * * *  docker exec dspace /dspace/bin/dspace doi-organiser -q
```

## Rolling back

A DOI that has been registered with DataCite in production cannot be deleted, only marked
inactive. Before the first production run, make sure the metadata is right — a wrong
`publicationYear` or a missing creator is permanent in the DOI's history.

To stop minting new DOIs, remove the `filter` reference or point it at a filter that returns
false; existing DOIs are unaffected.
