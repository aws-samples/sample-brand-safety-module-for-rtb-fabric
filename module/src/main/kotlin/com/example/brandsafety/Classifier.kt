package com.example.brandsafety

/**
 * A single domain and the content-taxonomy categories it carries.
 * Categories are GARM-aligned IAB Content Taxonomy 3.0 IDs.
 */
data class DomainEntry(
    val domain: String,
    val categories: List<String>
)

enum class Action {
    ALLOW,
    DROP;

    override fun toString(): String = name
}

/**
 * The output record written to the lookup table that the RTB Fabric cache module
 * hydrates. `metadata` is a compact JSON blob the runtime module can surface to the
 * bidder (e.g. why a request was dropped, or enrichment for allowed requests).
 */
data class Classification(
    val domain: String,
    val action: Action,
    val metadata: String
)

/**
 * Classifies a domain as ALLOW or DROP and attaches JSON metadata.
 *
 * A domain is dropped if ANY of its categories is on the blocklist. The metadata
 * records every category the domain carries and the subset that triggered the DROP,
 * so downstream consumers know *why* a decision was made.
 */
fun classify(entry: DomainEntry, config: ClassificationConfig): Classification {
    val blocked = entry.categories.filter { it in config.blockedCategories }
    val action = if (blocked.isNotEmpty()) Action.DROP else Action.ALLOW

    val metadata =
        """{"categories":${jsonArray(entry.categories)},"blocked":${jsonArray(blocked)}}"""

    return Classification(
        domain = entry.domain,
        action = action,
        metadata = metadata
    )
}

// Renders a list of category IDs as a compact JSON string array, e.g. ["a","b"].
// Each value is JSON-escaped so a category ID containing a quote or backslash cannot
// break out of its string and alter the structure of the metadata document.
private fun jsonArray(items: List<String>): String =
    items.joinToString(separator = ",", prefix = "[", postfix = "]") { "\"${escapeJson(it)}\"" }

// Minimal JSON string escaping for the characters that would otherwise corrupt the
// compact metadata JSON (quote, backslash, and control characters).
private fun escapeJson(value: String): String {
    val sb = StringBuilder(value.length)
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.toString()
}

fun classifyAll(domains: List<DomainEntry>, config: ClassificationConfig): List<Classification> {
    return domains.map { entry -> classify(entry, config) }
}
