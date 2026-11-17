package ai.atalia.chat.reactnative

import ai.atalia.chat.Atalia
import ai.atalia.chat.AtaliaEvent
import ai.atalia.chat.AtaliaException
import ai.atalia.chat.AtaliaLauncherOptions
import ai.atalia.chat.AtaliaListener
import ai.atalia.chat.AtaliaOptions
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.net.Uri
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

/** Opções de `configure`, como chegam do JavaScript. */
class ConfigureRecord : Record {
    @Field val baseUrl: String? = null
    @Field val customDomain: Boolean? = null
    @Field val closeButton: Boolean? = null
    @Field val presentationStyle: String? = null
    @Field val debug: Boolean? = null
    @Field val appLinkHosts: List<String>? = null
}

/** Opções do botão flutuante, como chegam do JavaScript. */
class LauncherRecord : Record {
    @Field val side: String? = null
    @Field val horizontalMargin: Double? = null
    @Field val bottomMargin: Double? = null
    @Field val text: String? = null
    @Field val subtitle: String? = null
    @Field val color: String? = null
    @Field val collapse: String? = null
    @Field val collapseAfter: Double? = null
    @Field val draggable: Boolean? = null
    @Field val dockable: Boolean? = null
    @Field val preloadChat: Boolean? = null
}

/** Ponte entre o JavaScript e o SDK Android (`ai.atalia:chat`). */
class AtaliaChatModule : Module() {
    private companion object {
        /** Pelo nome: o tipo herda de uma classe do androidx que este módulo não tem no classpath. */
        const val CHAT_ACTIVITY = "ai.atalia.chat.AtaliaChatActivity"
    }

    private var listener: AtaliaListener? = null

    private val context: Context
        get() = appContext.currentActivity ?: requireNotNull(appContext.reactContext) { "React context indisponível" }

    override fun definition() = ModuleDefinition {
        Name("AtaliaChat")

        Events("onEvent")

        OnStartObserving("onEvent") {
            if (listener != null) return@OnStartObserving
            val created = AtaliaListener { event -> sendEvent("onEvent", eventBody(event)) }
            listener = created
            Atalia.addListener(created)
        }

        OnStopObserving("onEvent") {
            listener?.let(Atalia::removeListener)
            listener = null
        }

        Function("configure") { channelToken: String, record: ConfigureRecord ->
            val app = context.applicationContext
            Atalia.configure(
                app,
                channelToken,
                AtaliaOptions(
                    baseUrl = record.baseUrl ?: AtaliaOptions.DEFAULT_BASE_URL,
                    customDomain = record.customDomain ?: false,
                    closeButton = record.closeButton ?: true,
                    debug = record.debug ?: false,
                    notificationIcon = AtaliaChatConfig.notificationIcon(AtaliaChatConfig.metaData(app)),
                    urlHandler = appLinkHandler(record.appLinkHosts.orEmpty()),
                    presentation = if (record.presentationStyle == "fullScreen") {
                        AtaliaOptions.Presentation.FULL_SCREEN
                    } else {
                        AtaliaOptions.Presentation.SHEET
                    },
                ),
            )
        }

        Function("open") {
            Atalia.open(context)
        }

        Function("openConversation") { conversationId: Double ->
            Atalia.openConversation(context, conversationId.toLong())
        }

        // Android: o chat é uma activity; "fechar" pelo app não se aplica.
        Function("close") {}

        // O SDK mexe no WebView do chat aberto: tudo que pode alcançá-lo roda na thread principal.
        // (Parênteses: `infix` tem precedência menor que `.`.)
        (AsyncFunction("identify") Coroutine { jwt: String ->
            try {
                val contact = Atalia.identify(jwt)
                mapOf("externalId" to contact.externalId, "contactId" to contact.contactId, "name" to contact.name)
            } catch (error: AtaliaException) {
                throw CodedException(error.code, error.code, error)
            }
        }).runOnQueue(Queues.MAIN)

        (AsyncFunction("logout") Coroutine { ->
            Atalia.logout()
        }).runOnQueue(Queues.MAIN)

        (AsyncFunction("refreshUnread") Coroutine { ->
            try {
                Atalia.refreshUnread()
            } catch (error: AtaliaException) {
                throw CodedException(error.code, error.code, error)
            }
        }).runOnQueue(Queues.MAIN)

        AsyncFunction("getState") {
            mapOf(
                "unreadCount" to Atalia.unreadCount.value,
                "isIdentified" to Atalia.isIdentified.value,
                "isChatOpen" to Atalia.isChatOpen.value,
            )
        }

        // Push

        Function("setPushToken") { token: String ->
            Atalia.setPushToken(token)
        }

        // Token do APNs só existe no iOS.
        Function("setAPNsToken") { _: String, _: String -> }

        Function("clearPushToken") {
            Atalia.clearPushToken()
        }

        AsyncFunction("handleNotification") { data: Map<String, String>, title: String?, body: String? ->
            Atalia.handleNotification(context.applicationContext, data, title, body)
        }

        Function("handleNotificationTap") { data: Map<String, String> ->
            if (!Atalia.isAtaliaNotification(data)) return@Function false
            val conversationId = data["conversation_id"]?.toLongOrNull()?.takeIf { it > 0 }
            try {
                if (conversationId != null) Atalia.openConversation(context, conversationId) else Atalia.open(context)
                true
            } catch (_: AtaliaException) {
                false
            }
        }

        // Botão flutuante

        Function("showLauncher") { record: LauncherRecord ->
            val activity = appContext.currentActivity ?: return@Function
            val options = launcherOptions(record)
            activity.runOnUiThread { Atalia.showLauncher(activity, options) }
        }

        Function("hideLauncher") {
            appContext.currentActivity?.let { activity ->
                activity.runOnUiThread { Atalia.hideLauncher(activity) }
            }
        }
    }

    /**
     * Links dos sites do próprio app (`appLinkHosts`): em vez do navegador, o chat fecha e o
     * JavaScript recebe `linkPressed` para navegar. Sem ninguém ouvindo os eventos, o link segue
     * para o sistema: fechar o chat sem o app navegar deixaria o visitante sem resposta.
     */
    private fun appLinkHandler(hosts: List<String>): ((Context, Uri) -> Boolean)? {
        if (hosts.isEmpty()) return null
        return handler@{ context, uri ->
            if (listener == null || !isAppLink(uri, hosts)) return@handler false
            val sent = runCatching { sendEvent("onEvent", mapOf("type" to "linkPressed", "url" to uri.toString())) }.isSuccess
            if (sent) chatActivity(context)?.finish()
            sent
        }
    }

    private fun isAppLink(uri: Uri, hosts: List<String>): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return hosts.any { host == it || host.endsWith(".$it") }
    }

    /** O chat vive num MutableContextWrapper: desembrulha até a tela dele (nunca a do app). */
    private fun chatActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current.takeIf { it.javaClass.name == CHAT_ACTIVITY }
            current = current.baseContext
        }
        return null
    }

    private fun launcherOptions(record: LauncherRecord): AtaliaLauncherOptions {
        val defaults = AtaliaLauncherOptions()
        return AtaliaLauncherOptions(
            side = when (record.side) {
                "start" -> AtaliaLauncherOptions.Side.START
                "end" -> AtaliaLauncherOptions.Side.END
                else -> null
            },
            horizontalMarginDp = record.horizontalMargin?.toInt() ?: defaults.horizontalMarginDp,
            bottomMarginDp = record.bottomMargin?.toInt() ?: defaults.bottomMarginDp,
            text = record.text,
            subtitle = record.subtitle,
            color = record.color?.let { runCatching { Color.parseColor(it) }.getOrNull() },
            collapse = when (record.collapse) {
                "edge" -> AtaliaLauncherOptions.Collapse.EDGE
                "never" -> AtaliaLauncherOptions.Collapse.NEVER
                else -> AtaliaLauncherOptions.Collapse.CIRCLE
            },
            collapseAfterMs = record.collapseAfter?.toLong() ?: defaults.collapseAfterMs,
            draggable = record.draggable ?: false,
            dockable = record.dockable ?: defaults.dockable,
            preloadChat = record.preloadChat ?: defaults.preloadChat,
        )
    }

    private fun eventBody(event: AtaliaEvent): Map<String, Any?> = when (event) {
        AtaliaEvent.Ready -> mapOf("type" to "ready")
        AtaliaEvent.Opened -> mapOf("type" to "opened")
        AtaliaEvent.Closed -> mapOf("type" to "closed")
        is AtaliaEvent.Identified -> mapOf("type" to "identified", "externalId" to event.externalId, "contactId" to event.contactId)
        AtaliaEvent.LoggedOut -> mapOf("type" to "loggedOut")
        is AtaliaEvent.ConversationStarted -> mapOf("type" to "conversationStarted", "conversationId" to event.conversationId)
        is AtaliaEvent.ConversationClosed -> mapOf("type" to "conversationClosed", "conversationId" to event.conversationId)
        is AtaliaEvent.MessageReceived ->
            mapOf("type" to "messageReceived", "conversationId" to event.conversationId, "messageId" to event.messageId)
        is AtaliaEvent.UnreadChanged -> mapOf("type" to "unreadChanged", "count" to event.count)
        is AtaliaEvent.Error -> mapOf("type" to "error", "code" to event.code, "command" to event.command)
    }
}
