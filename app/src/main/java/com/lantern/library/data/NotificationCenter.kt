package com.lantern.library.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lantern.library.MainActivity
import com.lantern.library.R
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class LoreNoticeCategory(val key: String, val label: String) {
    LIBRARY("library", "Library updates"),
    SYNC("sync", "Cloud sync and backup"),
    DOWNLOADS("downloads", "Downloads"),
    ACCOUNT("account", "Account and security"),
    RECOMMENDATIONS("recommendations", "Recommendations"),
    REMINDERS("reminders", "Reading reminders");

    companion object {
        fun from(value: String?): LoreNoticeCategory =
            entries.firstOrNull { it.key == value?.lowercase() } ?: LIBRARY
    }
}

data class NotificationOpenRequest(val notificationId: String, val target: String)

data class LoreNotification(
    val id: String,
    val eventId: String,
    val category: LoreNoticeCategory,
    val title: String,
    val body: String,
    val target: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val readAt: Long = 0L,
    val dismissed: Boolean = false
) {
    val unread: Boolean get() = readAt == 0L && !dismissed
}

data class LoreNotificationPreferences(
    val enabled: Boolean = true,
    val inAppEnabled: Boolean = true,
    val pushEnabled: Boolean = true,
    val categories: Map<LoreNoticeCategory, Boolean> = LoreNoticeCategory.entries.associateWith {
        it != LoreNoticeCategory.REMINDERS
    }
) {
    fun categoryEnabled(category: LoreNoticeCategory): Boolean =
        enabled && (categories[category] ?: (category != LoreNoticeCategory.REMINDERS))
}

/**
 * Device-local notification history and delivery preferences. Remote event generation is supplied
 * by the trusted backend; this class is the durable client inbox and Android delivery endpoint.
 */
object NotificationCenter {
    private const val PREFS = "lore_notifications_v1"
    private const val ITEMS = "items"
    private const val SETTINGS = "settings"
    private const val TOKEN = "fcm_token"
    private const val SEEN_EVENTS = "seen_events"
    private const val CHANNEL = "lore_updates"
    private const val MAX_HISTORY = 250
    private val lock = Any()
    @Volatile private var app: Context? = null
    private val mutableItems = MutableStateFlow<List<LoreNotification>>(emptyList())
    val items: StateFlow<List<LoreNotification>> = mutableItems
    private val mutablePreferences = MutableStateFlow(LoreNotificationPreferences())
    val preferences: StateFlow<LoreNotificationPreferences> = mutablePreferences
    private val mutablePendingOpen = MutableStateFlow<NotificationOpenRequest?>(null)
    val pendingOpen: StateFlow<NotificationOpenRequest?> = mutablePendingOpen

    fun initialize(context: Context) {
        synchronized(lock) {
            if (app != null) return
            app = context.applicationContext
            createChannel(context.applicationContext)
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            mutableItems.value = decodeItems(prefs.getString(ITEMS, null))
            mutablePreferences.value = decodePreferences(prefs.getString(SETTINGS, null))
        }
    }

    fun post(
        context: Context,
        category: LoreNoticeCategory,
        title: String,
        body: String,
        eventId: String = "",
        target: String = "",
        showSystem: Boolean = true
    ): LoreNotification? {
        initialize(context)
        val prefs = mutablePreferences.value
        if (!prefs.categoryEnabled(category)) return null
        val key = eventId.ifBlank { "${category.key}:${title.trim()}:${body.trim()}" }
        val notice = synchronized(lock) {
            val storage = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = storage.getStringSet(SEEN_EVENTS, emptySet()).orEmpty()
            if (key in seen) return@synchronized null
            storage.edit().putStringSet(SEEN_EVENTS, (seen + key).takeLast(500).toSet()).apply()
            LoreNotification(
                id = Integer.toHexString(key.hashCode()),
                eventId = key,
                category = category,
                title = title.ifBlank { "Lore update" },
                body = body,
                target = target
            ).also {
                if (prefs.inAppEnabled) saveItems(mutableItems.value + it)
            }
        } ?: return null
        if (showSystem && prefs.pushEnabled) showSystem(context, notice)
        return notice
    }

    fun consumeIntent(intent: Intent?) {
        if (intent?.hasExtra("lore_notification_id") != true) return
        val id = intent.getStringExtra("lore_notification_id").orEmpty()
        val target = intent.getStringExtra("lore_notification_target").orEmpty()
        if (id.isNotBlank()) markRead(id)
        mutablePendingOpen.value = NotificationOpenRequest(id, target)
        intent.removeExtra("lore_notification_id")
        intent.removeExtra("lore_notification_target")
    }

    fun clearPendingOpen() { mutablePendingOpen.value = null }

    fun markRead(id: String) = update(id) { it.copy(readAt = System.currentTimeMillis()) }
    fun dismiss(id: String) = update(id) { it.copy(dismissed = true, readAt = it.readAt.takeIf { at -> at > 0L } ?: System.currentTimeMillis()) }
    fun markAllRead() = synchronized(lock) {
        saveItems(mutableItems.value.map { if (it.unread) it.copy(readAt = System.currentTimeMillis()) else it })
    }
    fun clearHistory() = synchronized(lock) { saveItems(emptyList()) }

    fun updatePreferences(transform: (LoreNotificationPreferences) -> LoreNotificationPreferences) {
        synchronized(lock) {
            val next = transform(mutablePreferences.value)
            mutablePreferences.value = next
            app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
                ?.putString(SETTINGS, encodePreferences(next).toString())?.apply()
        }
    }

    fun saveToken(token: String) {
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(TOKEN, token)?.apply()
    }

    fun savedToken(): String? = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(TOKEN, null)

    /** Stores this device token under the authenticated Firebase UID for trusted server delivery. */
    fun registerDeviceToken() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        refreshToken { token ->
            if (token.isNullOrBlank()) return@refreshToken
            val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
                .joinToString("") { "%02x".format(it) }
            runCatching {
                FirebaseFirestore.getInstance().collection("users").document(uid)
                    .collection("devices").document(hash)
                    .set(mapOf("token" to token, "platform" to "android", "updatedAt" to System.currentTimeMillis()))
            }
        }
    }

    fun refreshToken(onResult: (String?) -> Unit = {}) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> saveToken(token); onResult(token) }
            .addOnFailureListener { onResult(null) }
    }

    fun unreadCount(): Int = mutableItems.value.count { it.unread }

    private fun update(id: String, change: (LoreNotification) -> LoreNotification) {
        synchronized(lock) { saveItems(mutableItems.value.map { if (it.id == id) change(it) else it }) }
    }

    private fun saveItems(items: List<LoreNotification>) {
        val next = items.sortedByDescending { it.createdAt }.take(MAX_HISTORY)
        mutableItems.value = next
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(ITEMS, encodeItems(next).toString())?.apply()
    }

    private fun showSystem(context: Context, notice: LoreNotification) {
        if (!mutablePreferences.value.pushEnabled) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("lore_notification_id", notice.id)
            putExtra("lore_notification_target", notice.target)
        }
        val pending = PendingIntent.getActivity(
            context, notice.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notice.id.hashCode(), notification) }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Lore updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Library, sync, download, account and recommendation updates"
            }
        )
    }

    private fun encodeItems(items: List<LoreNotification>) = JSONArray().apply {
        items.forEach { n ->
            put(JSONObject().put("id", n.id).put("eventId", n.eventId).put("category", n.category.key)
                .put("title", n.title).put("body", n.body).put("target", n.target)
                .put("createdAt", n.createdAt).put("readAt", n.readAt).put("dismissed", n.dismissed))
        }
    }

    private fun decodeItems(raw: String?): List<LoreNotification> = runCatching {
        val a = JSONArray(raw ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            LoreNotification(o.optString("id"), o.optString("eventId"), LoreNoticeCategory.from(o.optString("category")),
                o.optString("title"), o.optString("body"), o.optString("target"),
                o.optLong("createdAt"), o.optLong("readAt"), o.optBoolean("dismissed"))
        }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    private fun encodePreferences(p: LoreNotificationPreferences) = JSONObject().apply {
        put("enabled", p.enabled); put("inAppEnabled", p.inAppEnabled); put("pushEnabled", p.pushEnabled)
        put("categories", JSONObject().apply { p.categories.forEach { (category, enabled) -> put(category.key, enabled) } })
    }

    private fun decodePreferences(raw: String?): LoreNotificationPreferences = runCatching {
        val o = JSONObject(raw ?: return LoreNotificationPreferences())
        val categories = o.optJSONObject("categories")
        LoreNotificationPreferences(
            enabled = o.optBoolean("enabled", true),
            inAppEnabled = o.optBoolean("inAppEnabled", true),
            pushEnabled = o.optBoolean("pushEnabled", true),
            categories = LoreNoticeCategory.entries.associateWith { c ->
                categories?.optBoolean(c.key, c != LoreNoticeCategory.REMINDERS) ?: (c != LoreNoticeCategory.REMINDERS)
            }
        )
    }.getOrDefault(LoreNotificationPreferences())
}
