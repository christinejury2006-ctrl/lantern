package com.lantern.library.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lantern.library.data.LoreNoticeCategory
import com.lantern.library.data.LoreNotification
import com.lantern.library.data.NotificationCenter
import java.text.DateFormat
import java.util.Date

@Composable
fun NotificationsScreen(onBack: () -> Unit, onOpenTarget: (String) -> Unit) {
    val items by NotificationCenter.items.collectAsState()
    val prefs by NotificationCenter.preferences.collectAsState()
    var settings by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pushPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        NotificationCenter.updatePreferences { it.copy(pushEnabled = granted) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Notifications", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { settings = true }) { Text("Settings") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { NotificationCenter.markAllRead() }) { Text("Mark all read") }
            TextButton(onClick = { clearConfirm = true }) { Text("Clear history") }
            Text("${items.count { it.unread }} unread", modifier = Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium)
        }
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.Notifications, null, modifier = Modifier.size(42.dp))
                Text("You're all caught up", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text("Library, sync and account updates will appear here.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.id }) { notice ->
                    NoticeCard(notice, onOpen = {
                        NotificationCenter.markRead(notice.id)
                        if (notice.target.isNotBlank()) onOpenTarget(notice.target)
                    }, onDismiss = { NotificationCenter.dismiss(notice.id) })
                }
            }
        }
    }
    if (settings) {
        AlertDialog(
            onDismissRequest = { settings = false },
            title = { Text("Notification settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PreferenceSwitch("All notifications", prefs.enabled) { enabled -> NotificationCenter.updatePreferences { current -> current.copy(enabled = enabled) } }
                    PreferenceSwitch("In-app inbox", prefs.inAppEnabled) { NotificationCenter.updatePreferences { it.copy(inAppEnabled = it) } }
                    PreferenceSwitch("Android push", prefs.pushEnabled) { enabled ->
                        if (!enabled) NotificationCenter.updatePreferences { it.copy(pushEnabled = false) }
                        else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) pushPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else NotificationCenter.updatePreferences { it.copy(pushEnabled = true) }
                    }
                    LoreNoticeCategory.entries.forEach { category ->
                        PreferenceSwitch(category.label, prefs.categories[category] ?: (category != LoreNoticeCategory.REMINDERS)) { enabled ->
                            NotificationCenter.updatePreferences { it.copy(categories = it.categories + (category to enabled)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } }
        )
    }
    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("Clear notification history?") },
            text = { Text("This removes the saved inbox on this device. It does not change your library or account.") },
            confirmButton = { TextButton(onClick = { NotificationCenter.clearHistory(); clearConfirm = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("Keep") } }
        )
    }
}

@Composable
private fun NoticeCard(notice: LoreNotification, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = if (notice.unread) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(notice.category.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                if (notice.unread) Spacer(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(notice.createdAt)), style = MaterialTheme.typography.labelSmall)
            }
            Text(notice.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
            Text(notice.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun PreferenceSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
