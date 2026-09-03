# Brand Safety Express Module for RTB Fabric

A minimal, runnable reference implementation of an RTB Fabric Express Module, meant to
show how a working module is built end to end. This one is a **brand-safety
classifier**: it labels site domains against the GARM-aligned IAB Content Taxonomy and
either drops requests for unsafe domains or enriches them with category metadata.

It shows the full shape: classify input data, publish a lookup table to S3, and let
RTB Fabric hydrate it into a cache that the runtime module consults on the bid path.

> **Note:** registering a custom module in the RTB Fabric catalog is done in
> coordination with your AWS account team. Attaching an already-registered module to a
> link uses the public `UpdateLinkModuleFlow` API. See
> [docs/module-contract.md](docs/module-contract.md).

## Layout

```
module/       Kotlin classifier + Lambda handler + tests (standalone Gradle)
data/         Domain dataset (domains.tsv) and blocklist config (default.toml)
infra-cfn/    CloudFormation template for the ISV-side infra (S3, Lambda, IAM, EventBridge)
docs/         Module contract and architecture
scripts/      Attach a registered module to a link (public API)
```

## Quick start

### 1. Run the classifier locally (no AWS)

```bash
cd module
./gradlew run --args="../data/default.toml ../data/domains.tsv output/classifications.csv"
./gradlew test
```

Requires a JDK (17+). `./gradlew` downloads the correct Gradle version automatically.

### 2. Build the Lambda deployment package

```bash
cd module
./gradlew fatJar   # -> build/libs/reference-express-module-all.jar
```

### 3. Deploy the ISV-side infrastructure (CloudFormation)

The infrastructure is a plain CloudFormation template (`infra-cfn/brand-safety-module.yaml`)
— no CDK or Node toolchain required. You supply the Lambda JAR via an S3 location.

```bash
# a) Upload the Lambda deployment package to an S3 bucket you own
aws s3 cp module/build/libs/reference-express-module-all.jar \
  s3://<your-code-bucket>/reference-express-module-all.jar

# b) Deploy the stack
aws cloudformation deploy \
  --template-file infra-cfn/brand-safety-module.yaml \
  --stack-name brand-safety-module \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides \
      LambdaCodeBucket=<your-code-bucket> \
      LambdaCodeKey=reference-express-module-all.jar
      # Optionally add: RtbFabricHydratorPrincipal=<principal from your account team>
```

This creates the S3 data bucket (encrypted, public access blocked, TLS-enforced,
versioned, with server access logging to a dedicated logs bucket), the classifier
Lambda, its least-privilege IAM role, and an hourly refresh schedule. Then upload
`domains.tsv` and `default.toml` to the data bucket (see the `DataBucketName` stack
output) so the Lambda can read them.

#### Cleanup

The S3 buckets use `DeletionPolicy: Retain` and have versioning enabled, so deleting
the stack does **not** remove them — this protects your data from accidental loss. To
fully tear down, delete the stack, then empty and delete the buckets manually. Because
versioning is on, you must delete all object *versions* (not just current objects)
before the bucket can be removed:

```bash
aws cloudformation delete-stack --stack-name brand-safety-module
# then, for each retained bucket, delete all versions before deleting the bucket
```

### 4. Attach the module to a link

Once your module is registered in the catalog, attach it to a link's flow:

```bash
cd scripts
GATEWAY_ID=... LINK_ID=... ./attach-module.sh
```

## How it works

See [docs/architecture.md](docs/architecture.md). In short: a scheduled Lambda
classifies the domain dataset and writes a lookup table to S3; RTB Fabric reads that
table into its cache; the runtime module applies the ALLOW/DROP decision per bid
request (DROP → no-bid).

## Adapting it

The classification logic lives in
`module/src/main/kotlin/com/example/brandsafety/Classifier.kt`. Swap in your own
decision logic while keeping the input/output contract in
[docs/module-contract.md](docs/module-contract.md) so RTB Fabric can hydrate and look
up your data unchanged.

Before using this against real traffic, read the coverage limits and **adopter
hardening checklist** in [docs/THREAT-MODEL.md](docs/THREAT-MODEL.md). In particular,
this is an **exact-match denylist**: a domain not listed verbatim — including
subdomains, `www.`, and punycode/IDN variants — is **not** filtered, and a blocked
parent category does not implicitly block its child categories.

## Security & disclaimer

This is **sample code, intended for non-production use** to demonstrate how an Express
Module is built. Work with your own security and legal teams to meet your
organizational security, regulatory, and compliance requirements before deploying it,
and review and adapt the IAM policies, encryption settings, and network configuration
for your environment.

## License

MIT-0. See [LICENSE](LICENSE).
