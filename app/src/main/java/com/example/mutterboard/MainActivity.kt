package com.example.mutterboard

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mutterboard.ui.theme.MutterboardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val REPO = "ryskeel/Mutterboard"
private val GreenAccent = Color(0xFF2E7D32)
private val BrandFont = FontFamily(Font(R.font.audiowide_regular))

private sealed interface UpdateStatus {
    object Checking : UpdateStatus
    data class UpToDate(val version: String) : UpdateStatus
    data class Available(val tag: String, val url: String) : UpdateStatus
    object Failed : UpdateStatus
}

class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* status is re-read in onResume */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MutterboardTheme {
                SetupScreen(
                    onRequestMic = { requestMicPermission() },
                    onOpenImeSettings = { openImeSettings() }
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
}

@Composable
private fun SetupScreen(
    onRequestMic: () -> Unit,
    onOpenImeSettings: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(
            MutterboardInputMethodService.PREFS,
            Context.MODE_PRIVATE
        )
    }

    var apiKey by remember {
        mutableStateOf(prefs.getString(MutterboardInputMethodService.KEY_API_KEY, "") ?: "")
    }
    var showKeyDialog by remember { mutableStateOf(false) }
    var hasMic by remember { mutableStateOf(false) }
    var imeEnabled by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    val currentVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }
    var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Checking) }
    var updateCheckTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        hasMic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        imeEnabled = isImeEnabled(context)
    }

    LaunchedEffect(updateCheckTick) {
        updateStatus = UpdateStatus.Checking
        val latest = fetchLatestRelease(REPO)
        updateStatus = when {
            latest == null -> UpdateStatus.Failed
            isNewer(latest.first, currentVersion) -> UpdateStatus.Available(latest.first, latest.second)
            else -> UpdateStatus.UpToDate(currentVersion)
        }
    }

    DisposableEffect(Unit) {
        val activity = context as? ComponentActivity
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshTick++
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    val allDone = hasMic && imeEnabled && apiKey.isNotBlank()

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 24.dp)
        ) {
            Text("Mutterboard", fontSize = 30.sp, fontFamily = BrandFont)
            Spacer(Modifier.height(4.dp))
            Text(
                "Voice keyboard",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))

            SectionHeader("Setup")
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                StepRow(
                    index = 1,
                    label = "Microphone permission",
                    done = hasMic,
                    doneText = "Granted",
                    actionLabel = "Grant",
                    showActionWhenDone = false,
                    onAction = onRequestMic
                )
                HorizontalDivider(modifier = Modifier.padding(start = 62.dp))
                StepRow(
                    index = 2,
                    label = "Enable keyboard",
                    done = imeEnabled,
                    doneText = "Enabled",
                    actionLabel = "Open settings",
                    showActionWhenDone = false,
                    onAction = onOpenImeSettings
                )
                HorizontalDivider(modifier = Modifier.padding(start = 62.dp))
                StepRow(
                    index = 3,
                    label = "Groq API key",
                    done = apiKey.isNotBlank(),
                    doneText = "Saved",
                    actionLabel = if (apiKey.isBlank()) "Add" else "Edit",
                    showActionWhenDone = true,
                    onAction = { showKeyDialog = true }
                )
            }

            if (allDone) {
                Spacer(Modifier.height(16.dp))
                CompletionBanner()
            }

            Spacer(Modifier.height(40.dp))

            SectionHeader("Updates")
            Spacer(Modifier.height(12.dp))
            UpdatesCard(
                status = updateStatus,
                currentVersion = currentVersion,
                onOpen = { url ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                onCheck = { updateCheckTick++ }
            )
        }
    }

    if (showKeyDialog) {
        ApiKeyDialog(
            initialKey = apiKey,
            onDismiss = { showKeyDialog = false },
            onSave = { newKey ->
                apiKey = newKey.trim()
                prefs.edit()
                    .putString(MutterboardInputMethodService.KEY_API_KEY, apiKey)
                    .apply()
                showKeyDialog = false
            }
        )
    }
}

@Composable
private fun ApiKeyDialog(
    initialKey: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf(initialKey) }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Groq API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
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
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))
                        )
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Need a key? Get one from Groq")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft) },
                enabled = draft.isNotBlank()
            ) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StepBadge(index: Int, done: Boolean) {
    val base = Modifier.size(24.dp).clip(CircleShape)
    val styled = if (done) {
        base.background(GreenAccent)
    } else {
        base.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
    }
    Box(modifier = styled, contentAlignment = Alignment.Center) {
        if (done) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Done",
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
        } else {
            Text(
                "$index",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun StepRow(
    index: Int,
    label: String,
    done: Boolean,
    doneText: String,
    actionLabel: String,
    showActionWhenDone: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepBadge(index, done)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(
                if (done) doneText else "Required",
                fontSize = 12.sp,
                color = if (done) GreenAccent else MaterialTheme.colorScheme.error
            )
        }
        if (!done) {
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        } else if (showActionWhenDone) {
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun CompletionBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = GreenAccent.copy(alpha = 0.12f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Setup complete", fontWeight = FontWeight.Bold, color = GreenAccent)
            Text(
                "You're all good to start muttering.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UpdatesCard(
    status: UpdateStatus,
    currentVersion: String,
    onOpen: (String) -> Unit,
    onCheck: () -> Unit
) {
    val releasesUrl = "https://github.com/$REPO/releases/latest"
    val linkLabel: String
    val linkUrl: String
    if (status is UpdateStatus.Available) {
        linkLabel = "Get it on GitHub"
        linkUrl = status.url
    } else {
        linkLabel = "View on GitHub"
        linkUrl = releasesUrl
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                when (status) {
                    is UpdateStatus.Checking ->
                        Text("Checking for updates…", fontWeight = FontWeight.Medium)
                    is UpdateStatus.UpToDate ->
                        Text("You're up to date", fontWeight = FontWeight.Medium)
                    is UpdateStatus.Available -> Text(
                        "Update available",
                        fontWeight = FontWeight.Bold,
                        color = GreenAccent
                    )
                    is UpdateStatus.Failed ->
                        Text("Couldn't check for updates", fontWeight = FontWeight.Medium)
                }

                val versionLine = when (status) {
                    is UpdateStatus.Available ->
                        "v${currentVersion.normalizedVersion()} → v${status.tag.normalizedVersion()}"
                    else -> "Version v${currentVersion.normalizedVersion()}"
                }
                Text(
                    versionLine,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TextButton(
                    onClick = { onOpen(linkUrl) },
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    Text(linkLabel)
                }
            }

            Spacer(Modifier.width(12.dp))

            if (status is UpdateStatus.Checking) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                OutlinedButton(onClick = onCheck) { Text("Check now") }
            }
        }
    }
}

private fun String.normalizedVersion(): String = trimStart('v', 'V')

private fun isNewer(latestTag: String, current: String): Boolean {
    fun parts(v: String) = v.trimStart('v', 'V').split(".", "-")
        .mapNotNull { it.toIntOrNull() }
    val a = parts(latestTag)
    val b = parts(current)
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private suspend fun fetchLatestRelease(repo: String): Pair<String, String>? =
    withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            OkHttpClient().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val json = JSONObject(body)
                val tag = json.optString("tag_name")
                val url = json.optString("html_url")
                if (tag.isBlank()) null else tag to url
            }
        }.getOrNull()
    }

private fun isImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val target = ComponentName(context, MutterboardInputMethodService::class.java)
    return imm.enabledInputMethodList.any { it.component == target }
}
