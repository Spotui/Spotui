package com.music.spotui.di

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Experimental "real Spotify" playback: rather than resolve a track from
 * YouTube/FLAC, we host Spotify's OWN web player in a hidden 1×1 WebView and let
 * it stream + play the track itself (its DRM/decoding, not ours — no bypass).
 * Our native UI becomes a remote control that drives the web player's on-page
 * buttons (data-testid controls) and reads its now-playing state.
 *
 * Requirements / limits:
 *  - The WebView reuses the login cookie jar (sp_dc etc.), so the user must have
 *    logged in via SpotifyLoginScreen first.
 *  - Free tier works (desktop web allows on-demand) but injects ads; Premium is
 *    ad-free. Playback is real-time only: no seek-ahead / download / crossfade.
 *  - Whether audio actually plays depends on Widevine being provisioned in the
 *    system WebView; if it isn't, the page loads but stays silent.
 */
object SpotifyWebPlayer {
    private const val TAG = "SpotifyWebPlayer"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    @Volatile private var webView: WebView? = null
    @Volatile var pageReady = false
        private set

    // Android's System WebView ships WITHOUT the Widevine CDM (only full Chrome
    // has it), so Spotify's DRM web player can't decrypt audio here — it shows
    // "playing" but stays silent. We probe for Widevine on load; when it's absent
    // (all current Android WebViews) callers must NOT route playback here.
    @Volatile var canPlay = false
        private set

    // Live playback position/duration (ms) scraped from the web player's own
    // progress bar, so the app's UI (which reads these) shows real time instead
    // of the idle ExoPlayer's 0:00. Updated by a 500ms poll while attached.
    @Volatile var positionMs = 0L
        private set
    @Volatile var durationMs = 0L
        private set
    @Volatile var isPlaying = false
        private set

    // True once the web player has registered a device and we've captured its
    // auth/client tokens — then we switch tracks via Spotify's own command API
    // (playFromUri) instead of reloading the page for each one.
    @Volatile private var commandReady = false

    // True once the first play navigated+clicked, activating the web player's
    // Connect device — a prerequisite for the command API to actually start audio.
    @Volatile private var activated = false

    /** Invoked (on the main thread) after each progress poll so the media
     *  notification's SimpleBasePlayer can refresh its state. */
    @Volatile var onStateChanged: (() -> Unit)? = null

    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            val wv = webView
            if (wv != null) {
                wv.evaluateJavascript(progressJs()) { r ->
                    // r is "pos|dur|playing" in seconds (or -1 fields when unknown).
                    val s = r?.trim('"') ?: ""
                    val parts = s.split("|")
                    if (parts.size == 3) {
                        parts[0].toDoubleOrNull()?.let { if (it >= 0) positionMs = (it * 1000).toLong() }
                        parts[1].toDoubleOrNull()?.let { if (it >= 0) durationMs = (it * 1000).toLong() }
                        isPlaying = parts[2] == "1"
                        onStateChanged?.invoke()
                    }
                }
            }
            pollHandler.postDelayed(this, 500)
        }
    }

    /** Attach the hidden WebView to the activity window (media won't play detached). */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(activity: Activity) {
        if (webView != null) return
        try {
            // Lets `chrome://inspect` attach to the hidden player for diagnosis.
            WebView.setWebContentsDebuggingEnabled(true)
            val wv = WebView(activity)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = DESKTOP_UA
                @Suppress("DEPRECATION")
                setSupportMultipleWindows(false)
            }
            // Bridge so the page can report the async Widevine probe result back —
            // evaluateJavascript can't await a Promise, so we call in via JS instead.
            wv.addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun onWidevine(ok: Boolean) {
                    canPlay = ok
                    Log.d(TAG, "widevine canPlay=$ok (via bridge)")
                }
                @android.webkit.JavascriptInterface
                fun onCommandReady() {
                    // The web player registered a device + we captured its tokens, so
                    // we can now switch tracks via the connect-state command API
                    // (no page reload → no "Oops, something went wrong").
                    if (!commandReady) { commandReady = true; Log.d(TAG, "command API ready") }
                }
            }, "SpotuiBridge")

            val cookies = CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
            }
            // The login flow stores sp_dc in app prefs, NOT in this WebView's cookie
            // jar — so without this the web player loads logged-OUT and can't stream.
            // Seed the auth cookie for the Spotify domain before we load the page.
            val spDc = com.music.spotui.data.api.SpotifySession.spDc(activity)
            if (spDc.isNotBlank()) {
                val attrs = "Domain=.spotify.com; Path=/; Secure"
                cookies.setCookie("https://open.spotify.com", "sp_dc=$spDc; $attrs")
                cookies.setCookie("https://spotify.com", "sp_dc=$spDc; $attrs")
                cookies.flush()
                Log.d(TAG, "seeded sp_dc cookie into WebView jar")
            } else {
                Log.w(TAG, "no sp_dc — web player will be logged out (log in first)")
            }
            val appContext = activity.applicationContext
            wv.webViewClient = object : WebViewClient() {
                // Ad-free ("unlocked") playback, SpotiFuck-style: drop analytics and
                // swap audio-ad streams for silence at the network layer, so the free
                // web player never plays or counts an ad.
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): android.webkit.WebResourceResponse? {
                    if (request == null) return null
                    return interceptForAds(appContext, request) ?: super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    // Inject as EARLY as possible: the off-screen WebView reports the
                    // page as hidden, and Spotify's Web Playback SDK refuses to
                    // register a device / start audio on a hidden page. Spoof the Page
                    // Visibility API to always report visible+focused.
                    view?.evaluateJavascript(VISIBILITY_SPOOF_JS, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = url?.contains("open.spotify.com") == true
                    Log.d(TAG, "onPageFinished ready=$pageReady url=$url")
                    view?.evaluateJavascript(VISIBILITY_SPOOF_JS, null)
                    // Command-API bootstrap: capture the player's device id + tokens
                    // so we can transfer playback to THIS device and switch tracks
                    // without reloading.
                    view?.evaluateJavascript(BOOTSTRAP_JS, null)
                    // Report whether the page thinks it's logged in (auth cookie present).
                    view?.evaluateJavascript(
                        "(function(){return document.cookie.indexOf('sp_dc')>-1 ? 'has-sp_dc' : 'no-sp_dc';})();"
                    ) { Log.d(TAG, "login-check: $it") }
                    // Probe Widevine and report the async result via the bridge.
                    // Works because onPermissionRequest (below) auto-grants the
                    // PROTECTED_MEDIA_ID permission the CDM needs.
                    view?.evaluateJavascript(
                        """
                        (function(){
                          navigator.requestMediaKeySystemAccess('com.widevine.alpha',
                            [{initDataTypes:['cenc'],audioCapabilities:[{contentType:'audio/mp4;codecs="mp4a.40.2"'}]}])
                            .then(function(){ SpotuiBridge.onWidevine(true); })
                            .catch(function(){ SpotuiBridge.onWidevine(false); });
                        })();
                        """.trimIndent(),
                        null,
                    )
                }
            }
            wv.webChromeClient = object : android.webkit.WebChromeClient() {
                // THE fix: Spotify's player needs the PROTECTED_MEDIA_ID (Widevine)
                // permission to decrypt audio. WebView asks the app to grant it; if
                // we don't, EME fails and playback is silent. Grant DRM, deny the rest.
                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                    val drm = android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                    if (request.resources.contains(drm)) {
                        Log.d(TAG, "granting PROTECTED_MEDIA_ID (Widevine)")
                        request.grant(arrayOf(drm))
                    } else {
                        request.deny()
                    }
                }
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    Log.d(TAG, "console: ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                    return true
                }
            }
            // Give it a real full-screen viewport so Spotify's responsive web player
            // renders its full DOM incl. the Play button — a tiny/0-size view
            // collapses the layout (innerWidth=0) and the controls never get
            // created, and the track page won't even navigate. MATCH_PARENT gives a
            // genuine size; translate it off-screen so it's invisible but attached
            // (attachment is required for audio to play).
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            activity.addContentView(wv, params)
            wv.post { wv.translationX = wv.width.toFloat() + 2000f }
            wv.loadUrl("https://open.spotify.com/")
            webView = wv
            pollHandler.postDelayed(pollRunnable, 1000)
            Log.d(TAG, "WebView attached")
        } catch (e: Exception) {
            Log.e(TAG, "attach failed", e)
        }
    }

    /**
     * Re-load the player with the now-current login. The hidden player WebView is
     * created at app startup — on a fresh install that's BEFORE the user logs in,
     * so it's stuck with a logged-out session (→ "Oops"). Call this the moment
     * login succeeds so the player picks up the new sp_dc session.
     */
    fun refreshLogin(context: Context) {
        val wv = webView ?: return
        val spDc = com.music.spotui.data.api.SpotifySession.spDc(context)
        if (spDc.isBlank()) return
        CookieManager.getInstance().apply {
            val attrs = "Domain=.spotify.com; Path=/; Secure"
            setCookie("https://open.spotify.com", "sp_dc=$spDc; $attrs")
            setCookie("https://spotify.com", "sp_dc=$spDc; $attrs")
            flush()
        }
        commandReady = false
        activated = false
        wv.post { wv.loadUrl("https://open.spotify.com/") }
        Log.d(TAG, "refreshLogin: reloaded player with logged-in session")
    }

    fun play(trackId: String) = playUri("spotify:track:$trackId", "track/$trackId")

    /** Play a podcast episode (same engine as tracks). */
    fun playEpisode(episodeId: String) = playUri("spotify:episode:$episodeId", "episode/$episodeId")

    /**
     * Play a Spotify uri. Once the web player is initialized (commandReady), switch
     * tracks via the connect-state command API — NO page reload, which is what
     * caused the frequent "Oops, something went wrong". Before it's ready, do the
     * one-time navigation (which also boots the player + captures its tokens).
     */
    private fun playUri(uri: String, path: String) {
        val wv = webView ?: run { Log.w(TAG, "play() before attach"); return }
        Log.d(TAG, "playUri($uri) activated=$activated commandReady=$commandReady")
        wv.post {
            if (activated && commandReady) {
                sendCommand(uri, path)
            } else {
                navigateAndPlay(wv, path)
                activated = true
            }
        }
    }

    private fun sendCommand(uri: String, path: String? = null) {
        val wv = webView ?: return
        wv.evaluateJavascript("(window.__spotuiPlay ? window.__spotuiPlay('$uri') : 'no-fn');") { r ->
            Log.d(TAG, "command play -> $r")
            val res = r?.trim('"')
            if ((res == "no-fn" || res == "no-device") && path != null) {
                wv.post { navigateAndPlay(wv, path) }
            }
        }
    }

    /** Last-resort cold start: navigate once to boot the player, then click play. */
    private fun navigateAndPlay(wv: WebView, path: String) {
        wv.evaluateJavascript("window.location.assign('https://open.spotify.com/$path');", null)
        for (delay in longArrayOf(2000, 3000, 4000, 5000, 6500, 8000, 10000, 12000)) {
            wv.postDelayed({
                wv.evaluateJavascript(clickPlayJs()) { r ->
                    if (r != null && r.contains("clicked")) Log.d(TAG, "play click: $r")
                }
            }, delay)
        }
    }

    fun resume() = eval(clickPlayJs())
    fun pause() = eval(pauseJs())
    fun next() = eval(clickJs("control-button-skip-forward"))
    fun previous() = eval(clickJs("control-button-skip-back"))

    /** Seek the web player to an absolute position (ms) by driving its progress bar. */
    fun seekTo(positionMs: Long) {
        val wv = webView ?: return
        val dur = durationMs
        if (dur <= 0) return
        val frac = (positionMs.toDouble() / dur).coerceIn(0.0, 1.0)
        wv.post { wv.evaluateJavascript(seekJs(frac)) { r -> Log.d(TAG, "seek ${"%.3f".format(frac)} -> $r") } }
        // Optimistic UI update; the 500ms poll will correct it from the real bar.
        this.positionMs = positionMs.coerceIn(0, dur)
    }

    /** Remove the hidden player so it cannot cover an interactive login WebView. */
    fun detach() {
        pollHandler.removeCallbacks(pollRunnable)
        webView?.let { wv ->
            wv.post {
                runCatching {
                    wv.stopLoading()
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.destroy()
                }
            }
        }
        webView = null
        pageReady = false
        canPlay = false
        commandReady = false
        activated = false
    }

    fun release() = detach()

    private fun eval(js: String) {
        val wv = webView ?: return
        wv.post { wv.evaluateJavascript(js, null) }
    }

    // Force the page to always look visible + focused. The hidden off-screen
    // WebView otherwise reports document.hidden=true, and Spotify's Web Playback
    // SDK won't register its Connect device or start audio on a hidden page (this
    // is why Spotify Connect stopped showing "Web Player" and playback went silent).
    private val VISIBILITY_SPOOF_JS = """
        (function(){
          try {
            var vis = function(){ return 'visible'; };
            var no  = function(){ return false; };
            Object.defineProperty(document,'visibilityState',{configurable:true,get:vis});
            Object.defineProperty(document,'hidden',{configurable:true,get:no});
            Object.defineProperty(document,'webkitVisibilityState',{configurable:true,get:vis});
            Object.defineProperty(document,'webkitHidden',{configurable:true,get:no});
            document.hasFocus = function(){ return true; };
            // Swallow visibilitychange/blur so the SDK never sees us go background.
            ['visibilitychange','webkitvisibilitychange','blur','pagehide'].forEach(function(t){
              window.addEventListener(t, function(e){ e.stopImmediatePropagation(); }, true);
              document.addEventListener(t, function(e){ e.stopImmediatePropagation(); }, true);
            });
          } catch(e){}
        })();
    """.trimIndent()

    // Injected once per page load. Hooks fetch to capture the web player's device
    // id + auth/client tokens (from its own requests), then exposes
    // __spotuiPlay(uri) which plays any track/episode via Spotify's connect-state
    // command API — no page reload. license:'tft' matches SpotiFuck. Also reloads
    // on a connect-state 404 (player lock) to self-heal.
    private val BOOTSTRAP_JS = """
        (function(){
          if (window.__spotuiReady) return;
          window.__spotuiReady = true;
          window.__featVer = 'web-player_' + Date.now();
          var oriFetch = window.fetch.bind(window);
          window.__oriFetch = oriFetch;
          // PURELY OBSERVATIONAL: read the player's own requests to capture its
          // device id, tokens and region-correct spclient origin. Never alters a
          // request and never reloads — the old reload-on-404 destabilised the
          // half-initialised player and caused the "Oops" loop.
          window.fetch = function(){
            try {
              var url = arguments[0], opts = arguments[1] || {};
              var us = (typeof url === 'string') ? url : (url && url.url) || '';
              var hs = opts.headers || {};
              function hv(n){ try { return hs.get ? hs.get(n) : (hs[n] || hs[n.toLowerCase()]); } catch(e){ return null; } }
              var m = us.match(/https?:\/\/([a-z0-9-]*spclient[a-z0-9.\-]*)/i);
              if (m) window.__spBase = 'https://' + m[1];
              if (us.indexOf('/track-playback/v1/devices') > -1 && opts.body) {
                try { var b = JSON.parse(opts.body); if (b && b.device && b.device.device_id) window.__devId = b.device.device_id; } catch(e){}
              }
              var ct = hv('Client-Token'); if (ct) window.__cliToken = ct;
              var au = hv('Authorization'); if (au && au.indexOf('Bearer') === 0) window.__auth = au;
              if (window.__devId && window.__auth && !window.__reported) {
                window.__reported = true;
                try { SpotuiBridge.onCommandReady(); } catch(e){}
              }
            } catch(e){}
            return oriFetch.apply(this, arguments);
          };
          // Only ever called AFTER the token+device are captured (native waits for
          // onCommandReady), so we never poke a half-initialised player.
          window.__spotuiPlay = function(uri){
            if (!window.__devId || !window.__auth) return 'no-device';
            var base = window.__spBase || 'https://gew4-spclient.spotify.com';
            var type = (uri.match(/^spotify:([^:]+)/) || [])[1] || 'track';
            window.__oriFetch(base + '/connect-state/v1/player/command/from/' + window.__devId + '/to/' + window.__devId, {
              method: 'POST',
              headers: { 'Authorization': window.__auth, 'Client-Token': window.__cliToken || '', 'Content-Type': 'application/json' },
              body: JSON.stringify({ command: { context: { uri: uri, url: 'context://' + uri, metadata: {} }, play_origin: { feature_identifier: type, feature_version: window.__featVer, referrer_identifier: 'search' }, options: { license: 'tft', skip_to: {}, player_options_override: {} }, endpoint: 'play' } })
            }).catch(function(){});
            return 'sent';
          };
        })();
    """.trimIndent()

    // Start playback of the currently-open page. Prefer the big context Play
    // button (track/album/playlist page), then the now-playing transport, then
    // any button whose label starts with "Play". Never re-click when already
    // playing (label would say "Pause"). Returns 'clicked …' or 'no-play-button'.
    private fun clickPlayJs() =
        """
        (function(){
          // Already playing? The transport button reads "Pause" — do nothing, so the
          // poll loop stops re-clicking (which could toggle or hit another track).
          var pp=document.querySelector('[data-testid="control-button-playpause"]');
          if (pp && (pp.getAttribute('aria-label')||'').toLowerCase().indexOf('pause')>-1)
            return 'already-playing';
          // Click the main context Play button (the big one on the track page).
          var b=document.querySelector('[data-testid="play-button"]');
          if (b && (b.getAttribute('aria-label')||'').toLowerCase().indexOf('pause')===-1){
            b.click(); return 'clicked';
          }
          return 'no-play-button';
        })();
        """.trimIndent()

    private fun pauseJs() =
        """
        (function(){
          var b = document.querySelector('[data-testid="control-button-playpause"]');
          if (b){ var lbl=(b.getAttribute('aria-label')||'').toLowerCase();
                  if (lbl.indexOf('pause')!==-1){ b.click(); return 'paused'; } }
          return 'not-playing';
        })();
        """.trimIndent()

    private fun clickJs(testId: String) =
        """
        (function(){ var b=document.querySelector('[data-testid="$testId"]');
          if(b){b.click();return 'clicked';} return 'missing'; })();
        """.trimIndent()

    // Seek to a fraction [0..1] of the track. The desktop web player's progress bar
    // is a React-controlled <input type=range>; set its value via the native setter
    // (so React's onChange fires) then dispatch input/change. Falls back to
    // simulating a click at the fraction's x on the bar element.
    private fun seekJs(frac: Double) =
        """
        (function(){
          var f=$frac;
          var input=document.querySelector('[data-testid="playback-progressbar"] input[type="range"]')
                 || document.querySelector('[data-testid="progress-bar"] input[type="range"]')
                 || document.querySelector('div[data-testid="playback-progressbar"] input');
          if(input){
            var max=parseFloat(input.max)||1, val=f*max;
            var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
            setter.call(input, String(val));
            input.dispatchEvent(new Event('input',{bubbles:true}));
            input.dispatchEvent(new Event('change',{bubbles:true}));
            return 'input '+val.toFixed(1)+'/'+max;
          }
          var bar=document.querySelector('[data-testid="playback-progressbar"]')
               || document.querySelector('[data-testid="progress-bar"]');
          if(!bar) return 'no-bar';
          var r=bar.getBoundingClientRect();
          var x=r.left+Math.max(0,Math.min(1,f))*r.width, y=r.top+r.height/2;
          ['pointerdown','pointerup','click'].forEach(function(t){
            bar.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,clientX:x,clientY:y}));
          });
          return 'clicked-bar';
        })();
        """.trimIndent()

    // ── Ad blocking (SpotiFuck strategy) ────────────────────────────────
    // Analytics/telemetry hosts → answered with an empty 200 so nothing tracks.
    private val analyticsHosts = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "fastly-insights.com",
        "sentry.io",
    )
    // Audio-ad sources → answered with a bundled silent.mp3. Only UNAMBIGUOUS ad
    // hosts here: the shared scdn.co/audio/ + spotifycdn.com/audio/ were REMOVED
    // because real track audio streams from them too — probing every segment there
    // (to check content-type) throttled playback (stutter/restart/lag). These
    // remaining hosts are ad-only, so a cheap URL match is safe.
    private val audioAdPatterns = listOf(
        "scdn.co/mp3-ad/",
        "amillionads.com",
        "2mdn.net",
        "adxcel.com",
        "adstudio-assets.scdn.co",
    )

    private fun interceptForAds(
        context: Context,
        request: android.webkit.WebResourceRequest,
    ): android.webkit.WebResourceResponse? {
        val url = request.url.toString()
        val lower = url.lowercase()

        // Analytics/telemetry: answer with an empty 200 — no network needed.
        if (analyticsHosts.any { it in lower }) {
            return android.webkit.WebResourceResponse(
                "text/plain", "utf-8", 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*"),
                java.io.ByteArrayInputStream(ByteArray(0)),
            )
        }

        // Never touch streaming media segments (they carry a Range header) — those
        // are the real audio/video being fetched in chunks; intercepting them is
        // what caused the stutter. Ad-only hosts get a silent.mp3.
        val isRange = request.requestHeaders.keys.any { it.equals("Range", ignoreCase = true) }
        if (!isRange && audioAdPatterns.any { it in lower }) {
            Log.d(TAG, "adblock: silenced audio ad $url")
            return runCatching {
                android.webkit.WebResourceResponse(
                    "audio/mpeg", null, context.assets.open("silent.mp3"),
                )
            }.getOrNull()
        }
        return null
    }

    // Read the now-playing bar's position/duration text ("M:SS") + play state.
    // Returns "posSec|durSec|playing" (fields -1 / 0 when unknown).
    private fun progressJs() =
        """
        (function(){
          function t2s(t){ if(!t) return -1; var p=t.trim().split(':');
            if(!p.length) return -1; var s=0;
            for(var i=0;i<p.length;i++){ var n=parseInt(p[i],10); if(isNaN(n)) return -1; s=s*60+n; }
            return s; }
          var pe=document.querySelector('[data-testid="playback-position"]');
          var de=document.querySelector('[data-testid="playback-duration"]');
          var pp=document.querySelector('[data-testid="control-button-playpause"]');
          var playing=(pp&&(pp.getAttribute('aria-label')||'').toLowerCase().indexOf('pause')>-1)?'1':'0';
          return t2s(pe&&pe.textContent)+'|'+t2s(de&&de.textContent)+'|'+playing;
        })();
        """.trimIndent()
}
