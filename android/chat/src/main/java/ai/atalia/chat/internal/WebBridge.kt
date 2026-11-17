package ai.atalia.chat.internal

import ai.atalia.chat.R
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * O que a tela do chat e a camada dos balões fazem igual com o WebView: expor o bridge só à origem
 * da Atalia, mandar comandos e abrir links fora dele.
 */
internal object WebBridge {
    const val NAME = "AtaliaAndroid"

    /**
     * Liga o objeto `AtaliaAndroid` ao WebView. [currentUrl] só é usado no WebView antigo, sem
     * `WebMessageListener`, para conferir a origem da página a cada mensagem.
     */
    fun install(webView: WebView, baseUrl: String, currentUrl: () -> String?, receive: (String?) -> Unit) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // O objeto só existe nas páginas da origem da Atalia, e só o frame principal fala.
            WebViewCompat.addWebMessageListener(webView, NAME, setOf(ChatUrl.origin(baseUrl))) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) receive(message.data)
            }
        } else {
            @Suppress("JavascriptInterface")
            webView.addJavascriptInterface(
                LegacyBridge { json -> if (isTrusted(currentUrl(), baseUrl)) receive(json) },
                NAME,
            )
        }
    }

    /** A URL é da origem do chat? */
    fun isTrusted(url: String?, baseUrl: String): Boolean =
        url != null && ChatUrl.origin(url).equals(ChatUrl.origin(baseUrl), ignoreCase = true)

    /** Comando nativo → web. Só depois do `ready` da página. */
    fun send(webView: WebView, type: String, payload: JSONObject?) {
        val command = JSONObject().put("type", type).apply { payload?.let { put("payload", it) } }
        webView.evaluateJavascript("window.AtaliaChat&&window.AtaliaChat._native&&window.AtaliaChat._native($command);", null)
    }

    /** Versão do app, para o diagnóstico do fragmento. */
    fun appVersion(context: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    /** Abre o link fora do WebView: primeiro o `urlHandler` do app, depois o sistema. */
    fun openExternal(context: Context, url: String, urlHandler: ((Context, Uri) -> Boolean)?) {
        val uri = Uri.parse(url)
        if (urlHandler?.invoke(context, uri) == true) return
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.atalia_chat_no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }
}
