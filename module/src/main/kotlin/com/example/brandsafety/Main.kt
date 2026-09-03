package com.example.brandsafety

import java.io.File

/**
 * Local CLI entry point — run the classifier against local files with no AWS involved.
 * Useful for developing and testing the classification logic in isolation.
 *
 *   ./gradlew run --args="../data/default.toml ../data/domains.tsv output/classifications.csv"
 */
fun main(args: Array<String>) {
    val configPath = args.getOrElse(0) { "data/default.toml" }
    val domainsPath = args.getOrElse(1) { "data/domains.tsv" }
    val outputPath = args.getOrElse(2) { "output/classifications.csv" }

    val config = loadConfig(configPath)
    println("Loaded ${config.blockedCategories.size} blocked categories")

    val domains = loadDomains(domainsPath)
    println("Loaded ${domains.size} domains")

    val classifications = classifyAll(domains, config)
    val dropped = classifications.count { it.action == Action.DROP }
    val allowed = classifications.count { it.action == Action.ALLOW }
    println("Results: $dropped DROP, $allowed ALLOW")

    // Same fail-closed outcome guard as the Lambda path (see requireBlockingTable):
    // refuse to write a table that blocks nothing, so neither entry point can silently
    // publish an all-ALLOW result. The counts above are printed first, then it aborts.
    requireBlockingTable(classifications)

    val outputFile = File(outputPath)
    outputFile.parentFile?.mkdirs()
    outputFile.writeText(generateOutputTsv(classifications))
    println("Output written to $outputPath")
}
