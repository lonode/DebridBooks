package io.github.lonode.debridbooks.data

import androidx.room.*
import io.github.lonode.debridbooks.network.standalone.*

@Entity(tableName = "provider_transfers", primaryKeys = ["accountId", "identity"])
data class ProviderTransfer(val accountId: String, val identity: String, val providerId: String, val infohash: String?)
@Entity(tableName = "provider_accounts")
data class ProviderAccount(@PrimaryKey val providerKey: String, val libraryAccount: String)
@Dao
interface ProviderTransferDao {
    @Query("SELECT libraryAccount FROM provider_accounts WHERE providerKey=:key") fun libraryAccount(key: String): String?
    @Upsert fun bindAccount(value: ProviderAccount)
    @Query("SELECT * FROM provider_transfers WHERE accountId=:account") suspend fun entries(account: String): List<ProviderTransfer>
    @Upsert suspend fun save(value: ProviderTransfer)
    @Query("DELETE FROM provider_transfers WHERE accountId=:account AND identity=:identity") suspend fun remove(account: String, identity: String)
}
class RoomMagnetRegistry(private val database: () -> LibraryDatabase) : MagnetRegistry {
    override suspend fun entries(account: String) = database().providerTransfers().entries(account).map { RegisteredMagnet(it.identity, it.providerId, it.infohash) }
    override suspend fun save(account: String, identity: String, id: String, infohash: String?) = database().providerTransfers().save(ProviderTransfer(account, identity, id, infohash))
    override suspend fun remove(account: String, identity: String) = database().providerTransfers().remove(account, identity)
}
