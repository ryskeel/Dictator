package com.example.dictator

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
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
    ) { /* status is re-read in onResume */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DictatorTheme {
                SetupScreen(
                    onRequestMic = { requestMicPermission() },
                    onOpenImeSettings = { openImeSettings() },
                    onPickIme = { pickIme() }
                )
            }
        }
    }

    private fun requestMicPermission() {
        requestPermissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    private fun openImeSettings() {
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    private fun pickIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }
}

@Composable
private fun SetupScreen(
    onRequestMic: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onPickIme: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(
            DictatorInputMethodService.PREFS,
            Context.MODE_PRIVATE
        )
    }

    var apiKey by remember {
        mutableStateOf(prefs.getString(DictatorInputMethodService.KEY_API_KEY, "") ?: "")
    }
    var showKey by remember { mutableStateOf(false) }
    var hasMic by remember { mutableStateOf(false) }
    var imeEnabled by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        hasMic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        imeEnabled = isImeEnabled(context)
    }

    DisposableEffect(Unit) {
        val activity = context as? ComponentActivity
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshTick++
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
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
                "Voice keyboard. Switch to it inside any app and it starts listening " +
                        "automatically. Tap the mic to stop, transcribe, paste, and " +
                        "switch back to your previous keyboard.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            StepRow(
                step = "1",
                label = "Microphone permission",
                done = hasMic,
                actionLabel = "Grant",
                onAction = onRequestMic
            )
            StepRow(
                step = "2",
                label = "Enable Dictator in Languages & input",
                done = imeEnabled,
                actionLabel = "Open settings",
                onAction = onOpenImeSettings
            )
            StepRow(
                step = "3",
                label = "Open keyboard picker (use any time you want to switch to it)",
                done = false,
                showDone = false,
                actionLabel = "Show picker",
                onAction = onPickIme
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
                    prefs.edit()
                        .putString(DictatorInputMethodService.KEY_API_KEY, apiKey.trim())
                        .apply()
                },
                enabled = apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save API key")
            }
        }
    }
}

@Composable
private fun StepRow(
    step: String,
    label: String,
    done: Boolean,
    showDone: Boolean = true,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Step $step", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, fontWeight = FontWeight.Medium)
            if (showDone) {
                Text(
                    if (done) "Granted" else "Required",
                    fontSize = 12.sp,
                    color = if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        }
        OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}

private fun isImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val target = ComponentName(context, DictatorInputMethodService::class.java)
    return imm.enabledInputMethodList.any { it.component == target }
}
