package ai.atalia.chat.reactnative

import ai.atalia.chat.Atalia
import ai.atalia.chat.AtaliaEvent
import ai.atalia.chat.AtaliaException
import ai.atalia.chat.AtaliaLauncherOptions
import ai.atalia.chat.AtaliaListener
import ai.atalia.chat.AtaliaOptions
import android.content.Context
import android.graphics.Color
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
