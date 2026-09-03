# Express Module Contract

This document defines the data contract this Express Module implements. The module is
a brand-safety classifier, but the contract shape is the same for any lookup-style
Express Module: map a key from the bid request to a cached ALLOW/DROP decision.

## Lookup key

RTB Fabric extracts a lookup key from each bid request using a JSONPath expression.
This module keys on the request's site domain:

```
$.site.domain
```

The key must be registered with the module so the runtime knows which field to look
up in the cache. The JSONPath form matters — a bare field name (e.g. `domain`) will
not resolve; it must be the full path (`$.site.domain`).

## Input dataset

A TAB-separated file. First column is the domain; second column is a comma-separated
list of GARM/IAB Content Taxonomy 3.0 category IDs.

```
domain<TAB>categories
example.com<TAB>Rm3SiT,396
```

## Output lookup table

The classifier writes a TAB-separated table with three columns:

```
domain<TAB>action<TAB>metadata
example.com<TAB>DROP<TAB>{"categories":["Rm3SiT"],"blocked":["Rm3SiT"]}
```

- `action` — `ALLOW` or `DROP`.
- `metadata` — compact JSON. `categories` lists every category the domain carries;
  `blocked` lists the subset that triggered a DROP (empty for ALLOW).

### File naming gotcha

The output object must have a **`.csv` extension** (the module catalog rejects other
extensions), but its **content is TAB-delimited** — RTB Fabric's hydrator splits on
tab. The extension is cosmetic; do not comma-separate the content.

## Runtime behavior

RTB Fabric owns and operates the runtime evaluation of a module on the bid path;
refer to the official RTB Fabric documentation for its runtime and availability
behavior. From the module author's perspective:

- **Modes:** the module can enrich (attach category metadata as advisory data the DSP
  can act on) or filter (drop unsafe domains before the bidder). In filter mode, a
  dropped request is gated before the bidder, so no enrichment is observable on it.
- **Pre-computed decisions:** all ALLOW/DROP decisions are computed offline during
  hydration; the runtime does a single cache lookup per request and runs no module
  author code on the bid path.

## Registration

Registering a custom module in the RTB Fabric catalog is done in coordination with
your AWS account team. Attaching an already-registered module to a link's flow uses
the public `UpdateLinkModuleFlow` API (see `scripts/attach-module.sh`).
