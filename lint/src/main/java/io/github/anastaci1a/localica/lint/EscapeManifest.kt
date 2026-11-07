package io.github.anastaci1a.localica.lint

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Safe, duplicate-free data only. No YAML constructors or shared parser state. */
internal object EscapeManifest {
    private val keys = setOf("rule", "symbol", "reason", "issue", "owner", "reviewer", "expires")
    fun parse(text: String): List<EscapeEntry>? {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 0
            codePointLimit = 100_000
        }
        val loaded = try { Yaml(SafeConstructor(options)).load<Any>(text) } catch (_: RuntimeException) { return null }
        val list = loaded as? List<*> ?: return null
        val rows = list.map { value ->
            val map = value as? Map<*, *> ?: return null
            if (map.keys.any { it !is String } || map.values.any { it !is String || it.isBlank() }) return null
            map.entries.associate { it.key as String to it.value as String }
        }
        val result = rows.map { row ->
            if (row.keys != keys) return null
            val rule = UiRuleId.entries.find { it.name == row["rule"] } ?: return null
            val symbol = row.getValue("symbol")
            if (!symbol.matches(Regex("[A-Za-z_][A-Za-z0-9_.$]*:[1-9][0-9]*"))) return null
            if (!row.getValue("issue").matches(Regex("https://github\\.com/anastaci1a/localica/issues/[1-9][0-9]*"))) return null
            if (row["reviewer"] != "lead") return null
            EscapeEntry(rule, symbol, row.getValue("reason"), row.getValue("issue"), row.getValue("owner"), row.getValue("reviewer"), row.getValue("expires"))
        }
        if (result.map { it.rule to it.symbol }.distinct().size != result.size) return null
        return result
    }

    fun isLive(entry: EscapeEntry, knownSteps: String?, completedSteps: String?, release: String?): Boolean {
        if (entry.reviewer != "lead") return false
        if (entry.expires.startsWith("U")) {
            if (knownSteps == null || completedSteps == null) return false
            val known = knownSteps.split(',').filter { it.isNotBlank() }.toSet()
            val completed = completedSteps.split(',').filter { it.isNotBlank() }.toSet()
            return known.all { it.matches(Regex("U\\d{2}\\.\\d{2}[a-z]?")) } &&
                known.containsAll(completed) && entry.expires in known && entry.expires !in completed
        }
        fun version(value: String?): List<Int>? = value?.takeIf { it.matches(Regex("v\\d+\\.\\d+\\.\\d+")) }
            ?.drop(1)?.split('.')?.map { it.toIntOrNull() ?: return null }
        val expiry = version(entry.expires) ?: return false
        val current = version(release) ?: return false
        for (index in current.indices) {
            if (current[index] != expiry[index]) return current[index] < expiry[index]
        }
        return false
    }
}

/** `tools/ui-consolidation/stage.json` step lists (known, completed), or null unless strictly well-formed. */
internal object StageFile {
    fun steps(text: String): Pair<Set<String>, Set<String>>? {
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false; maxAliasesForCollections = 0; codePointLimit = 100_000 }
        val map = (try { Yaml(SafeConstructor(options)).load<Any>(text) } catch (_: RuntimeException) { null }) as? Map<*, *> ?: return null
        fun list(key: String) = (map[key] as? List<*>)?.map { it as? String ?: return null }?.takeIf { it.toSet().size == it.size }?.toSet()
        val known = list("knownSteps") ?: return null
        val completed = list("completedSteps") ?: return null
        return (known to completed).takeIf { map["schemaVersion"] == 1 && known.isNotEmpty() && known.containsAll(completed) }
    }
}
