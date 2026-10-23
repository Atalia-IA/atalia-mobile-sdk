package ai.atalia.chat.internal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Sessão do visitante. O token é a credencial da conversa. */
internal data class StoredSession(val token: String, val identified: Boolean, val externalId: String?) {
    fun toJson(): String = JSONObject()
        .put("token", token)
        .put("identified", identified)
        .apply { externalId?.let { put("externalId", it) } }
        .toString()

    companion object {
        fun fromJson(raw: String): StoredSession? = runCatching {
            val json = JSONObject(raw)
            StoredSession(json.getString("token"), json.optBoolean("identified"), json.opt("externalId") as? String)
        }.getOrNull()
    }
}

internal interface SessionStorage {
    fun load(): StoredSession?

    fun save(session: StoredSession)

    fun clear()

    /** Cor do cabeçalho do canal, para a barra de status combinar antes de a página carregar. */
    fun loadPrimaryColor(): String? = null

    fun savePrimaryColor(color: String) {}
}

/**
 * SharedPreferences cifrado com uma chave AES-GCM do Android Keystore (a chave nunca sai do
 * hardware). Uma entrada por canal e origem: o app pode falar com mais de um canal.
 * Se o Keystore falhar (aparelho com chave corrompida), a sessão vira anônima de novo:
 * perder o histórico é melhor que travar o chat.
 */
internal class KeystoreSessionStorage(context: Context, baseUrl: String, channelToken: String) : SessionStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val key = sha256("${ChatUrl.origin(baseUrl)}|$channelToken")

    override fun load(): StoredSession? {
        val stored = prefs.getString("session:$key", null) ?: return null
        return runCatching { StoredSession.fromJson(decrypt(stored)) }.getOrNull() ?: run {
            clear()
            null
        }
    }

    override fun save(session: StoredSession) {
        runCatching { encrypt(session.toJson()) }
            .onSuccess { prefs.edit().putString("session:$key", it).apply() }
    }

    override fun clear() {
        prefs.edit().remove("session:$key").apply()
    }

    override fun loadPrimaryColor(): String? = prefs.getString("color:$key", null)

    override fun savePrimaryColor(color: String) {
        prefs.edit().putString("color:$key", color).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val iv = sealed.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        }
        return String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val PREFS = "ai.atalia.chat"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ai.atalia.chat.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Usado nos testes. */
internal class MemorySessionStorage(private var session: StoredSession? = null) : SessionStorage {
    private var color: String? = null

    override fun load() = session

    override fun save(session: StoredSession) {
        this.session = session
    }

    override fun clear() {
        session = null
    }

    override fun loadPrimaryColor() = color

    override fun savePrimaryColor(color: String) {
        this.color = color
    }
}
