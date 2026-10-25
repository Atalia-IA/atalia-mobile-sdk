package ai.atalia.chat.internal

import ai.atalia.chat.AtaliaChatActivity
import ai.atalia.chat.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder

/**
 * Notificação da Atalia mostrada pelo próprio SDK quando o app está aberto (com o app em segundo
 * plano quem mostra é o sistema, a partir do bloco `notification` do Firebase). Mesmo canal, mesma
 * tag por conversa: a nova substitui a anterior, como no envio do servidor.
 */
internal object AtaliaNotifications {
    const val CHANNEL_ID = "atalia_chat"

    /**
     * Cria o canal "atalia_chat". Precisa existir antes do primeiro push: sem ele, a notificação
     * que o sistema mostra com o app fechado cai no canal genérico do Firebase.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.atalia_chat_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        )
        manager.createNotificationChannel(channel)
    }

    /** @return false quando o usuário bloqueou as notificações do app (nada a fazer). */
    fun show(context: Context, payload: PushPayload, title: String?, body: String?, smallIcon: Int): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        ensureChannel(context)

        val tag = payload.conversationId?.let { "${TAG_PREFIX}conv-$it" } ?: "${TAG_PREFIX}chat"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.atalia_chat_notification_fallback_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openChatIntent(context, payload.conversationId, tag.hashCode()))
            .build()
        return try {
            manager.notify(tag, NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // Android 13+ sem a permissão POST_NOTIFICATIONS concedida.
            false
        }
    }

    /**
     * Tira da bandeja as notificações da Atalia, as do SDK e as que o sistema mostrou a partir do
     * Firebase (mesma tag). As do próprio app ficam.
     */
    fun cancelAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications
            .filter { it.tag?.startsWith(TAG_PREFIX) == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    /** Toque: abre o chat por cima do app (o "voltar" leva ao app, não a uma tela vazia). */
    private fun openChatIntent(context: Context, conversationId: Long?, requestCode: Int): PendingIntent? {
        val chat = Intent(context, AtaliaChatActivity::class.java)
        conversationId?.let { chat.putExtra(AtaliaChatActivity.EXTRA_CONVERSATION_ID, it) }
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val stack = TaskStackBuilder.create(context)
        if (launcher != null) stack.addNextIntent(launcher)
        stack.addNextIntent(chat)
        return stack.getPendingIntent(requestCode, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private const val NOTIFICATION_ID = 1
    private const val TAG_PREFIX = "atalia-"
}
