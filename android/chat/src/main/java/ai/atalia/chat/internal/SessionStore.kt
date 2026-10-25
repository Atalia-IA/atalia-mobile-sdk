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

    /** Aparência do canal da última consulta: topo do chat e botão flutuante não piscam. */
    fun loadBranding(): ChannelBranding? = null

    fun saveBranding(branding: ChannelBranding) {}

    /**
     * Token do push do aparelho. Fica separado da sessão: o logout apaga a sessão, mas o aparelho
     * continua o mesmo e precisa ser registrado de novo na sessão nova.
     */
    fun loadPushToken(): String? = null

    fun savePushToken(token: String) {}

    fun clearPushToken() {}
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

    override fun loadBranding(): ChannelBranding? {
        val stored = prefs.getString("branding:$key", null) ?: return null
        return runCatching { ChannelBranding.fromJson(JSONObject(stored)) }.getOrNull()
    }

    override fun saveBranding(branding: ChannelBranding) {
        prefs.edit().putString("branding:$key", branding.toJson()).apply()
    }

    // O token do push não abre conversa nenhuma (só serve para o Firebase entregar no aparelho),
    // então fica fora da cifra da sessão.
    override fun loadPushToken(): String? = prefs.getString("push:$key", null)

    override fun savePushToken(token: String) {
        prefs.edit().putString("push:$key", token).apply()
    }

    override fun clearPushToken() {
        prefs.edit().remove("push:$key").apply()
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
    private var branding: ChannelBranding? = null
    private var pushToken: String? = null

    override fun loadPushToken() = pushToken

    override fun savePushToken(token: String) {
        pushToken = token
    }

    override fun clearPushToken() {
        pushToken = null
    }

    override fun load() = session

    override fun save(session: StoredSession) {
        this.session = session
    }

    override fun clear() {
        session = null
    }

    override fun loadBranding() = branding

    override fun saveBranding(branding: ChannelBranding) {
        this.branding = branding
    }
}
