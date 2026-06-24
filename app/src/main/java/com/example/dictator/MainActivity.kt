package com.example.dictator

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dictator.ui.theme.DictatorTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { checkAndStartService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DictatorTheme {
                SetupScreen(
                    onRequestRuntimePermissions = { requestRuntimePermissions() },
                    onOpenOverlaySettings = { openOverlaySettings() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onApiKeySaved = { DictationService.refreshApiKey(this); checkAndStartService() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndStartService()
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionsLauncher.launch(perms.toTypedArray())
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun checkAndStartService() {
        val allReady = hasRecordAudio() && hasOverlay() && hasAccessibility() && hasApiKey()
        if (allReady) DictationService.start(this)
    }

    private fun hasRecordAudio() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasOverlay() = Settings.canDrawOverlays(this)

    private fun hasAccessibility(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("${packageName}/${DictationAccessibilityService::class.java.name}")
    }

    private fun hasApiKey(): Boolean {
        val key = getSharedPreferences(DictationService.PREFS, MODE_PRIVATE)
            .getString(DictationService.KEY_API_KEY, "") ?: ""
        return key.isNotEmpty()
    }
}

@Composable
private fun SetupScreen(
    onRequestRuntimePermissions: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onApiKeySaved: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(DictationService.PREFS, android.content.Context.MODE_PRIVATE)
    }

    var apiKey by remember {
        mutableStateOf(prefs.getString(DictationService.KEY_API_KEY, "") ?: "")
    }
    var showKey by remember { mutableStateOf(false) }
    var hasRecordAudio by remember { mutableStateOf(false) }
    var hasOverlay by remember { mutableStateOf(false) }
    var hasAccessibility by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasRecordAudio = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        hasOverlay = Settings.canDrawOverlays(context)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        hasAccessibility = enabledServices.contains(
            "${context.packageName}/${DictationAccessibilityService::class.java.name}"
        )
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
        ) {
            Text("Dictator", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(
                "Shake your phone while a text field is focused to start dictating. " +
                        "Tap the pill above the keyboard to stop.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            PermissionRow(
                label = "Microphone",
                granted = hasRecordAudio,
                onGrant = onRequestRuntimePermissions
            )
            PermissionRow(
                label = "Draw over apps",
                granted = hasOverlay,
                onGrant = onOpenOverlaySettings
            )
            PermissionRow(
                label = "Accessibility service",
                granted = hasAccessibility,
                onGrant = onOpenAccessibilitySettings
            )

            HorizontalDivider()

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Groq API key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    prefs.edit().putString(DictationService.KEY_API_KEY, apiKey.trim()).apply()
                    onApiKeySaved()
                },
                enabled = apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save & start service")
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, fontWeight = FontWeight.Medium)
            Text(
                if (granted) "Granted" else "Required",
                fontSize = 12.sp,
                color = if (granted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
        if (!granted) {
            OutlinedButton(onClick = onGrant) { Text("Grant") }
        }
    }
}
