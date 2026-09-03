package com.example.brandsafety

import java.io.File

/**
 * The set of GARM/IAB category IDs that should cause a DROP. Loaded from a simple
 * TOML-style config so the blocklist can be edited without touching code.
 */
data class ClassificationConfig(
    val blockedCategories: Set<String>
)

fun loadConfig(path: String): ClassificationConfig = loadConfig(File(path).readLines())

// Matches every double-quoted token on a line, e.g. "Rm3SiT".
private val QUOTED = Regex("\"([^\"]+)\"")

// Matches ONLY the `blocked_categories` array key at the start of a line (optional
// leading whitespace). Anchoring to the exact key stops a suffixed key such as
// `blocked_categories_note = "..."` from being treated as the array and smuggling its
// quoted value past the fail-closed guard.
private val BLOCKED_KEY = Regex("^\\s*blocked_categories\\s*=")

/**
 * Parses the blocked-category IDs from a simple TOML config. Category IDs are read
 * from any quoted tokens in the `blocked_categories` array, whether the array is
 * written one-entry-per-line or collapsed onto a single line (e.g. by a TOML
 * formatter) — both forms are valid TOML and must parse identically.
 *
 * Fails closed: an empty blocklist would silently classify every domain as ALLOW and
 * disable the entire control, so an empty result is treated as a configuration error
 * rather than a valid "block nothing" state.
 */
fun loadConfig(lines: List<String>): ClassificationConfig {
    val categories = mutableSetOf<String>()
    // Only collect quoted tokens that appear INSIDE the blocked_categories = [ ... ]
    // array. Tracking the array scope prevents unrelated quoted values elsewhere in
    // the file (e.g. `title = "x"`) from being mistaken for category IDs — which would
    // otherwise defeat the fail-closed guard below.
    var inArray = false

    for (rawLine in lines) {
        // Strip trailing comments so labels after `#` are never parsed as IDs.
        val line = rawLine.substringBefore("#")

        var scan = line
        if (BLOCKED_KEY.containsMatchIn(line)) {
            inArray = true
            scan = line.substringAfter("blocked_categories") // start after the key name
        }

        if (inArray) {
            QUOTED.findAll(scan).forEach { categories.add(it.groupValues[1]) }
            if (scan.contains("]")) inArray = false // array closed on this line
        }
    }

    require(categories.isNotEmpty()) {
        "No blocked categories parsed from config — refusing to run with an empty " +
            "blocklist, which would classify every domain as ALLOW and disable filtering."
    }

    return ClassificationConfig(blockedCategories = categories)
}
