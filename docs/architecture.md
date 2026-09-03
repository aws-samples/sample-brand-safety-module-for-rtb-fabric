# Architecture

This brand-safety Express Module has two halves. **This repo builds only the ISV
side.** RTB Fabric owns the runtime side; the S3 bucket is the handoff boundary
between them.

```
   ISV SIDE (this repo)                                RTB FABRIC (platform-owned)
   ─────────────────────────────────────────           ────────────────────────────────
   EventBridge     Lambda            S3 bucket           Hydrator    Cache      Broker
   Scheduler  ──▶  classifier  ──▶   classifications ──▶ reads S3 ─▶ Cache ─▶  runtime
   (hourly)        (this code)       .csv (action+meta)  object              module runs
                                                                                   │
                                                                                   ▼
                        bid request ─▶ gateway ─▶ link (module flow) ─▶ bidder
                                    (module attached via UpdateLinkModuleFlow)
```

## Data flow

1. **EventBridge** triggers the Lambda on a schedule (hourly here).
2. **Lambda** reads the domain dataset and blocklist config from S3, classifies every
   domain, and writes the lookup table (`classifications.csv`) back to S3.
3. **RTB Fabric's hydrator** reads the output object and loads it into the cache.
   This is granted via a cross-account S3 read policy (see the CloudFormation template).
4. At bid time, the **runtime module** in the broker looks up the request's domain in
   the cache and applies the ALLOW/DROP decision inline (DROP → no-bid).

## What this repo provides vs. does not

| Provided (ISV side)                          | Not provided (platform side)             |
| -------------------------------------------- | ---------------------------------------- |
| Classifier logic + tests                     | The cache / hydrator                     |
| Lambda handler (S3 in/out)                   | The runtime module execution             |
| CloudFormation for S3, Lambda, IAM, EventBridge | Module registration in the catalog    |
| `UpdateLinkModuleFlow` attach script         | Gateway/link provisioning internals      |
| The data contract (docs/module-contract.md)  |                                          |
