# Threat Model — Brand Safety Express Module for RTB Fabric

## Scope

This threat model covers the **ISV-side pipeline** of a brand-safety Express Module
reference sample: an AWS Lambda classifier, S3 storage, an IAM execution role, and an
EventBridge schedule, deployed by a customer into their own AWS account via CloudFormation.

Out of scope: RTB Fabric's platform-owned runtime (cache, hydrator, broker), which this
sample does not build or operate. The terminal per-request miss→bid decision runs in that
runtime; this document reasons about the ISV side that produces the lookup table.

## System overview

- **Trigger:** EventBridge scheduled rule (hourly) — the only invocation path.
- **Compute:** Lambda (Java 17) reads a domain dataset + blocklist config from S3,
  classifies each domain ALLOW/DROP, writes a lookup table back to S3.
- **Storage:** **two** S3 buckets — (1) the **data bucket** holding the inputs
  (`domains.tsv`, `default.toml`) and the output (`classifications.csv`), and (2) a
  dedicated **server-access-logs bucket** that receives access logs for the data bucket.
  Both use SSE-S3, block all public access, enforce TLS, and are versioned.
- **Identity:** one least-privilege IAM execution role (object-scoped read of the two
  inputs, write of the single output).
- **Data:** synthetic sample domains + public GARM/IAB category IDs. No customer data,
  PII, secrets, or confidential data.
- **Handoff:** RTB Fabric's platform hydrator reads the output object via an optional,
  read-only, least-privilege cross-account S3 bucket policy scoped to that one object.

## Trust boundaries

1. **S3 data bucket** — boundary between this service (writes) and the RTB Fabric
   platform (reads). The only cross-account exposure.
2. **The deployer's AWS account** — everything runs within one customer-owned account;
   no AWS-operated environment, no AWS employee access.
3. **Input provenance** — who may write the input objects (`domains.tsv`,
   `default.toml`). The Lambda's role cannot (its `PutObject` is scoped to the output),
   but any *other* account principal with `s3:PutObject` can, unless the deployer
   restricts it. The inputs decide every ALLOW/DROP.
4. **The Lambda deployment artifact** — `ClassifierFunction.Code` points at a
   deployer-managed code bucket/key *outside* the stack. Whoever can write that object
   controls the code the function runs.

## Assets

- **Output lookup table (`classifications.csv`)** — integrity matters (a wrong entry
  changes a filtering decision); not confidential (synthetic, publicly releasable).
- **Input objects (`domains.tsv`, `default.toml`)** — integrity-critical: they determine
  every ALLOW/DROP decision.
- **The Lambda deployment package** — integrity-critical: it is the code that produces
  the table.
- **The Lambda execution role** — must stay least-privilege.

## Severity note

Rows are rated by **impact**, not likelihood. Likelihood is low across the board
(single account, synthetic data, no public surface), but the *impact* of a brand-safety
control failing open is high: an ad that should have been dropped is served — e.g. adult
content on child-directed inventory, which carries COPPA/GDPR-K exposure for the
advertiser and reputational damage for the platform. Where likelihood and impact
diverge, the rating reflects impact.

## Threats considered (STRIDE)

| Threat | Impact | Analysis / mitigation |
|---|---|---|
| **Spoofing** | Low | No auth surface, no callers to spoof. Lambda invoked only by an EventBridge rule in the same account; no external invoke permission. |
| **Tampering — output** | Med–High | Output-table integrity: bucket blocks public access, TLS enforced, versioning enabled (recoverable), write access limited to the Lambda's object-scoped role. Cross-account grant is **read-only**, single-object, role-only principal. |
| **Tampering — inputs** | High | The inputs decide every classification. The template stops the *Lambda* from writing them (its `PutObject` is scoped to `classifications.csv` only), but `LookupDataBucketPolicy` does **not** restrict which other principals may write the input keys, and the deployer uploads them by hand — so any account principal with generic `s3:PutObject` (a shared CI/dev role, a data-feed job, or compromised creds) could edit them. The fail-closed guards do **not** catch a *targeted* edit: removing one blocklist category or blanking one domain's categories still leaves the blocklist non-empty and most domains DROP, so `dropped > 0` passes and the change publishes. **Latent in this sample** (single trusted uploader, synthetic data); an adopter wiring uploads into a pipeline widens the writer set. *Adopter mitigation:* a writer-scoped bucket policy / dedicated upload role, treating inputs as provenance-controlled. |
| **Repudiation** | Low | Single-account sample. The **template enables S3 server access logging** on the data bucket (to the dedicated logs bucket), and the deployer owns CloudTrail in their account. |
| **Information disclosure** | Low | No sensitive data to disclose — data is synthetic/public. Bucket is private (public access blocked); the only external read is the scoped, read-only cross-account grant to the platform hydrator. |
| **Denial of service / freshness** | Med | No public endpoint; excess invocations are bounded by the EventBridge schedule + the Lambda's reserved concurrency. **Freshness gap:** on repeated input-parse failure the Lambda throws *before* writing, so the previous table is served indefinitely (**stale**), and before the first successful run there is no table at all (a bootstrap miss). The template provisions no error alarm, DLQ/`OnFailure` destination, or output-age check, so a stalled refresh is undetected. *Adopter mitigation:* alarm on the function `Errors` metric (or a DLQ) plus an output-age / last-successful-publish signal. |
| **Elevation of privilege** | Med | IAM role is least-privilege (object-scoped, no wildcards); no untrusted-input execution or deserialization. **Deployment artifact:** the classifier code is supplied via the deployer-managed code bucket, not the stack — whoever can write that object controls execution and could publish an arbitrary decision table. *Mitigation:* keep the code bucket access-restricted + versioned and pin `Code.S3ObjectVersion` (the template exposes an optional `LambdaCodeVersion` parameter for this). |

## Detective controls

This sample ships **preventative controls only**. It emits a CloudWatch log line per run
(classified/DROP counts) but provisions **no alarm** on classifier failure, anomalous
DROP count, or table staleness. A prevention that silently fails leaves nothing to catch
it. This is called out as an explicit gap: for a production feed, add at least one
detective control (error alarm / DROP-count anomaly / output-age). See the adopter
checklist below.

## Key security properties

- **Least privilege:** execution role object-scoped — reads only the two inputs, writes
  only the output; cannot overwrite its own trusted inputs.
- **No public exposure:** buckets block all public access; no API/endpoint/function URL.
- **Encryption:** SSE-S3 at rest; SSL/TLS enforced in transit on both buckets.
- **Access logging:** S3 server access logging enabled on the data bucket.
- **Scoped cross-account access:** read-only, single object, role-only principal
  (account-root ARNs rejected by the parameter's `AllowedPattern`).
- **Input validation / fail-closed:** the dataset parser rejects malformed rows (missing
  or empty categories, non-hostname domains), and aborts on a mostly-malformed file, an
  empty blocklist, or a would-be all-ALLOW table (`requireBlockingTable`).
- **Output integrity:** metadata JSON is escaped and the domain key is validated to a
  plain hostname, so neither can inject a tab/newline into the TAB-delimited table.
- **Dependencies:** all third-party libs are OSI-approved (Apache-2.0/EPL-2.0/MIT), with
  direct versions pinned via the AWS SDK BOM. *Note:* there is no Gradle
  dependency-verification / checksum lockfile, and the BOM selects transitive versions —
  so "pinned" applies to direct dependencies only; adopters wanting supply-chain
  assurance should add dependency verification.

## Coverage model & residual risks (accepted for a sample; hardening for a real feed)

The classifier is an **exact-match denylist over a lightly-normalized domain key**,
matched by exact set-membership against an explicitly-enumerated category list. Two
honest consequences an adopter must understand:

- **Unlisted or variant domains are not filtered.** Coverage is exactly the verbatim
  keys in the dataset (lowercased, trailing-dot-stripped). `www.badsite.com`,
  `m.badsite.com`, an eTLD+1 or punycode variant, or any domain not yet added, produces
  no DROP row and is **ALLOWed by default**. For a brand-safety control, match coverage
  *is* a security property — not merely data quality. The publish-time fail-closed guards
  only prevent an *all-ALLOW* table; they say nothing about the far larger set of domains
  simply absent from it.
- **Category matching is flat.** A blocked ID blocks only that exact ID — listing a
  parent (e.g. Sensitive Topics `v9i3On`) does **not** auto-block its IAB-taxonomy
  children; each ID must be enumerated in `default.toml`.

### Adopter hardening checklist (moving from this synthetic sample to a real feed)

- [ ] Key on the registrable domain (eTLD+1) with subdomain / `www.` / punycode / port
      normalization — or pin and test a normalization contract shared with the runtime.
- [ ] Resolve categories against the taxonomy tree (a blocked node blocks its
      descendants), or keep enumerating every ID explicitly.
- [ ] Restrict who may write the input objects (writer-scoped bucket policy or a
      dedicated upload role); treat the inputs as provenance-controlled.
- [ ] Keep the Lambda code bucket access-restricted + versioned and pin
      `Code.S3ObjectVersion` (`LambdaCodeVersion` parameter).
- [ ] Add a detective control: alarm on the classifier `Errors` metric / a DROP-count
      anomaly / output age; consider a DLQ or `OnFailure` destination.
- [ ] Replace the `requireBlockingTable` `dropped > 0` guard with a tolerance-band
      comparison against the prior run's DROP set (the code's own NOTE anticipates this).
- [ ] Add Gradle dependency verification / a checksum lockfile for supply-chain assurance.
- [ ] Wire a quick-disable / Andon path — the EventBridge rule can be disabled to halt
      refreshes if the published output is found to be wrong.

### Accepted for this sample

- A wrong classification entry yields a wrong ALLOW/DROP — a data-quality concern; the
  sample dataset is synthetic and publicly releasable.
- Shared responsibility: deployers own their account-level controls (CloudTrail, KMS
  CMKs if desired, monitoring, alarms) — standard for sample code deployed in a
  customer account.
