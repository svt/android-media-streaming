package se.svt.oss.android.streaming.cache

/**
 * Defer construction of containing element until needed.
 *
 * Note that this class is not thread-safe!
 */
class SingleElementCache<K, V>(private val provider: (K) -> V) {
    private var cache: Pair<K, V>? = null

    fun get(arg: K): V = cache
        ?.takeIf { (key, _) -> key == arg }
        ?.let { (_, value) -> value } ?: provider(arg).also { cache = arg to it }
}
