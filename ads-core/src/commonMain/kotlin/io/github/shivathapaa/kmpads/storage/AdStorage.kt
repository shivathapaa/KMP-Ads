package io.github.shivathapaa.kmpads.storage

/**
 * Durable storage for the policy engine's counters: one key holding one string. Implement it over
 * any key-value store, such as DataStore, `NSUserDefaults` or a file.
 */
public interface AdStorage {
    public suspend fun read(): String?

    public suspend fun write(value: String)

    public companion object {
        /** A suggested storage key. */
        public const val SUGGESTED_KEY: String = "kmpads_state_v1"
    }
}

/** In-memory storage. Counters reset with each process, so warm-up restarts and fewer ads show. */
public class InMemoryAdStorage(initial: String? = null) : AdStorage {
    private var value: String? = initial

    override suspend fun read(): String? = value

    override suspend fun write(value: String) {
        this.value = value
    }
}
