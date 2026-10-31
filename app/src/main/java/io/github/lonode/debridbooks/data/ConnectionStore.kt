package io.github.lonode.debridbooks.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.lonode.debridbooks.domain.MobileContract
import io.github.lonode.debridbooks.network.Connection
import io.github.lonode.debridbooks.network.standalone.StandaloneConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

interface SecretCodec {
    fun protect(value: String, account: String): ByteArray
    fun reveal(bytes: ByteArray, account: String): String
}

/** One encrypted record in noBackupFilesDir; no credentials in preferences or Room. */
class ConnectionStore(context: Context, private val accounts: ProviderTransferDao? = null) : SecretCodec {
    private val file = java.io.File(context.noBackupFilesDir, "connection")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("mobile-connection", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("mobile-connection", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized override fun protect(value: String, account: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(account.toByteArray())
        return cipher.iv + cipher.doFinal(value.toByteArray())
    }
    @Synchronized override fun reveal(bytes: ByteArray, account: String): String {
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(account.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    @Synchronized fun read(): Connection? {
        if (!file.exists()) return null
        val bytes = android.util.AtomicFile(file).readFully()
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val old = Json.decodeFromString<Connection>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
        val migrated = StandaloneConfig.migrate(old)
        if (migrated !== old) save(migrated) else rememberAccount(migrated)
        return migrated
    }
    /** A non-secret provider-key fingerprint keeps migrated library scopes stable across disconnects. */
    @Synchronized fun candidate(config: StandaloneConfig, previous: Connection?): Connection {
        val default = StandaloneConfig.connect(config, previous)
        if (previous?.standalone?.apiKey == config.apiKey) return default
        val known = accounts?.libraryAccount(MobileContract.localId("alldebrid:${config.apiKey}")) ?: return default
        return Connection(accountId = known, standalone = config)
    }
    private fun rememberAccount(connection: Connection) {
        val config = connection.standalone ?: return
        val key = MobileContract.localId("alldebrid:${config.apiKey}")
        if (accounts != null && accounts.libraryAccount(key) != connection.accountId) accounts.bindAccount(ProviderAccount(key, connection.accountId))
    }
    @Synchronized fun save(connection: Connection) {
        rememberAccount(connection)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(cipher.iv + cipher.doFinal(Json.encodeToString(connection).toByteArray()))
            atomic.finishWrite(stream)
        } catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    @Synchronized fun clear() { android.util.AtomicFile(file).delete() }
}
