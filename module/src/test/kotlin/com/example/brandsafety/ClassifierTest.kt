package com.example.brandsafety

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class ClassifierTest {

    // GARM-aligned Sensitive Topics from IAB Content Taxonomy 3.0.
    private val config = ClassificationConfig(
        blockedCategories = setOf(
            "v9i3On",   // Sensitive Topics (parent)
            "Rm3SiT",   // Adult & Explicit Sexual Content
            "avbNf2",   // Arms & Ammunition
            "XtODT3",   // Crime & Harmful Acts
            "I4GWl6",   // Death, Injury, or Military Conflict
            "HxqYV1",   // Hate Speech & Acts of Aggression
            "pg0WhF",   // Illegal Drugs/Tobacco/Vaping/Alcohol
            "j9PaO9",   // Obscenity and Profanity
            "mm3UXx",   // Online Piracy
            "6i4dB6",   // Spam or Harmful Content
            "8FD8nI",   // Terrorism
        )
    )

    @Test
    fun `blocked domain returns DROP`() {
        val result = classify(DomainEntry("adult-site.com", listOf("Rm3SiT")), config)
        assertEquals(Action.DROP, result.action)
    }

    @Test
    fun `safe domain returns ALLOW`() {
        val result = classify(DomainEntry("news-site.com", listOf("396")), config)
        assertEquals(Action.ALLOW, result.action)
    }

    @Test
    fun `unknown category returns ALLOW`() {
        assertEquals(Action.ALLOW, classify(DomainEntry("random-site.com", listOf("999")), config).action)
    }

    @Test
    fun `terrorism category returns DROP`() {
        assertEquals(Action.DROP, classify(DomainEntry("terror-site.org", listOf("8FD8nI")), config).action)
    }

    @Test
    fun `gambling category returns ALLOW`() {
        assertEquals(Action.ALLOW, classify(DomainEntry("casino-site.com", listOf("181")), config).action)
    }

    @Test
    fun `metadata lists all categories`() {
        val result = classify(DomainEntry("mixed-site.com", listOf("396", "Rm3SiT")), config)
        assertTrue(result.metadata.contains("\"categories\":[\"396\",\"Rm3SiT\"]"))
    }

    @Test
    fun `allowed domain has empty blocked list`() {
        val result = classify(DomainEntry("news-site.com", listOf("396", "132")), config)
        assertEquals(Action.ALLOW, result.action)
        assertTrue(result.metadata.contains("\"blocked\":[]"))
    }

    @Test
    fun `multiple categories one blocked returns DROP and reports it`() {
        val result = classify(DomainEntry("mixed-site.com", listOf("396", "Rm3SiT")), config)
        assertEquals(Action.DROP, result.action)
        // Only the blocked category (Rm3SiT) appears in blocked, not the safe 396.
        assertTrue(result.metadata.contains("\"blocked\":[\"Rm3SiT\"]"))
    }

    @Test
    fun `multiple blocked categories are all reported`() {
        val result = classify(DomainEntry("very-bad.com", listOf("Rm3SiT", "396", "8FD8nI")), config)
        assertEquals(Action.DROP, result.action)
        assertTrue(result.metadata.contains("\"blocked\":[\"Rm3SiT\",\"8FD8nI\"]"))
    }

    @Test
    fun `metadata has no risk field`() {
        val result = classify(DomainEntry("adult-site.com", listOf("Rm3SiT")), config)
        assertTrue(!result.metadata.contains("risk"))
    }

    @Test
    fun `classifyAll produces correct results`() {
        val domains = listOf(
            DomainEntry("bad.com", listOf("Rm3SiT")),
            DomainEntry("good.com", listOf("396")),
        )
        val results = classifyAll(domains, config)
        assertEquals(Action.DROP, results[0].action)
        assertEquals(Action.ALLOW, results[1].action)
    }

    @Test
    fun `output TSV format includes metadata column`() {
        val classifications = listOf(
            Classification("bad.com", Action.DROP, """{"categories":["Rm3SiT"],"blocked":["Rm3SiT"]}"""),
            Classification("good.com", Action.ALLOW, """{"categories":["396"],"blocked":[]}"""),
        )
        val output = generateOutputTsv(classifications)
        assertTrue(output.contains("bad.com\tDROP\t"))
        assertTrue(output.contains("good.com\tALLOW\t"))
        assertTrue(output.startsWith("domain\taction\tmetadata"))
    }

    @Test
    fun `parseDomains reads tab-separated rows and skips header`() {
        val domains = parseDomains(
            listOf(
                "domain\tcategories",
                "bad.com\tRm3SiT,396",
                "good.com\t396",
            )
        )
        assertEquals(2, domains.size)
        assertEquals("bad.com", domains[0].domain)
        assertEquals(listOf("Rm3SiT", "396"), domains[0].categories)
    }

    @Test
    fun `loadConfig parses quoted category ids from toml lines`() {
        val config = loadConfig(
            listOf(
                "[classification]",
                "blocked_categories = [",
                "    \"Rm3SiT\",   # Adult & Explicit Sexual Content",
                "    \"8FD8nI\",   # Terrorism",
                "]",
            )
        )
        assertEquals(setOf("Rm3SiT", "8FD8nI"), config.blockedCategories)
    }

    @Test
    fun `loadConfig parses a single-line collapsed array identically`() {
        // A TOML formatter may collapse the array onto one line; it must parse the same.
        val config = loadConfig(
            listOf("blocked_categories = [\"Rm3SiT\", \"8FD8nI\"]")
        )
        assertEquals(setOf("Rm3SiT", "8FD8nI"), config.blockedCategories)
    }

    @Test
    fun `loadConfig fails closed on an empty blocklist`() {
        // An empty blocklist would classify every domain ALLOW and disable the control.
        val ex = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            loadConfig(listOf("[classification]", "blocked_categories = []"))
        }
        assertTrue(ex.message!!.contains("empty blocklist"))
    }

    @Test
    fun `loadConfig fails closed even when other quoted values exist outside the array`() {
        // Regression: quoted values elsewhere in the file must NOT be read as categories,
        // so an empty array still fails closed rather than silently disabling filtering.
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            loadConfig(
                listOf(
                    "version = \"1\"",
                    "title = \"my config\"",
                    "blocked_categories = []",
                )
            )
        }
    }

    @Test
    fun `loadConfig ignores quoted values outside the blocked_categories array`() {
        val config = loadConfig(
            listOf(
                "version = \"1\"",
                "blocked_categories = [\"Rm3SiT\"]",
                "notes = \"ignore me\"",
            )
        )
        assertEquals(setOf("Rm3SiT"), config.blockedCategories)
    }

    @Test
    fun `loadConfig ignores commented-out labels`() {
        // The label after '#' must never be parsed as a category id, even when it is
        // itself quoted. The real category id before the '#' is still collected.
        val config = loadConfig(
            listOf(
                "blocked_categories = [",
                "    \"Rm3SiT\",   # \"NotACategory\"",
                "]",
            )
        )
        assertEquals(setOf("Rm3SiT"), config.blockedCategories)
    }

    @Test
    fun `parseDomains skips malformed rows instead of throwing`() {
        val domains = parseDomains(
            listOf(
                "domain\tcategories",
                "bad.com\tRm3SiT",
                "no-tab-here",          // malformed: no categories column
                "\tRm3SiT",             // malformed: blank domain
                "good.com\t396",
            )
        )
        assertEquals(2, domains.size)
        assertEquals(listOf("bad.com", "good.com"), domains.map { it.domain })
    }

    @Test
    fun `parseDomains normalizes domain casing and trailing dot`() {
        val domains = parseDomains(listOf("domain\tcategories", "Bad.COM.\tRm3SiT"))
        assertEquals("bad.com", domains[0].domain)
    }

    @Test
    fun `parseDomains fails closed when most rows are malformed`() {
        // Simulates tabs mangled into spaces (spreadsheet round-trip): nearly all rows
        // malformed must abort rather than publish a degraded/empty table.
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            parseDomains(
                listOf(
                    "domain\tcategories",
                    "bad.com Rm3SiT",   // space instead of tab -> malformed
                    "good.com 396",     // space instead of tab -> malformed
                    "ugly.com A1",      // space instead of tab -> malformed
                )
            )
        }
    }

    @Test
    fun `parseDomains tolerates a minority of malformed rows`() {
        val domains = parseDomains(
            listOf(
                "domain\tcategories",
                "bad.com\tRm3SiT",
                "good.com\t396",
                "oops-no-tab",       // 1 of 3 malformed -> under threshold, tolerated
            )
        )
        assertEquals(2, domains.size)
    }

    @Test
    fun `metadata escapes quotes in category values`() {
        // A category value containing a quote must not break out of the JSON string.
        val result = classify(DomainEntry("x.example", listOf("a\"b")), config)
        assertTrue(result.metadata.contains("\"a\\\"b\""))
    }

    @Test
    fun `requireBlockingTable throws when nothing is dropped`() {
        // The outcome guard: a table that blocks nothing means the blocklist or dataset
        // was lost upstream, so publishing it would silently disable filtering.
        val allAllow = listOf(
            Classification("a.com", Action.ALLOW, "{}"),
            Classification("b.com", Action.ALLOW, "{}"),
        )
        val ex = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            requireBlockingTable(allAllow)
        }
        assertTrue(ex.message!!.contains("blocks nothing"))
    }

    @Test
    fun `requireBlockingTable returns the drop count when at least one is dropped`() {
        val mixed = listOf(
            Classification("bad.com", Action.DROP, "{}"),
            Classification("good.com", Action.ALLOW, "{}"),
        )
        assertEquals(1, requireBlockingTable(mixed))
    }

    @Test
    fun `loadConfig fails closed when a suffixed key mimics the array key`() {
        // Regression: a key that merely starts with `blocked_categories`, e.g.
        // `blocked_categories_note`, must NOT re-open the array and smuggle its quoted
        // value in as a category — the real (empty) array must still fail closed.
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            loadConfig(
                listOf(
                    "blocked_categories = []",
                    "blocked_categories_note = \"cleared during incident INC-12345\"",
                )
            )
        }
    }

    @Test
    fun `loadConfig ignores a suffixed key while reading the real array`() {
        val config = loadConfig(
            listOf(
                "blocked_categories = [\"Rm3SiT\"]",
                "blocked_categories_note = \"audited 2026\"",
            )
        )
        assertEquals(setOf("Rm3SiT"), config.blockedCategories)
    }

    @Test
    fun `parseDomains treats an empty category column as malformed, not ALLOW`() {
        // Regression: `evil.com\t` splits to ["evil.com", ""], which used to be accepted
        // with an empty category list and silently classified ALLOW. It must now be
        // dropped as malformed so a domain can't slip through by an empty column.
        val domains = parseDomains(
            listOf(
                "domain\tcategories",
                "evil.com\t",
                "good.com\t396",
            )
        )
        assertEquals(listOf("good.com"), domains.map { it.domain })
    }

    @Test
    fun `parseDomains rejects a domain with an invalid character`() {
        // A domain carrying a space (or any non-hostname char, e.g. an embedded tab that
        // would corrupt the TAB-delimited output) is malformed, not a valid key.
        val domains = parseDomains(
            listOf(
                "domain\tcategories",
                "ev il.com\t396",
                "good.com\t396",
            )
        )
        assertEquals(listOf("good.com"), domains.map { it.domain })
    }

    @Test
    fun `parseDomains fails closed when all rows have empty categories`() {
        // A wholesale-blanked category column (enrichment miss / spreadsheet export) is a
        // degraded feed: all rows malformed -> abort rather than publish an all-ALLOW table.
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            parseDomains(
                listOf(
                    "domain\tcategories",
                    "a.com\t",
                    "b.com\t",
                    "c.com\t",
                )
            )
        }
    }
}
