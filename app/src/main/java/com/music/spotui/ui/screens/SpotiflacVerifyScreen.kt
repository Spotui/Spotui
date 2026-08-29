package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.music.spotui.data.preferences.saveSpotiflacSession
import com.music.spotui.lossless.LosslessRegistry
import com.music.spotui.ui.navigation.Routes
import org.json.JSONObject

/**
 * EXPERIMENTAL (path 1a). Obtains a signed session from SpotiFLAC's gated backend
 * by having the user solve the Cloudflare Turnstile **themselves** in this WebView
 * — no auto-solving. The page is loaded with base URL [LosslessRegistry.SESSION_BASE]
 * so it behaves as same-origin with that host: fetches avoid CORS, the Turnstile
 * widget passes its domain check, and the resulting cf_clearance cookie applies to
 * the same WebView. Every step is logged on-screen and to logcat (tag SpotiflacVerify)
 * so the flow can be debugged on-device.
 *
 * Stage 1 captures session_id + session_secret. Downloading via the signed
 * `/api/dl` endpoints is wired only after this is confirmed working on a device.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun SpotiflacVerifyScreen(navController: NavController, next: String = "") {
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 40.dp),
            factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    webChromeClient = WebChromeClient()

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun log(msg: String) {
                            android.util.Log.d("SpotiflacVerify", msg)
                        }

                        @JavascriptInterface
                        fun onSession(json: String) {
                            runCatching {
                                val o = JSONObject(json)
                                saveSpotiflacSession(
                                    context,
                                    sessionId = o.optString("session_id"),
                                    sessionSecret = o.optString("session_secret"),
                                    installId = o.optString("install_id"),
                                    expiresAt = o.optString("expires_at"),
                                )
                            }
                            Handler(Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(
                                    context, "SpotiFLAC session captured", android.widget.Toast.LENGTH_LONG,
                                ).show()
                                // Onboarding (Spotify → music source → SpotiFLAC) lands Home;
                                // reached from Settings we just return where we came from.
                                if (next == "home") {
                                    navController.navigate(Routes.Home.route) {
                                        popUpTo(Routes.MusicSource.route) { inclusive = true }
                                    }
                                } else {
                                    navController.popBackStack()
                                }
                            }
                        }
                    }, "Android")

                    val html = ENGINE_HTML
                        .replace("__BASE__", LosslessRegistry.SESSION_BASE)
                        .replace("__APPV__", "4.8.5")
                        .replace("__PLAT__", "android")
                    // base URL = session host → same-origin fetches + valid Turnstile domain.
                    loadDataWithBaseURL(LosslessRegistry.SESSION_BASE, html, "text/html", "utf-8", null)
                }
            },
        )

        Box(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().height(40.dp).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "SpotiFLAC verification (experimental)",
                color = Color(0xFF00C7B7),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }
    }
}

// JS engine. Written with string concatenation (no template literals) so no `$`
// collides with Kotlin. Placeholders __BASE__/__APPV__/__PLAT__ are substituted.
private const val ENGINE_HTML = """
<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{background:#0b0b0f;color:#ddd;font:13px monospace;padding:12px}
#cf{margin:16px 0}#log div{padding:2px 0;border-bottom:1px solid #1a1a20}</style>
<script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit" async defer></script>
</head><body>
<h3 style="color:#00C7B7">Solve the check below to enable SpotiFLAC</h3>
<div id="cf"></div>
<div id="log"></div>
<script>
var BASE="__BASE__", APPV="__APPV__", PLAT="__PLAT__";
function log(m){var d=document.createElement('div');d.textContent=m;document.getElementById('log').appendChild(d);
  try{if(window.Android)Android.log(m);}catch(e){}}
function rand(){var a=new Uint8Array(16);crypto.getRandomValues(a);
  return Array.from(a).map(function(b){return ('0'+b.toString(16)).slice(-2)}).join('');}
var installId = localStorage.getItem('sf_install')||rand();
localStorage.setItem('sf_install', installId);

async function boot(){
  try{
    log('bootstrap...');
    var r=await fetch(BASE+'/bootstrap?install_id='+installId+'&app_version='+APPV,{headers:{'Accept':'application/json'}});
    log('bootstrap HTTP '+r.status);
    var d=await r.json();
    log('bootstrap: '+JSON.stringify(d).slice(0,240));
    if(d.session_id && d.session_secret){ done(d); return; }
    var sitekey=d.sitekey||d.turnstile_sitekey||d.turnstile_site_key;
    window._challengeId=d.challenge_id||'';
    if(!sitekey){
      var au=d.auth_url||d.challenge_url;
      if(au){ log('navigating to challenge page...'); location.href=au; return; }
      log('ERROR: no sitekey and no auth_url in bootstrap'); return;
    }
    renderTurnstile(sitekey);
  }catch(e){ log('bootstrap error: '+e); }
}
function renderTurnstile(sitekey){
  if(!window.turnstile){ log('waiting for turnstile lib...'); setTimeout(function(){renderTurnstile(sitekey)},400); return; }
  log('rendering Turnstile (sitekey '+sitekey.slice(0,10)+'...)');
  turnstile.render('#cf',{ sitekey: sitekey, callback: function(token){ verify(token); } });
}
async function verify(token){
  try{
    log('verify (token '+token.slice(0,8)+'...)');
    var r=await fetch(BASE+'/challenge/verify',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({challenge_id:window._challengeId, turnstile_token:token})});
    log('verify HTTP '+r.status);
    var d=await r.json();
    log('verify: '+JSON.stringify(d).slice(0,240));
    if(d.session_id && d.session_secret){ done(d); return; }
    await exchange(d.grant||d.grant_token||'');
  }catch(e){ log('verify error: '+e); }
}
async function exchange(grant){
  try{
    log('exchange...');
    var r=await fetch(BASE+'/session/exchange',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({grant:grant, install_id:installId, app_version:APPV, platform:PLAT})});
    log('exchange HTTP '+r.status);
    var d=await r.json();
    log('exchange: '+JSON.stringify(d).slice(0,160));
    if(d.session_id && d.session_secret){ done(d); } else { log('ERROR: no session in exchange response'); }
  }catch(e){ log('exchange error: '+e); }
}
function done(d){
  log('SESSION OBTAINED ✓ ('+String(d.session_id).slice(0,8)+'...)');
  try{ if(window.Android) Android.onSession(JSON.stringify({
    session_id:d.session_id, session_secret:d.session_secret,
    install_id:installId, expires_at:d.expires_at||d.expiresAt||'' })); }catch(e){ log('bridge error: '+e); }
}
window.addEventListener('load', function(){ setTimeout(boot, 400); });
</script></body></html>
"""
