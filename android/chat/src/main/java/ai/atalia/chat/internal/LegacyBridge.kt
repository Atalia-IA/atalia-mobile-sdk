package ai.atalia.chat.internal

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * Bridge para WebView antigo, sem `WebMessageListener`. O `addJavascriptInterface` expõe o
 * objeto a qualquer página carregada, então quem recebe confere a origem da página atual
 * na thread principal antes de aceitar a mensagem.
 */
internal class LegacyBridge(private val onMessage: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postMessage(json: String?) {
        if (json == null) return
        main.post { onMessage(json) }
    }
}
