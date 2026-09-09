# 6. MultiNet-R attribute mapping, and what is assumed

## Context

MN-R describes its road network across three tables: `MNR_Netw_Route_Link` for
routing attributes, `MNR_Netw_Geo_Link` for geometry and length, `MNR_Junction` for
nodes. Its enumerations do not line up with OpenLR's, and several values have no
OpenLR equivalent at all.

An existing script, `openlr_customers/avro/create_tables.sql`, already performed
this mapping by loading the three tables into PostgreSQL. It is the closest thing
to a specification of the intended semantics, and it carries a `(?)` comment on one
of its own decisions.

## Decision

The mappings are ported from that script rather than reinvented, with its
enumeration labels recorded from the MN-R specification so a reader can check them.
Three points needed a judgment.

**`SIMPLE_TRAFFIC_DIRECTION` 2 and 3 swap into `flowdir`.** MN-R's positive
direction is relative to the digitised direction. Verified rather than assumed: on
20,000 sampled links the geometry ran `JUNCTION_ID_FROM` → `JUNCTION_ID_TO` in
20,000 cases and reversed in none. So MN-R 2 is `flowdir` 3 and MN-R 3 is
`flowdir` 2 (ADR 0002).

**Value 9, "Not Present", is read as two-way.** This is the reference script's
choice and its `(?)`. About a fifth of Dutch links carry it. Most are walkways, but
roughly 141,000 are `FORM_OF_WAY` 3, Single Carriageway — genuine roads. The
attribute is defined as the allowed flow *for passenger cars*, and OpenLR matches
geometry rather than car routing, so a missing car direction need not mean
impassable. It remains an assumption, and it errs toward permitting traversal.

**Only junctions a kept link references are emitted.** The reference loaded every
`MNR_Junction` row. With `BACK_ROAD` filtered that leaves junctions no road points
at, and `findRoadsNear` inner-joins the table, so they can never be used: 1,923,459
of 2,079,442 for the Netherlands.

Two inclusion filters are exposed rather than settled, because the right answer
depends on where the OpenLR codes being decoded come from:

* `--include-back-roads` keeps `BACK_ROAD <> 0`. The default drops 8.6% of Dutch
  links — back roads, destination roads, driveways — as the reference did.
* `--drivable-only` drops walkways, stairs, pedestrian zones and parking. The
  default keeps them as FOW 7 (OTHER), which is what that value is for.

## Consequences

A network converted with the defaults reproduces the reference script's road set
exactly — 2,400,680 links for the Netherlands — so results can be compared against
the PostgreSQL-based pipeline it replaces.

The direction-9 assumption is the one to revisit if decoding shows paths running
the wrong way along minor roads. It cannot be settled from the data: the attribute
is absent, and MN-R offers no other per-link car-direction field. Settling it needs
either a rule from the map's authors or an experiment against known-good OpenLR
codes on affected links.

Keeping pedestrian and parking geometry means about 442,000 extra candidates in a
Dutch network. They carry FRC7 and FOW 7, so a vehicle-oriented decode profile
rates them poorly, but they are still searched.

Merging a distribution into one network means rows repeat where extracts overlap at
borders: the six-country EUR sample collapsed 321 roads and 1,158 junctions. Since
a repeat carries the same `FEAT_ID` it is byte-identical, so collapsing is safe --
but it has to happen at the end, in one aggregate, rather than through a unique
constraint maintained per insert. Maintaining that constraint while accumulating
cost more than the entire rest of the conversion.
