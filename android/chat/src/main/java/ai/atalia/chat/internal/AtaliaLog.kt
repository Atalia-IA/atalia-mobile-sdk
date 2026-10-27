package ai.atalia.chat.internal

import android.util.Log

/**
 * Registro do SDK no Logcat (tag `AtaliaChat`), ligado por `AtaliaOptions.debug`. Serve para o
 * time do app ver o caminho do push e do chat no aparelho. Só eventos, ids e códigos: nunca texto
 * de mensagem, JWT ou token inteiro.
 */
internal object AtaliaLog {
    const val TAG = "AtaliaChat"

    @Volatile
    var enabled = false

    fun i(message: String) {
        if (enabled) Log.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        if (enabled) Log.w(TAG, message, error)
    }

    /** Começo e fim do token, o bastante para casar com a lista de aparelhos do painel. */
    fun mask(token: String): String = if (token.length <= 12) "…" else "${token.take(6)}…${token.takeLast(4)}"
}
