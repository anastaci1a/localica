package io.github.anastaci1a.localica.ui

import java.text.Normalizer
import java.util.Locale
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SettingsCatalogIndexTest {
    private val entries get() = SettingsCatalog.entries
    private val parents get() = SettingsCatalog.parents
    private fun breadcrumb(page: String) = SettingsCatalog.breadcrumb(page)

    @Test fun normalizedIndexPreservesEveryOrderedResultAndScore() {
        val texts = entries.flatMap { listOf(it.title, it.keywords) + it.values } + parents.keys.map(::breadcrumb)
        val words = texts.flatMap { it.split(' ') }.filter { it.isNotBlank() }.distinct()
        val queries = (texts + words.flatMap { word -> word.indices.map { word.take(it + 1) } } +
            words.map { "$it nonexistent" } + texts.map { "  " + it.uppercase(Locale.ROOT).replace("E", "É") + " !" } +
            listOf("", "   ", "grey", "GRÁY", "gray\u0301", "blur queue", "blurr", "queue queue")).distinct()
        val digest = MessageDigest.getInstance("SHA-256")
        queries.forEach { query ->
            val expected = originalSearch(query, Int.MAX_VALUE)
            for (limit in listOf(0, 1, 7, 30, Int.MAX_VALUE)) {
                assertEquals("Query=$query limit=$limit", expected.take(limit), SettingsCatalog.search(query, limit))
            }
            digest.update((query + expected.toString()).toByteArray(Charsets.UTF_8))
        }
        println("Settings index exact parity: " + queries.size + " queries; SHA-256 " +
            digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun normalize(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace("grey", "gray")
        .split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.isNotBlank() }.joinToString(" ")

    private fun originalSearch(query: String, limit: Int = 30): List<SettingsCatalog.Result> {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return emptyList()
        val tokens = normalized.split(' ')
        fun score(title: String, keywords: String, page: String): Int? {
            val name = normalize(title)
            val extra = normalize(keywords)
            val trail = normalize(breadcrumb(page))
            val scores = tokens.map { token -> when {
                name == token -> 100
                name.startsWith(token) -> 80
                name.split(' ').any { it.startsWith(token) } -> 60
                token in name -> 45
                token in extra -> 35
                token in trail -> 25
                else -> return null
            } }
            return scores.sum() + if (name == normalized) 100 else 0
        }
        val settings = entries.mapNotNull { spec ->
            score(spec.title, spec.keywords + " " + spec.values.joinToString(" "), spec.page)
                ?.let { SettingsCatalog.Result(spec.title, spec.page, spec, it) }
        }
        val pages = parents.keys.filterNot { page -> entries.any { it.id == page && it.page == page } }.mapNotNull { page ->
            val summary = entries.filter { it.page == page }.joinToString(" ") { it.title + " " + it.keywords }
            score(page, summary, page)?.let { SettingsCatalog.Result(page, page, null, it - 1) }
        }
        return (settings + pages).sortedByDescending { it.score }.take(limit)
    }
}
