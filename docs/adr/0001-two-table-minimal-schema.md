# 1. A minimal two-table schema, not a routing-grade map

## Context

OpenLR map matching needs very little from a map: per segment a geometry, a length,
a functional road class, a form of way, a direction of travel, and the identity of
the junctions at each end. A full navigation map carries far more — names, speeds,
turn restrictions, lane detail, administrative hierarchy — none of which the decoder
consults.

Customers' networks arrive in every conceivable format. Requiring them to be
converted into a rich schema would make adoption the hard part.

## Decision

Two tables: `local.roads` and `local.intersections`, with the minimum OpenLR needs
and a free-text `meta` column per row referencing the customer's own identifier.
Anything richer stays in the customer's system, reachable through `meta`.

## Consequences

Loading a network is a small conversion rather than a mapping exercise, and the tool
stays independent of any particular source format.

The cost is that capabilities requiring more data are unavailable and cannot be added
without a schema change. `hasTurnRestrictions()` returns false because the schema has
nowhere to record them, so the decoder may match a path through a turn that is
prohibited in reality. That is a deliberate accuracy-for-simplicity trade, not an
oversight.

The schema also assumes segments are already split at junctions: endpoints are the
only junctions, so a road passing through an intersection without a row boundary
leaves the graph disconnected there. Sources that do not satisfy this must be split
before loading.
