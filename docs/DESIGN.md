# Brand Safety Express Module for RTB Fabric — Design

## Overview

This is a reference implementation of an RTB Fabric **Express Module**: a brand-safety
classifier that labels site domains against the IAB/GARM content taxonomy and publishes
a cache lookup table used for bid-request filtering. It is a sample/reference intended
for public release (aws-samples), demonstrating how to build a working Express Module.

The service being built is the **ISV-side pipeline only**: an offline classifier plus
the AWS infrastructure that produces and publishes a lookup table to S3. RTB Fabric's
runtime cache/broker is platform-owned and out of scope for this review.

## Components (what this service creates)

All resources deploy into a single account/region (us-east-1), provisioned via a plain
AWS CloudFormation template (no CDK/Node toolchain):

- **AWS Lambda** — the classifier. Reads a domain dataset and a blocklist config,
  classifies each domain ALLOW/DROP, writes the lookup table back to S3. Java 17.
- **Amazon S3** — one bucket holding the input dataset, the blocklist config, and the
  generated output lookup table. Encrypted (S3-managed), public access blocked, SSL
  enforced, versioned.
- **AWS IAM** — a least-privilege Lambda execution role (read/write to the one bucket).
- **Amazon EventBridge** — a scheduled rule that triggers the classifier hourly to
  refresh the lookup table.

There is no API, no database, no VPC/network resource, no container, no GenAI/ML, and
no authentication/authorization surface. Nothing runs on the live bid path.

## Data flow

1. EventBridge triggers the Lambda on a schedule (hourly).
2. The Lambda reads the domain dataset (`domains.tsv`) and blocklist config
   (`default.toml`) from S3, classifies every domain, and writes the lookup table
   (`classifications.csv`, TAB-delimited) back to S3.
3. RTB Fabric's platform hydrator reads the output object from S3 into its cache. This
   is granted via an optional, least-privilege, read-only cross-account S3 bucket
   policy scoped to the single output object.
4. At bid time, the platform's runtime module looks up the request's domain in the
   cache and applies the ALLOW/DROP decision (DROP → no-bid). This runtime path is
   platform-owned and not part of this service.

## Data classification

Only synthetic, public, non-sensitive sample data:
- A sample dataset of example domains mapped to GARM/IAB Content Taxonomy 3.0 category
  IDs (a public industry standard).
- A blocklist config of category IDs.
- No customer data, no PII, no confidential or production data. The entire artifact is
  intended to be publicly released.

## Data schema

Input dataset (TAB-separated):
```
domain<TAB>categories        e.g.  example.com<TAB>Rm3SiT,396
```

Output lookup table (TAB-separated, `.csv` extension required by the catalog):
```
domain<TAB>action<TAB>metadata
example.com<TAB>DROP<TAB>{"categories":["Rm3SiT"],"blocked":["Rm3SiT"]}
```
- `action` — ALLOW or DROP (DROP if any category is on the blocklist).
- `metadata` — compact JSON: all categories carried, plus the blocked subset.

## Dependencies

Third-party open-source components, fetched from Maven Central at build time, all under
OSI-approved licenses (Apache-2.0, EPL-2.0): Kotlin stdlib, AWS SDK for Java, AWS Lambda
Java core, JUnit (test). Infrastructure is a plain CloudFormation template (no CDK/npm
dependencies).

## Trust boundary

The S3 bucket is the boundary between the ISV side (this service) and the
platform side (RTB Fabric). This service only writes the lookup table; the platform
reads it. This service calls only standard AWS APIs (S3) plus, for attaching an
already-registered module to a link, the public RTB Fabric `UpdateLinkModuleFlow` API.

## Out of scope

- The RTB Fabric runtime cache, hydrator, and broker (platform-owned).
- Custom module registration internals (done in coordination with the account team).
- Any GenAI/LLM functionality (a potential future enhancement, not in this artifact).
