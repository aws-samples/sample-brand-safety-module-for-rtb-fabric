package com.example.brandsafety

import java.io.File

// A valid lookup key is a plain lowercase hostname: letters, digits, dots, hyphens.
// Anything else (an embedded tab/newline/space, quote, or control character) marks the
// row malformed — such a value is both an invalid domain and would corrupt the
// TAB-delimited output table the hydrator parses.
private val VALID_DOMAIN = Regex("^[a-z0-9.-]+$")

/**
 * Loads the domain dataset: a TAB-separated file mapping each domain to a
 * comma-separated list of its GARM/IAB categories.
 *
 *   domain<TAB>cat1,cat2,cat3
 */
fun loadDomains(path: String): List<DomainEntry> = parseDomains(File(path).readLines())

fun parseDomains(lines: List<String>): List<DomainEntry> {
    val dataRows = lines.drop(1).filter { it.isNotBlank() } // skip header + blank lines

    val entries = dataRows.mapNotNull { line ->
        val parts = line.split("\t")
        if (parts.size < 2) return@mapNotNull null
        // Normalize the domain to a lowercase, trailing-dot-free key so lookups are
        // consistent with how the runtime extracts site.domain.
        val domain = parts[0].trim().lowercase().trimEnd('.')
        val categories = parts[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        // Treat the row as malformed (drop it, and let the fail-closed guard below count
        // it) rather than silently producing an ALLOW when:
        //   - the domain is blank or is not a plain hostname (a stray space/tab/newline
        //     or other character would be an invalid key and corrupt the TAB output), or
        //   - the categories column is present but empty, e.g. `evil.com\t`: an empty
        //     category set classifies ALLOW, so accepting it would let a domain through
        //     by omission. Fail closed instead of trusting an empty column.
        if (domain.isBlank() || !VALID_DOMAIN.matches(domain) || categories.isEmpty()) {
            null
        } else {
            DomainEntry(domain = domain, categories = categories)
        }
    }

    // Fail closed: if the input has data rows but (nearly) all of them are malformed
    // — e.g. tabs mangled into spaces by a spreadsheet round-trip, or blanked category
    // columns — bail out instead of silently publishing an empty/degraded lookup table
    // that would let every domain through as ALLOW.
    val malformed = dataRows.size - entries.size
    require(dataRows.isEmpty() || malformed <= dataRows.size / 2) {
        "Refusing to publish: $malformed of ${dataRows.size} data rows were malformed " +
            "(missing/empty categories, or an invalid domain). The input file is likely " +
            "corrupt (e.g. tabs converted to spaces); aborting rather than overwriting " +
            "the lookup table with degraded data."
    }

    return entries
}

/**
 * Renders the classified rows as the lookup table RTB Fabric hydrates.
 *
 * NOTE: the output is TAB-delimited. RTB Fabric's hydrator splits on tab, but the
 * catalog requires the object to have a `.csv` extension — so this content is written
 * to a file named `*.csv` despite being tab-separated. See docs/module-contract.md.
 */
fun generateOutputTsv(classifications: List<Classification>): String {
    val sb = StringBuilder()
    sb.appendLine("domain\taction\tmetadata")
    for (c in classifications) {
        sb.appendLine("${c.domain}\t${c.action}\t${c.metadata}")
    }
    return sb.toString()
}
