package io.github.anastaci1a.localica.queue

import kotlin.random.Random

/** Occurrence IDs, independent of visible order. The chosen song starts a fresh traversal. */
internal fun shuffleTraversal(ids: List<String>, current: String?, previous: List<String> = emptyList(),
    random: Random = Random.Default): List<String> {
    if (previous.isEmpty()) return listOfNotNull(current?.takeIf { it in ids }) + ids.filterNot { it == current }.shuffled(random)
    val retained = previous.filter { it in ids }
    return retained + ids.filterNot { it in retained }.shuffled(random)
}
