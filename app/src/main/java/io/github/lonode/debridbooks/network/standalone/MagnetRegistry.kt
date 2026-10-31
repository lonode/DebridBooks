package io.github.lonode.debridbooks.network.standalone

/** Only account-scoped hashes and numeric provider IDs, never URLs or keys. */
class RegisteredMagnet(val identity: String, val id: String, val infohash: String?)
interface MagnetRegistry {
    suspend fun entries(account: String): List<RegisteredMagnet>
    suspend fun save(account: String, identity: String, id: String, infohash: String?)
    suspend fun remove(account: String, identity: String)
}
object ProviderRegistry {
    @Volatile var current: MagnetRegistry = MemoryMagnetRegistry()
}
class MemoryMagnetRegistry : MagnetRegistry {
    private val entries = mutableMapOf<String, LinkedHashMap<String, RegisteredMagnet>>()
    override suspend fun entries(account: String) = synchronized(entries) { entries[account]?.values?.toList().orEmpty() }
    override suspend fun save(account: String, identity: String, id: String, infohash: String?) { synchronized(entries) {
        val accountEntries = entries.getOrPut(account) { linkedMapOf() }
        if (accountEntries.size >= 1000) accountEntries.remove(accountEntries.keys.first())
        accountEntries[identity] = RegisteredMagnet(identity, id, infohash)
    } }
    override suspend fun remove(account: String, identity: String) { synchronized(entries) { entries[account]?.remove(identity) } }
}
