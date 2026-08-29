package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.music.spotui.data.preferences.setDeezerArl
import com.music.spotui.data.preferences.setDeezerTier
import com.music.spotui.data.preferences.MusicSource
import com.music.spotui.data.preferences.setPrimaryMusicSource
import com.music.spotui.deezer.DeezerSession
import com.music.spotui.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val DEEZER_LOGIN_URL = "https://www.deezer.com/login"
private const val DEEZER_PURPLE = 0xFFA238FF

/**
 * Deezer login. Shows Deezer's own web login in a full-screen WebView and
 * watches for the `arl` cookie it sets on success. The ARL is the account token
 * the streaming layer ([DeezerSession]) authenticates with; once captured we
 * store it, detect the account tier, and return to Settings.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeezerLoginScreen(navController: NavController, next: String = "") {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var statusMessage by remember { mutableStateOf("Log in to Deezer") }
    var hasError by remember { mutableStateOf(false) }
    val captured = remember { AtomicBoolean(false) }

    // Poll for the arl cookie — it's set on .deezer.com the moment login succeeds.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (captured.get()) continue
            val arl = extractDeezerCookie("arl")
            if (!arl.isNullOrBlank() && captured.compareAndSet(false, true)) {
                statusMessage = "Connecting…"
                setDeezerArl(context, arl)
                scope.launch(Dispatchers.IO) {
                    DeezerSession.setArl(arl)
                    DeezerSession.authorize()
                    val tier = when (DeezerSession.entitledQuality) {
                        DeezerSession.QUALITY_FLAC -> "Premium (FLAC)"
                        DeezerSession.QUALITY_MP3_320 -> "Premium (MP3 320)"
                        else -> "Free (MP3 128)"
                    }
                    setDeezerTier(context, tier)
                    setPrimaryMusicSource(context, MusicSource.DEEZER)
                    withContext(Dispatchers.Main) {
                        statusMessage = "Signed in — $tier"
                        delay(400)
                        when (next) {
                            "spotiflac" -> {
                                navController.navigate("${Routes.SpotiflacVerify.route}?next=home")
                            }
                            "home" -> {
                                navController.navigate(Routes.Home.route) {
                                    popUpTo(Routes.MusicSource.route) { inclusive = true }
                                }
                            }
                            else -> navController.popBackStack()
                        }
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 40.dp),
            factory = { ctx ->
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                WebView(ctx).apply {
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {}
                        override fun onPageFinished(view: WebView?, url: String?) {}
                    }
                    loadUrl(DEEZER_LOGIN_URL)
                }
            },
        )

        Box(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().height(40.dp).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = statusMessage,
                color = if (hasError) Color(0xFFE22134) else Color(DEEZER_PURPLE),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }
}

private fun extractDeezerCookie(name: String): String? {
    val all = CookieManager.getInstance().getCookie("https://www.deezer.com") ?: return null
    return all.split(";")
        .mapNotNull {
            val parts = it.trim().split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }
        .firstOrNull { it.first == name && it.second.isNotBlank() }
        ?.second
}
