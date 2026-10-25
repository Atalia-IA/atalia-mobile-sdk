package ai.atalia.chat.reactnative

import ai.atalia.chat.Atalia
import ai.atalia.chat.AtaliaOptions
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import expo.modules.core.interfaces.ApplicationLifecycleListener
import expo.modules.core.interfaces.Package

/** Descoberto pelo autolinking do Expo: registra a configuração no início do app. */
class AtaliaChatPackage : Package {
    override fun createApplicationLifecycleListeners(context: Context): List<ApplicationLifecycleListener> =
        listOf(AtaliaChatLifecycleListener())
}

/**
 * Configura a Atalia ao iniciar o app, antes do JavaScript: o toque numa notificação que abre o
 * app já encontra o SDK pronto. O plugin do Expo grava o token no AndroidManifest; sem ele, nada
 * acontece aqui e o app chama `configure` pelo JavaScript.
 */
class AtaliaChatLifecycleListener : ApplicationLifecycleListener {
    override fun onCreate(application: Application) {
        val meta = AtaliaChatConfig.metaData(application) ?: return
        val token = meta.getString(AtaliaChatConfig.META_CHANNEL_TOKEN)?.trim().orEmpty()
        if (token.isEmpty()) return
        Atalia.configure(
            application,
            token,
            AtaliaOptions(
                baseUrl = meta.getString(AtaliaChatConfig.META_BASE_URL) ?: AtaliaOptions.DEFAULT_BASE_URL,
                customDomain = meta.getBoolean(AtaliaChatConfig.META_CUSTOM_DOMAIN, false),
                notificationIcon = AtaliaChatConfig.notificationIcon(meta),
            ),
        )
    }
}

internal object AtaliaChatConfig {
    const val META_CHANNEL_TOKEN = "ai.atalia.chat.CHANNEL_TOKEN"
    const val META_BASE_URL = "ai.atalia.chat.BASE_URL"
    const val META_CUSTOM_DOMAIN = "ai.atalia.chat.CUSTOM_DOMAIN"

    /** Ícone de notificação que o app já usa no Firebase (o expo-notifications grava este). */
    private const val META_FIREBASE_ICON = "com.google.firebase.messaging.default_notification_icon"

    fun metaData(context: Context): Bundle? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    }.getOrNull()

    fun notificationIcon(meta: Bundle?): Int? = meta?.getInt(META_FIREBASE_ICON, 0)?.takeIf { it != 0 }
}
