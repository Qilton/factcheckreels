package com.example.reelcheck.ui.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.reelcheck.accessibility.ReelAccessibilityService
import com.example.reelcheck.service.OverlayService
import com.example.reelcheck.network.FactCheckApiClient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.reelcheck.capture.AudioTestPlayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var hasOverlayPermission by remember {
        mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true)
    }

    var hasRecordAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val isAccessibilityConnected by ReelAccessibilityService.isServiceConnected.collectAsState()
    val isOverlayRunning by OverlayService.isOverlayRunning.collectAsState()
    val isMediaProjectionReady by OverlayService.isMediaProjectionReady.collectAsState()
    val latestExtracted by ReelAccessibilityService.latestExtracted.collectAsState()
    val latestScreenshot by OverlayService.latestScreenshot.collectAsState()
    val latestAudioClip by OverlayService.latestAudioClip.collectAsState()
    val latestFactCheck by OverlayService.latestFactCheck.collectAsState()
    val isFactChecking by OverlayService.isFactChecking.collectAsState()

    var isBackendHealthy by remember { mutableStateOf<Boolean?>(null) }
    var isCheckingHealth by remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences("reelcheck_prefs", Context.MODE_PRIVATE) }
    var backendUrlText by remember {
        val saved = prefs.getString("backend_url", FactCheckApiClient.baseUrl) ?: "http://10.0.2.2:3000"
        FactCheckApiClient.baseUrl = saved
        mutableStateOf(saved)
    }
    var latestBudget by remember { mutableStateOf<com.example.reelcheck.network.BudgetStats?>(null) }

    var isTestTonePlaying by remember { mutableStateOf(false) }
    var toneType by remember { mutableStateOf("") }

    // Refresh overlay permission when returning from settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(context)
                } else true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val eventLogs = remember { mutableStateListOf<String>() }

    // MediaProjection permission launcher
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            OverlayService.setMediaProjectionPermission(result.resultCode, result.data!!)
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            eventLogs.add(0, "[$timeStr] MediaProjection screen capture authorized")
        }
    }

    // Listen to bubble tap events
    LaunchedEffect(Unit) {
        OverlayService.bubbleTapEvents.collect { timestamp ->
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
            eventLogs.add(0, "[$timeStr] Bubble tapped!")
        }
    }

    // Listen to accessibility extractions
    LaunchedEffect(Unit) {
        ReelAccessibilityService.latestExtracted.collect { info ->
            if (info != null) {
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(info.timestamp))
                eventLogs.add(0, "[$timeStr] Extracted: @${info.username ?: "none"} | \"${info.caption?.take(30) ?: "none"}...\"")
            }
        }
    }

    // Listen to screenshot capture events
    LaunchedEffect(Unit) {
        OverlayService.latestScreenshot.collect { shot ->
            if (shot != null) {
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(shot.timestamp))
                val kb = shot.jpegBytes.size / 1024
                eventLogs.add(0, "[$timeStr] Screenshot in RAM: ${shot.width}x${shot.height} ($kb KB)")
            }
        }
    }

    // Listen to audio capture events
    LaunchedEffect(Unit) {
        OverlayService.latestAudioClip.collect { clip ->
            if (clip != null) {
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(clip.timestamp))
                val wavKb = clip.wavBytes.size / 1024
                val statusStr = if (clip.isSilent) "SILENT/OPT-OUT" else "ACTIVE SOUND"
                eventLogs.add(0, "[$timeStr] Audio in RAM: ${clip.durationSeconds}s ($wavKb KB WAV, RMS=${clip.rmsAmplitude.toInt()}, $statusStr)")
            }
        }
    }

    // Audio permission launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordAudioPermission = granted
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        eventLogs.add(0, "[$timeStr] RECORD_AUDIO permission: " + if (granted) "GRANTED" else "DENIED")
    }

    // Permission launcher for Android 13+ notifications
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    fun pingBackend() {
        coroutineScope.launch {
            isCheckingHealth = true
            val res = FactCheckApiClient.checkHealth()
            isBackendHealthy = res.isSuccess
            latestBudget = res.getOrNull()?.budget
            isCheckingHealth = false
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (res.isSuccess) {
                eventLogs.add(0, "[$timeStr] Backend health OK: ${FactCheckApiClient.baseUrl}")
            } else {
                eventLogs.add(0, "[$timeStr] Backend error: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    LaunchedEffect(Unit) {
        pingBackend()
    }

    LaunchedEffect(Unit) {
        OverlayService.latestFactCheck.collect { response ->
            if (response != null) {
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val cachedStr = if (response.cached) " [CACHED]" else ""
                eventLogs.add(0, "[$timeStr] Fact Check Response$cachedStr: ${response.claims.size} claims, notCheckable=${response.notCheckable != null}")
            }
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ReelCheck Dashboard", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Spike 1 Card: Floating Overlay
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Spike 1: Floating Overlay", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Box(
                            modifier = Modifier
                                .background(if (isOverlayRunning) Color(0xFF10B981) else Color(0xFF6B7280), CircleShape)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(if (isOverlayRunning) "ACTIVE" else "STOPPED", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (!hasOverlayPermission) {
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant Overlay Permission")
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { OverlayService.start(context) },
                                enabled = !isOverlayRunning,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Start Bubble")
                            }
                            OutlinedButton(
                                onClick = { OverlayService.stop(context) },
                                enabled = isOverlayRunning,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Stop Bubble")
                            }
                        }
                    }
                }
            }

            // Spike 2 Card: Accessibility Service
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAccessibilityConnected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Spike 2: AccessibilityService", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Box(
                            modifier = Modifier
                                .background(if (isAccessibilityConnected) Color(0xFF10B981) else Color(0xFFEF4444), CircleShape)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                if (isAccessibilityConnected) "CONNECTED" else "DISABLED",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (!isAccessibilityConnected) {
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Enable Accessibility in Settings")
                        }
                    } else {
                        Button(
                            onClick = { ReelAccessibilityService.extractCurrentScreen() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Extract Current Screen Now")
                        }
                    }

                    if (latestExtracted != null) {
                        val info = latestExtracted!!
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Extracted Reel Data:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("Package: ${info.packageName}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text("Username: ${info.username ?: "<Not Found>"}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            Text("Caption: ${info.caption ?: "<Not Found>"}", fontSize = 12.sp)
                            Text("On-Screen Text Count: ${info.onScreenTexts.size}", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }
            }

            // Spike 3 Card: MediaProjection Screenshot
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isMediaProjectionReady) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Spike 3: MediaProjection", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Box(
                            modifier = Modifier
                                .background(if (isMediaProjectionReady) Color(0xFF10B981) else Color(0xFFF59E0B), CircleShape)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                if (isMediaProjectionReady) "READY" else "AUTH NEEDED",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = "Captures 1 screenshot into memory upon tapping bubble. In-memory only (Rule 4).",
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (!isMediaProjectionReady) {
                        Button(
                            onClick = {
                                val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                projectionLauncher.launch(mpManager.createScreenCaptureIntent())
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Authorize Screen Capture")
                        }
                    }

                    if (latestScreenshot != null) {
                        val shot = latestScreenshot!!
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Latest In-Memory Screenshot:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("Dimensions: ${shot.width} x ${shot.height} px", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text("RAM Size: ${shot.jpegBytes.size} bytes (${shot.jpegBytes.size / 1024} KB)", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            Text("Storage: Processed in memory only (never written to disk)", fontSize = 11.sp, color = Color(0xFF059669))
                        }
                    }
                }
            }

            // Spike 4 Card: AudioPlaybackCapture
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isMediaProjectionReady && hasRecordAudioPermission) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Spike 4: AudioPlaybackCapture", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isMediaProjectionReady && hasRecordAudioPermission) Color(0xFF10B981) else Color(0xFFF59E0B),
                                    CircleShape
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                if (isMediaProjectionReady && hasRecordAudioPermission) "ACTIVE" else "SETUP NEEDED",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = "Maintains 5-10s rolling PCM buffer in RAM via MediaProjection. Strict in-memory only (Rule 4).",
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (!hasRecordAudioPermission) {
                        Button(
                            onClick = { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant RECORD_AUDIO Permission")
                        }
                    }

                    // Test sound playback controls to test Allowed vs Opt-out
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Audio Stream Simulator (For Verification):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (isTestTonePlaying && toneType == "ALLOWED") {
                                        AudioTestPlayer.stop()
                                        isTestTonePlaying = false
                                        toneType = ""
                                    } else {
                                        AudioTestPlayer.playTone(optOut = false, frequencyHz = 440.0)
                                        isTestTonePlaying = true
                                        toneType = "ALLOWED"
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isTestTonePlaying && toneType == "ALLOWED") Color(0xFFEF4444) else MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(if (isTestTonePlaying && toneType == "ALLOWED") "Stop Tone" else "Play Media Audio")
                            }

                            Button(
                                onClick = {
                                    if (isTestTonePlaying && toneType == "OPTOUT") {
                                        AudioTestPlayer.stop()
                                        isTestTonePlaying = false
                                        toneType = ""
                                    } else {
                                        AudioTestPlayer.playTone(optOut = true, frequencyHz = 880.0)
                                        isTestTonePlaying = true
                                        toneType = "OPTOUT"
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isTestTonePlaying && toneType == "OPTOUT") Color(0xFFEF4444) else Color(0xFFD97706)
                                )
                            ) {
                                Text(if (isTestTonePlaying && toneType == "OPTOUT") "Stop Tone" else "Play Opt-Out Stream")
                            }
                        }

                        if (isMediaProjectionReady && hasRecordAudioPermission) {
                            OutlinedButton(
                                onClick = {
                                    OverlayService.captureAudioNow(5)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Capture Recent 5s Buffer Now")
                            }
                        }
                    }

                    if (latestAudioClip != null) {
                        val clip = latestAudioClip!!
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Latest In-Memory Audio Buffer:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("Format: ${clip.sampleRate} Hz Mono, 16-bit PCM (WAV)", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text("Duration: ${clip.durationSeconds}s (${clip.pcmByteCount / 1024} KB PCM, ${clip.wavBytes.size / 1024} KB WAV in RAM)", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("RMS Amplitude: ${clip.rmsAmplitude.toInt()}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (clip.isSilent) Color(0xFFEF4444) else Color(0xFF10B981),
                                            CircleShape
                                        )
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        if (clip.isSilent) "SILENT / OPT-OUT" else "ACTIVE SOUND",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text("Storage: Processed in memory only (never written to disk)", fontSize = 11.sp, color = Color(0xFF059669))
                        }
                    }
                }
            }

            // Mock Reel Screen Container
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Mock Instagram Reel Simulator", color = Color(0xFFA5B4FC), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(
                        text = "@nasa_astronomy",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "New James Webb observations have detected heavy sulfur molecules in the atmosphere of an exoplanet 120 light years away. This confirms models of active atmospheric volcanism.",
                        color = Color(0xFFE2E8F0),
                        fontSize = 13.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("❤️ 124K", color = Color.LightGray, fontSize = 12.sp)
                        Text("💬 1,420", color = Color.LightGray, fontSize = 12.sp)
                        Text("↗️ 8.9K", color = Color.LightGray, fontSize = 12.sp)
                    }
                }
            }

            // Milestone M2 Card: Wire Up & Live Fact Check
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Milestone M2: Wire Up", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Box(
                            modifier = Modifier
                                .background(
                                    when (isBackendHealthy) {
                                        true -> Color(0xFF10B981)
                                        false -> Color(0xFFEF4444)
                                        null -> Color(0xFF6B7280)
                                    },
                                    CircleShape
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = when (isBackendHealthy) {
                                    true -> "BACKEND OK"
                                    false -> "UNREACHABLE"
                                    null -> "CHECKING..."
                                },
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = "Configured backend: ${FactCheckApiClient.baseUrl}. Set LAN IP or production host below to sideload & test with friends.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedTextField(
                        value = backendUrlText,
                        onValueChange = { backendUrlText = it },
                        label = { Text("Backend URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            TextButton(onClick = {
                                val trimmed = backendUrlText.trim()
                                prefs.edit().putString("backend_url", trimmed).apply()
                                FactCheckApiClient.baseUrl = trimmed
                                pingBackend()
                            }) {
                                Text("Save & Ping")
                            }
                        }
                    )

                    if (latestBudget != null) {
                        val b = latestBudget!!
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text("Backend Daily Budget & Quota (M4 Hardening):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("• Cost spent: \$${b.estimatedCostDollars} / \$${b.dailyCostCapDollars} max cap", fontSize = 12.sp)
                            Text("• Remaining: \$${b.remainingBudgetDollars} (resets 00:00 UTC)", color = Color(0xFF10B981), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text("• Invocations: ${b.modelCallCount} Gemini calls (${b.cachedCount} cached hits)", fontSize = 12.sp)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { pingBackend() },
                            enabled = !isCheckingHealth,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isCheckingHealth) "Pinging..." else "Refresh Stats")
                        }

                        Button(
                            onClick = { OverlayService.checkCurrentScreenNow() },
                            enabled = isOverlayRunning && isMediaProjectionReady && !isFactChecking,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isFactChecking) "Checking..." else "Fact-Check Now")
                        }
                    }

                    if (latestFactCheck != null) {
                        val result = latestFactCheck!!
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Latest Result:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                if (result.cached) {
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xFF312E81), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("⚡ CACHED", color = Color(0xFFA5B4FC), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            if (result.notCheckable != null) {
                                Text("Not checkable: ${result.notCheckable}", fontSize = 12.sp, color = Color.Gray)
                            } else {
                                Text("Claims Checked: ${result.claims.size}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                result.claims.forEachIndexed { i, c ->
                                    val verdictColor = when (c.verdict.rawValue) {
                                        "supported" -> Color(0xFF10B981)
                                        "disputed" -> Color(0xFFEF4444)
                                        else -> Color(0xFFF59E0B)
                                    }
                                    Text(
                                        "${i + 1}. [${c.verdict.rawValue.uppercase()}] ${c.claim}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = verdictColor
                                    )
                                    Text(c.explanation, fontSize = 11.sp, color = Color.DarkGray)
                                }
                            }
                        }
                    }
                }
            }

            // Live event logs
            Text("Activity & Extraction Log", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                if (eventLogs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No events yet. Tap the bubble or extract screen.", fontSize = 12.sp, color = Color.Gray)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(eventLogs) { log ->
                            Text(log, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
