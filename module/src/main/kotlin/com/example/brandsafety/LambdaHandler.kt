package com.example.brandsafety

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest

/**
 * AWS Lambda entry point. On each (scheduled) invocation it:
 *   1. reads the domain dataset and the blocklist config from S3,
 *   2. classifies every domain,
 *   3. writes the resulting lookup table back to S3.
 *
 * RTB Fabric's hydrator then reads the output object and loads it into the cache.
 * This handler only ever calls standard AWS APIs (S3) — it has no dependency on any
 * RTB Fabric internal API.
 *
 * Environment variables:
 *   DATA_BUCKET   - S3 bucket holding domains, config, and output objects
 *   DOMAINS_KEY   - object key of the TAB-separated domain dataset (default: domains.tsv)
 *   CONFIG_KEY    - object key of the blocklist config           (default: default.toml)
 *   OUTPUT_KEY    - object key to write the lookup table to       (default: classifications.csv)
 */
class LambdaHandler : RequestHandler<Map<String, Any>, String> {

    private val s3: S3Client = S3Client.create()

    override fun handleRequest(event: Map<String, Any>, context: Context): String {
        val bucket = env("DATA_BUCKET")
        val domainsKey = System.getenv("DOMAINS_KEY") ?: "domains.tsv"
        val configKey = System.getenv("CONFIG_KEY") ?: "default.toml"
        val outputKey = System.getenv("OUTPUT_KEY") ?: "classifications.csv"

        val config = loadConfig(getObjectLines(bucket, configKey))
        val domains = parseDomains(getObjectLines(bucket, domainsKey))
        val classifications = classifyAll(domains, config)

        val table = generateOutputTsv(classifications)

        // Fail closed BEFORE writing. Every upstream failure mode — an empty/corrupt
        // dataset, a config that matched no category, a blocklist that failed to load —
        // converges on the same outcome: zero domains dropped. Guarding that single
        // outcome catches them all at once, instead of trying to enumerate every
        // malformed-input shape.
        val dropped = requireBlockingTable(classifications)

        s3.putObject(
            PutObjectRequest.builder().bucket(bucket).key(outputKey).build(),
            RequestBody.fromString(table)
        )

        val msg = "Classified ${classifications.size} domains ($dropped DROP) -> s3://$bucket/$outputKey"
        context.logger.log(msg)
        return msg
    }

    private fun getObjectLines(bucket: String, key: String): List<String> =
        s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
            .asUtf8String()
            .lines()

    private fun env(name: String): String =
        System.getenv(name) ?: error("Missing required environment variable: $name")
}

/**
 * Fail-closed outcome guard. A lookup table that blocks nothing means the blocklist or
 * dataset was lost somewhere upstream, so publishing it would silently disable filtering
 * (every domain becomes ALLOW). Throws rather than returning in that case; otherwise
 * returns the DROP count.
 *
 * NOTE: a production module would compare against the previous run's DROP count with a
 * tolerance band rather than a hard `> 0`, so a legitimately low-block refresh doesn't
 * fail the invocation. For this reference sample the simpler invariant is enough.
 */
fun requireBlockingTable(classifications: List<Classification>): Int {
    val dropped = classifications.count { it.action == Action.DROP }
    require(dropped > 0) {
        "Refusing to publish an all-ALLOW table: 0 of ${classifications.size} domains " +
            "classified DROP. The blocklist or dataset was likely lost upstream; aborting " +
            "rather than overwriting the lookup table with a result that blocks nothing."
    }
    return dropped
}
