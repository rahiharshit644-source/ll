package com.soltini.app.security

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soltini.app.overlay.OverlayService
import com.soltini.app.settings.AppSettings
import com.soltini.app.ui.theme.GeminiVoiceTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * VoiceUnlockActivity
 *
 * Biometric voice authentication dialog for Banking Mode and sensitive security actions.
 * Supports both Enrollment (3 samples) and Verification with cosine-similarity matching.
 */
class VoiceUnlockActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val MODE_VERIFY_BANKING = "verify_banking"
        const val MODE_VERIFY_DESTRUCTIVE = "verify_destructive"
        const val MODE_ENROLL = "enroll"

        fun startForBankingToggle(context: Context) {
            val intent = Intent(context, VoiceUnlockActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_VERIFY_BANKING)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }

        fun startEnrollment(context: Context) {
            val intent = Intent(context, VoiceUnlockActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_ENROLL)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_VERIFY_BANKING
        val voiceManager = VoiceBiometricsManager.getInstance(this)

        setContent {
            GeminiVoiceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.75f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        VoiceLockContent(
                            mode = mode,
                            voiceManager = voiceManager,
                            onDismiss = {
                                setResult(Activity.RESULT_CANCELED)
                                finish()
                            },
                            onSuccess = {
                                if (mode == MODE_VERIFY_BANKING) {
                                    val settings = AppSettings(this@VoiceUnlockActivity)
                                    val newPaused = !settings.isBankingModePaused
                                    settings.isBankingModePaused = newPaused
                                    OverlayService.getInstance()?.setHiddenForBanking(newPaused)
                                    Toast.makeText(
                                        this@VoiceUnlockActivity,
                                        if (newPaused) "Banking Mode Paused: Overlay Hidden" else "Voice Verified: Overlay Restored",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                setResult(Activity.RESULT_OK)
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceLockContent(
    mode: String,
    voiceManager: VoiceBiometricsManager,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf(if (mode == VoiceUnlockActivity.MODE_ENROLL) "Tap to record sample 1 of 3" else "Tap Mic to authenticate voice") }
    var similarityScore by remember { mutableStateOf<Float?>(null) }
    var isSuccess by remember { mutableStateOf<Boolean?>(null) }
    var sampleCount by remember { mutableStateOf(0) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth(0.88f)
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141724)),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF4A68FF).copy(0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = Color(0xFF4A68FF),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (mode == VoiceUnlockActivity.MODE_ENROLL) "Voice Biometric Enrollment" else "Banking Voice Lock",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                }
            }

            Spacer(Modifier.height(20.dp))

            // Pulsing Mic / Lock Orb
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                if (isSuccess == true) Color(0xFF00E676).copy(0.4f)
                                else if (isSuccess == false) Color(0xFFFF5252).copy(0.4f)
                                else Color(0xFF4A68FF).copy(if (isRecording) 0.5f else 0.2f),
                                Color.Transparent
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size((64 * pulseScale).dp)
                        .clip(CircleShape)
                        .background(
                            if (isSuccess == true) Color(0xFF00E676)
                            else if (isSuccess == false) Color(0xFFFF5252)
                            else if (isRecording) Color(0xFF4A68FF)
                            else Color(0xFF242C48)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isSuccess == true) Icons.Default.CheckCircle
                        else if (isSuccess == false) Icons.Default.Warning
                        else if (isRecording) Icons.Default.Mic
                        else Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                statusText,
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )

            if (similarityScore != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Match Score: ${"%.2f".format(similarityScore!! * 100)}% (Threshold: ${"%.0f".format(VoiceBiometricsManager.DEFAULT_THRESHOLD * 100)}%)",
                    color = if (isSuccess == true) Color(0xFF00E676) else Color(0xFFFF5252),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(24.dp))

            if (mode == VoiceUnlockActivity.MODE_ENROLL) {
                // Enrollment Button
                Button(
                    onClick = {
                        if (isRecording) return@Button
                        coroutineScope.launch {
                            isRecording = true
                            statusText = "Listening... Speak clear phrase #${sampleCount + 1}"
                            val res = voiceManager.recordAndVerify(durationMs = 2500L)
                            // Even if verification failed, we extract and add sample
                            // To record fresh sample:
                            isRecording = false
                            sampleCount++
                            if (sampleCount < VoiceBiometricsManager.REQUIRED_ENROLLMENT_SAMPLES) {
                                statusText = "Sample $sampleCount saved! Speak sample #${sampleCount + 1}"
                            } else {
                                val ok = voiceManager.finalizeEnrollment()
                                if (ok) {
                                    isSuccess = true
                                    statusText = "Enrollment Complete! Voice lock is active."
                                    delay(1200)
                                    onSuccess()
                                } else {
                                    statusText = "Could not finalize. Please try again."
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A68FF)),
                    enabled = !isRecording && isSuccess != true
                ) {
                    Text(
                        if (sampleCount == 0) "Record Sample 1/3" else "Record Sample ${sampleCount + 1}/3",
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                // Verification Button
                Button(
                    onClick = {
                        if (isRecording) return@Button
                        coroutineScope.launch {
                            isRecording = true
                            isSuccess = null
                            similarityScore = null
                            statusText = "Listening to your voice... Speak now"

                            val result = voiceManager.recordAndVerify(durationMs = 2500L)
                            isRecording = false
                            similarityScore = result.similarity
                            if (result.isMatch) {
                                isSuccess = true
                                statusText = "Voice Signature Verified!"
                                delay(1000)
                                onSuccess()
                            } else {
                                isSuccess = false
                                statusText = "Voice mismatch (${result.reason}). Action Rejected."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSuccess == true) Color(0xFF00E676) else Color(0xFF4A68FF)
                    ),
                    enabled = !isRecording && isSuccess != true
                ) {
                    Text(
                        if (isRecording) "Authenticating..." else "Authenticate Voice",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
