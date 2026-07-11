package com.saqib.wapdaalarm

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.google.firebase.messaging.FirebaseMessaging
import java.security.MessageDigest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WapdaAlarmApp() }
    }
}

@Composable
private fun WapdaAlarmApp() {
    val context = LocalContext.current
    val prefs = remember { PrefsManager(context) }
    var permissions by remember { mutableStateOf(PermissionState.read(context)) }
    var alarmRunning by remember { mutableStateOf(prefs.isAlarmRunning) }
    var token by remember { mutableStateOf(prefs.fcmToken) }
    var registered by remember { mutableStateOf(prefs.isRegistered) }
    var lastEvent by remember { mutableStateOf(prefs.lastEvent) }
    var registrationStatus by remember { mutableStateOf(prefs.lastRegistrationStatus) }
    var phoneRole by remember { mutableStateOf(prefs.phoneRole) }
    var alertModes by remember { mutableStateOf(readAlertModes(prefs)) }
    var restoredNotifications by remember { mutableStateOf(prefs.restoredNotificationsEnabled) }
    var lastServerPollAt by remember { mutableStateOf(prefs.lastServerPollAt) }
    var lastLineFailState by remember { mutableStateOf(prefs.lastLineFailState) }
    var lastPushResult by remember { mutableStateOf(prefs.lastPushResult) }

    fun refresh() {
        permissions = PermissionState.read(context)
        alarmRunning = prefs.isAlarmRunning
        token = prefs.fcmToken
        registered = prefs.isRegistered
        lastEvent = prefs.lastEvent
        registrationStatus = prefs.lastRegistrationStatus
        phoneRole = prefs.phoneRole
        alertModes = readAlertModes(prefs)
        restoredNotifications = prefs.restoredNotificationsEnabled
        lastServerPollAt = prefs.lastServerPollAt
        lastLineFailState = prefs.lastLineFailState
        lastPushResult = prefs.lastPushResult
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(AlarmActions.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Color(0xFF176B5D),
            secondary = Color(0xFF3E5C76),
            background = Color(0xFFF6F7F9),
            surface = Color.White,
            error = Color(0xFFC53030)
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Header(registered = registered, alarmRunning = alarmRunning)
                AlarmControls(
                    alarmRunning = alarmRunning,
                    onStop = {
                        context.startService(Intent(context, AlarmForegroundService::class.java).setAction(AlarmActions.ACTION_STOP))
                        refresh()
                    },
                    onTest = {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, AlarmForegroundService::class.java)
                                .setAction(AlarmActions.ACTION_TEST)
                                .putExtra(AlarmActions.EXTRA_DURATION_MS, AlarmActions.TEST_DURATION_MS)
                        )
                        refresh()
                    }
                )
                PhoneRolePanel(
                    role = phoneRole,
                    onRoleChanged = {
                        prefs.applyRole(it)
                        refresh()
                    }
                )
                AlertRulesPanel(
                    alertModes = alertModes,
                    restoredNotifications = restoredNotifications,
                    onModeChanged = { alarm, mode ->
                        prefs.setAlertMode(alarm, mode)
                        refresh()
                    },
                    onRestoredChanged = {
                        prefs.restoredNotificationsEnabled = it
                        refresh()
                    }
                )
                ServerStatusPanel(
                    lastPollAt = lastServerPollAt,
                    lineFailState = lastLineFailState,
                    pushResult = lastPushResult,
                )
                SetupChecklist(
                    permissions = permissions,
                    onBattery = { context.openBatteryOptimizationSettings() },
                    onPostNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onDnd = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                )
                ServerRegistrationPanel(
                    prefs = prefs,
                    token = token,
                    status = registrationStatus,
                    firebaseConfigured = FirebaseConfig.isConfigured(context),
                    onSave = { refresh() },
                    onFetchToken = { fetchToken(context) { refresh() } },
                    onRegister = {
                        val currentToken = prefs.fcmToken
                        if (currentToken.isBlank()) {
                            fetchToken(context) {
                                refresh()
                            }
                        } else {
                            subscribeToAlertTopic(context) { refresh() }
                        }
                    }
                )
                OemPanel()
                InfoPanel(title = "Last event", body = lastEvent)
            }
        }
    }
}

@Composable
private fun Header(registered: Boolean, alarmRunning: Boolean) {
    val text = when {
        alarmRunning -> "Alarm ringing"
        registered -> "Connected"
        else -> "Setup pending"
    }
    val detail = when {
        alarmRunning -> "A critical power alert is active"
        registered -> "Watching grid, battery, load, solar and cloud health"
        else -> "Connect this phone to Firebase alerts"
    }
    val color = when {
        alarmRunning -> Color(0xFFC53030)
        registered -> Color(0xFF176B5D)
        else -> Color(0xFF9A3412)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("WAPDA Alarm", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF374151))
        StatusPill(text = text, color = color)
    }
}

@Composable
private fun AlertRulesPanel(
    alertModes: Map<String, String>,
    restoredNotifications: Boolean,
    onModeChanged: (String, String) -> Unit,
    onRestoredChanged: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Alert rules", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        AlertCatalog.rules.forEach { rule ->
            AlertRuleRow(
                title = rule.title,
                subtitle = rule.subtitle,
                mode = alertModes[rule.alarm] ?: rule.defaultDadMode,
                onModeChanged = { onModeChanged(rule.alarm, it) }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Restored notification", fontWeight = FontWeight.SemiBold)
                Text("Show when an alert clears", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
            }
            Switch(checked = restoredNotifications, onCheckedChange = onRestoredChanged)
        }
    }
}

@Composable
private fun PhoneRolePanel(role: String, onRoleChanged: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Phone role", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ModeButton("Dad phone", role == AlertCatalog.ROLE_DAD, Modifier.weight(1f)) {
                onRoleChanged(AlertCatalog.ROLE_DAD)
            }
            ModeButton("Monitor", role == AlertCatalog.ROLE_MONITOR, Modifier.weight(1f)) {
                onRoleChanged(AlertCatalog.ROLE_MONITOR)
            }
        }
        InfoPanel(
            title = if (role == AlertCatalog.ROLE_DAD) "Loud alarm profile" else "Notification profile",
            body = if (role == AlertCatalog.ROLE_DAD) {
                "Grid failure, battery-low, and high-load alerts ring loudly. Solar loss, stale data, and summary stay as notifications."
            } else {
                "This phone receives the same cloud alerts as quiet notifications by default."
            }
        )
    }
}

@Composable
private fun ServerStatusPanel(lastPollAt: String, lineFailState: String, pushResult: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Server status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusLine("Last poll", lastPollAt)
            StatusLine("LINE_FAIL", lineFailState)
            StatusLine("Last push", pushResult)
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(0.9f), fontWeight = FontWeight.SemiBold)
        Text(value, modifier = Modifier.weight(1.4f), color = Color(0xFF4B5563))
    }
}

@Composable
private fun AlertRuleRow(
    title: String,
    subtitle: String,
    mode: String,
    onModeChanged: (String) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
                }
                ModePill(mode)
            }
            HorizontalDivider(color = Color(0xFFE5E7EB))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ModeButton("Alarm", mode == AlertMode.ALARM, Modifier.weight(1f)) { onModeChanged(AlertMode.ALARM) }
                ModeButton("Notify", mode == AlertMode.NOTIFICATION, Modifier.weight(1f)) { onModeChanged(AlertMode.NOTIFICATION) }
                ModeButton("Off", mode == AlertMode.OFF, Modifier.weight(1f)) { onModeChanged(AlertMode.OFF) }
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.height(42.dp)) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(42.dp)) {
            Text(label)
        }
    }
}

@Composable
private fun ModePill(mode: String) {
    val (label, color) = when (mode) {
        AlertMode.NOTIFICATION -> "Notify" to Color(0xFF3E5C76)
        AlertMode.OFF -> "Off" to Color(0xFF6B7280)
        else -> "Alarm" to Color(0xFFC53030)
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = color, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AlarmControls(alarmRunning: Boolean, onStop: () -> Unit, onTest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onStop,
            enabled = alarmRunning,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC53030))
        ) {
            Text("STOP ALARM", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onTest,
            enabled = !alarmRunning,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Test Alarm for 10 seconds")
        }
    }
}

@Composable
private fun SetupChecklist(
    permissions: PermissionState,
    onBattery: () -> Unit,
    onPostNotifications: () -> Unit,
    onDnd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Phone permissions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        ChecklistRow("Disable battery optimization", permissions.ignoringBatteryOptimizations, onBattery)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ChecklistRow("Allow app notifications", permissions.postNotificationsAllowed, onPostNotifications)
        }
        ChecklistRow("Allow Do Not Disturb access", permissions.notificationPolicyAccess, onDnd)
    }
}

@Composable
private fun ServerRegistrationPanel(
    prefs: PrefsManager,
    token: String,
    status: String,
    firebaseConfigured: Boolean,
    onSave: () -> Unit,
    onFetchToken: () -> Unit,
    onRegister: () -> Unit,
) {
    var serverUrl by remember { mutableStateOf(prefs.serverUrl) }
    var secret by remember { mutableStateOf(prefs.registrationSecret) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Cloud alert subscription", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (!firebaseConfigured) {
            InfoPanel(
                title = "Firebase config missing",
                body = "Add google-services.json before registering this phone."
            )
        }
        InfoPanel(
            title = "Delivery",
            body = "The phone subscribes to power alerts through Firebase. Server registration is optional."
        )
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text("Registration secret") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = {
                prefs.saveServerSettings(serverUrl, secret)
                onSave()
            }, modifier = Modifier.weight(1f)) {
                Text("Save")
            }
            Button(onClick = {
                prefs.saveServerSettings(serverUrl, secret)
                onSave()
                onFetchToken()
            }, enabled = firebaseConfigured, modifier = Modifier.weight(1f)) {
                Text("Connect")
            }
        }
        Button(
            onClick = {
                prefs.saveServerSettings(serverUrl, secret)
                onRegister()
            },
            enabled = firebaseConfigured,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reconnect Alerts")
        }
        InfoPanel(title = "Registration", body = status)
        SelectionContainer {
            Text(
                text = if (token.isBlank()) "FCM token not available yet" else token,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF4B5563)
            )
        }
    }
}

@Composable
private fun ChecklistRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onClick() })
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        OutlinedButton(onClick = onClick) {
            Text(if (checked) "Open" else "Grant")
        }
    }
}

@Composable
private fun OemPanel() {
    val guidance = remember { OemGuidanceProvider.current() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFFBEB), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Phone battery settings", fontWeight = FontWeight.SemiBold)
        Text(
            "Enable Autostart, unrestricted battery use, and lock this app in Recents on Xiaomi, Oppo, Vivo, Realme and similar phones.",
            style = MaterialTheme.typography.bodyMedium
        )
        guidance?.let {
            Text(it.brand, fontWeight = FontWeight.SemiBold)
            it.instructions.forEach { line -> Text("- $line", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun InfoPanel(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF4B5563))
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text = text, color = color, fontWeight = FontWeight.SemiBold)
    }
}

private data class PermissionState(
    val ignoringBatteryOptimizations: Boolean,
    val postNotificationsAllowed: Boolean,
    val notificationPolicyAccess: Boolean,
) {
    companion object {
        fun read(context: Context): PermissionState {
            val packageName = context.packageName
            val powerManager = context.getSystemService<PowerManager>()
            val notificationManager = context.getSystemService<NotificationManager>()
            return PermissionState(
                ignoringBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(packageName) == true,
                postNotificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                notificationPolicyAccess = notificationManager?.isNotificationPolicyAccessGranted == true,
            )
        }
    }
}

private object FirebaseConfig {
    fun isConfigured(context: Context): Boolean {
        val appId = runCatching { context.getString(R.string.google_app_id) }.getOrDefault("")
        val apiKey = runCatching { context.getString(R.string.google_api_key) }.getOrDefault("")
        return appId.isNotBlank() && apiKey.isNotBlank() && "replace" !in appId.lowercase() && "replace" !in apiKey.lowercase()
    }
}

private fun fetchToken(context: Context, onDone: () -> Unit) {
    val prefs = PrefsManager(context)
    if (!FirebaseConfig.isConfigured(context)) {
        prefs.lastRegistrationStatus = "Firebase config is missing"
        onDone()
        return
    }
    if (!RegistrationSecret.isValid(prefs.registrationSecret)) {
        prefs.isRegistered = false
        prefs.lastRegistrationStatus = "Wrong registration secret"
        onDone()
        return
    }
    FirebaseMessaging.getInstance().token
        .addOnCompleteListener { task ->
            if (task.isSuccessful) {
                prefs.fcmToken = task.result.orEmpty()
                subscribeToAlertTopic(context, onDone)
                return@addOnCompleteListener
            } else {
                prefs.lastRegistrationStatus = "FCM token failed: ${task.exception?.message}"
            }
            onDone()
        }
}

private fun subscribeToAlertTopic(context: Context, onDone: () -> Unit) {
    val prefs = PrefsManager(context)
    if (!RegistrationSecret.isValid(prefs.registrationSecret)) {
        prefs.isRegistered = false
        prefs.lastRegistrationStatus = "Wrong registration secret"
        onDone()
        return
    }
    FirebaseMessaging.getInstance().subscribeToTopic(AlarmActions.FCM_TOPIC)
        .addOnCompleteListener { task ->
            prefs.isRegistered = task.isSuccessful
            prefs.lastRegistrationStatus = if (task.isSuccessful) {
                "Connected - watching for power alerts"
            } else {
                "Cloud subscription failed: ${task.exception?.message}"
            }
            onDone()
        }
}

object RegistrationSecret {
    fun isValid(secret: String): Boolean =
        sha256(secret.trim()) == AlarmActions.REGISTRATION_SECRET_SHA256

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

private fun readAlertModes(prefs: PrefsManager): Map<String, String> =
    AlertCatalog.rules.associate { it.alarm to prefs.alertMode(it.alarm) }

private fun Context.openBatteryOptimizationSettings() {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:$packageName"))
    runCatching { startActivity(intent) }
        .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
