package com.ultragol.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.ultragol.app.adapters.ContentRowAdapter;
import com.ultragol.app.AirPlayManager;
import com.ultragol.app.CastBottomSheet;
import com.ultragol.app.CastDevice;
import com.ultragol.app.ContinueWatchingManager;
import com.ultragol.app.DLNAManager;
import com.ultragol.app.models.ContentItem;
import com.ultragol.app.network.StreamingApi;
import com.ultragol.app.network.AnimeApi;
import com.ultragol.app.network.TmdbApi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends AppCompatActivity {

    private WebView webView;
    private ProgressBar progressBar;
    private FrameLayout fullscreenContainer;
    private View webviewContainer, customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ContentItem item;

    // ── Inline player resize / fullscreen ────────────────────────────────────
    private static final int PLAYER_MIN_H_DP = 160;
    private static final int PLAYER_MAX_H_DP = 400;
    private boolean isPlayerFullscreen = false;
    private ViewGroup playerNormalParent = null;
    private int playerNormalParentIndex  = 0;
    private int playerNormalHeightPx     = 0;

    // ── M3U8 / MP4 interception ───────────────────────────────────────────────
    private volatile String capturedVideoUrl = null;
    private volatile String capturedReferer  = null;
    private volatile boolean capturedIsM3u8  = false;
    private String currentEmbedUrl           = null;
    private String videoTitle                = null;

    // ── Decoy filtering + "wait for the real source" grace window ────────────
    // Some embeds play a short ad/trailer/placeholder stream first, then swap
    // in the actual movie. Instead of freezing on the first URL the sniffer
    // sees, known decoy signatures are rejected outright, and every other
    // candidate resets a short timer — only the last candidate seen once
    // things go quiet for CAPTURE_GRACE_MS is treated as final.
    private static final long CAPTURE_GRACE_MS = 1400;
    private final Handler captureGraceHandler = new Handler(Looper.getMainLooper());
    private volatile String pendingCandidateUrl      = null;
    private volatile String pendingCandidateReferer  = null;
    private volatile boolean pendingCandidateIsM3u8  = false;

    private boolean isLikelyDecoyStream(String url) {
        return StreamValidator.isKnownDecoyUrl(url);
    }

    // ── Extra flags ───────────────────────────────────────────────────────────
    /** If true: once stream URL is captured, auto-open CastBottomSheet instead of MediaActivity */
    private boolean autoCast   = false;
    /** If true: keep WebView visible (manual quality / source selection) */
    private boolean manualMode = false;
    /** If true: once stream URL is captured, kick off a real download in the background */
    private boolean autoDownload = false;

    private static final long MIN_MP4_BYTES  = 1024 * 1024L; // 1 MB hint

    // ── Auto-server-fetch: lista de servidores obtenida del API ──────────────
    private final List<String> serverUrls = new ArrayList<>();
    private int currentServerIndex = 0;
    private final Handler autoRetryHandler = new Handler(Looper.getMainLooper());
    private static final long AUTO_RETRY_DELAY_MS = 10_000; // 10 s sin captura → siguiente servidor
    private int autoFetchSeason  = 1;
    private int autoFetchEpisode = 1;

    // ── XHR/fetch hook — injected at onPageStarted to catch streams before shouldInterceptRequest ──
    // Patches the top window AND every same-origin iframe reachable from it (some embeds
    // load the real player inside a nested iframe the naive top-only hook never sees),
    // re-scanning periodically since embeds often insert iframes after a short delay.
    private static final String JS_XHR_HOOK =
        "(function(){"
        + "try{"
        + "if(window.__ultragolHooked)return;window.__ultragolHooked=true;"
        // Helper: decide if a URL is a stream we care about
        + "function _isStream(u){"
        +   "if(!u||typeof u!=='string'||u.indexOf('http')!==0)return false;"
        +   "return u.indexOf('.m3u8')>=0||u.indexOf('.mp4')>=0"
        +     "||u.indexOf('m3u8')>=0"          // URLs like /stream?type=m3u8
        +     "||u.indexOf('/manifest')>=0"      // Dash/HLS manifest endpoints
        +     "||u.indexOf('/master')>=0"        // common HLS master playlist path
        +     "||u.indexOf('/playlist')>=0;"     // playlist endpoint
        + "}"
        + "function _send(u,src){try{window.top.HTMLOUT.onUrl(u,src);}catch(ex){}}"
        // Patches a single window object's XHR/fetch/video.src — no-ops safely
        // if win is a cross-origin iframe whose internals we can't touch.
        + "function _patchWin(win,tag){"
        +   "try{"
        +     "if(!win||win.__ultragolPatched)return;win.__ultragolPatched=true;"
        +     "var X=win.XMLHttpRequest;"
        +     "if(X&&X.prototype&&X.prototype.open){"
        +       "var xo=X.prototype.open;"
        +       "X.prototype.open=function(method,url){"
        +         "if(_isStream(url))_send(url,tag+'_xhr');"
        +         "return xo.apply(this,arguments);"
        +       "};"
        +     "}"
        +     "var of=win.fetch;"
        +     "if(typeof of==='function'){"
        +       "win.fetch=function(input,opts){"
        +         "var u=typeof input==='string'?input:(input&&input.url?input.url:'');"
        +         "if(_isStream(u))_send(u,tag+'_fetch');"
        +         "return of.apply(this,arguments);"
        +       "};"
        +     "}"
        +     "var HME=win.HTMLMediaElement;"
        +     "if(HME){"
        +       "var osd=Object.getOwnPropertyDescriptor(HME.prototype,'src');"
        +       "if(osd&&osd.set){"
        +         "Object.defineProperty(HME.prototype,'src',{"
        +           "set:function(v){"
        +             "if(_isStream(v))_send(v,tag+'_src_setter');"
        +             "return osd.set.call(this,v);"
        +           "},"
        +           "get:osd.get,configurable:true"
        +         "});"
        +       "}"
        +     "}"
        +   "}catch(ex){}"
        + "}"
        // Reaches into same-origin iframes only — cross-origin ones throw and are skipped.
        + "function _patchFrames(){"
        +   "try{"
        +     "var frames=document.querySelectorAll('iframe');"
        +     "for(var i=0;i<frames.length;i++){"
        +       "try{"
        +         "var cw=frames[i].contentWindow;"
        +         "if(cw)_patchWin(cw,'iframe'+i);"
        +       "}catch(ex){}"
        +     "}"
        +   "}catch(ex){}"
        + "}"
        + "_patchWin(window,'top');"
        + "_patchFrames();"
        // Embeds frequently inject the real player iframe a moment after load.
        + "var _tries=0;"
        + "var _rescan=setInterval(function(){"
        +   "_patchFrames();"
        +   "if(++_tries>=10)clearInterval(_rescan);"
        + "},600);"
        + "}catch(e){}"
        + "})();";

    // ── Full JS extractor — covers 15+ player engines + window vars + DOM scan ──
    private static final String JS_EXTRACT =
        "(function(){"
        + "function _ok(u){return u&&typeof u==='string'&&u.indexOf('http')===0"
        +   "&&(u.indexOf('m3u8')>=0||u.indexOf('.mp4')>=0"
        +   "||u.indexOf('/manifest')>=0||u.indexOf('/master')>=0);}"
        + "function _send(u,src){try{window.HTMLOUT.onUrl(u,src);}catch(e){}}"
        + "try{"
        // ── JW Player ──
        + "if(window.jwplayer){"
        +   "var jw=jwplayer();if(jw&&jw.getPlaylistItem){"
        +     "var pi=jw.getPlaylistItem();"
        +     "var src=pi&&(pi.file||(pi.sources&&pi.sources[0]&&pi.sources[0].file));"
        +     "if(_ok(src)){_send(src,'jwplayer');return;}"
        +   "}"
        // Also try jwplayer('player') by ID
        +   "var els=document.querySelectorAll('[id]');"
        +   "for(var ji=0;ji<els.length;ji++){"
        +     "try{var jj=jwplayer(els[ji].id);if(jj&&jj.getPlaylistItem){"
        +       "var pj=jj.getPlaylistItem();"
        +       "var sj=pj&&(pj.file||(pj.sources&&pj.sources[0]&&pj.sources[0].file));"
        +       "if(_ok(sj)){_send(sj,'jwplayer_id');return;}"
        +     "}}catch(ex){}"
        +   "}"
        + "}"
        // ── Video.js ──
        + "if(window.videojs&&videojs.players){"
        +   "var vkeys=Object.keys(videojs.players);"
        +   "for(var vi=0;vi<vkeys.length;vi++){"
        +     "var vp=videojs.players[vkeys[vi]];"
        +     "if(vp&&vp.currentSrc&&vp.currentSrc()&&_ok(vp.currentSrc())){"
        +       "_send(vp.currentSrc(),'videojs');return;"
        +     "}"
        +   "}"
        + "}"
        // ── HLS.js ──
        + "if(window.Hls&&Hls.instances&&Hls.instances.length>0){"
        +   "var hls=Hls.instances[0];"
        +   "var hu=hls.url||(hls.streamController&&hls.streamController.manifestUrl)"
        +         "||(hls.networkController&&hls.networkController.manifestUrl);"
        +   "if(_ok(hu)){_send(hu,'hlsjs');return;}"
        + "}"
        // ── Shaka Player ──
        + "if(window.shaka&&window.player&&window.player.getManifestUri){"
        +   "var su=window.player.getManifestUri();"
        +   "if(_ok(su)){_send(su,'shaka');return;}"
        + "}"
        // ── Plyr ──
        + "if(window.plyr){"
        +   "var pl=Array.isArray(window.plyr)?window.plyr[0]:window.plyr;"
        +   "if(pl){var ps=pl.source||(pl.media&&pl.media.currentSrc)||(pl.config&&pl.config.url);"
        +   "if(_ok(ps)){_send(ps,'plyr');return;}}"
        + "}"
        // ── DPlayer ──
        + "if(window.dp&&window.dp.video&&window.dp.video.src){"
        +   "var ds=window.dp.video.src;"
        +   "if(_ok(ds)){_send(ds,'dplayer');return;}"
        + "}"
        // ── Clappr ──
        + "if(window.player&&window.player.options){"
        +   "var co=window.player.options;"
        +   "var cs=co.source||co.src||(co.sources&&co.sources[0]&&co.sources[0].file)||'';"
        +   "if(_ok(cs)){_send(cs,'clappr');return;}"
        + "}"
        // ── Artplayer ──
        + "if(window.artplayer||window.art){"
        +   "var ap=window.artplayer||window.art;"
        +   "var au=ap.url||(ap.option&&ap.option.url)||(ap.hls&&ap.hls.url)||'';"
        +   "if(_ok(au)){_send(au,'artplayer');return;}"
        + "}"
        // ── Playerjs ──
        + "if(window.Playerjs){"
        +   "try{var pjc=new Playerjs({});var pju=pjc.file||pjc.src||'';"
        +   "if(_ok(pju)){_send(pju,'playerjs');return;}}catch(ex){}"
        + "}"
        // ── flowplayer ──
        + "if(window.flowplayer){"
        +   "var fp=flowplayer();if(fp&&fp.video){"
        +     "var fu=fp.video.url||fp.video.src||'';"
        +     "if(_ok(fu)){_send(fu,'flowplayer');return;}"
        +   "}"
        + "}"
        // ── OpenPlayer ──
        + "if(window.OpenPlayer&&window.OpenPlayer.instances){"
        +   "var ops=Object.values(window.OpenPlayer.instances);"
        +   "for(var oi=0;oi<ops.length;oi++){"
        +     "var ou=ops[oi].src||(ops[oi].getMedia&&ops[oi].getMedia().src)||'';"
        +     "if(_ok(ou)){_send(ou,'openplayer');return;}"
        +   "}"
        + "}"
        // ── MediaElement.js ──
        + "if(window.mejs&&window.mejs.players){"
        +   "var mkeys=Object.keys(window.mejs.players);"
        +   "for(var mi=0;mi<mkeys.length;mi++){"
        +     "var mp=window.mejs.players[mkeys[mi]];"
        +     "var mu=mp&&mp.media&&(mp.media.currentSrc||mp.media.src)||'';"
        +     "if(_ok(mu)){_send(mu,'mejs');return;}"
        +   "}"
        + "}"
        // ── <video> element currentSrc ──
        + "var vels=document.querySelectorAll('video');"
        + "for(var vi2=0;vi2<vels.length;vi2++){"
        +   "var vsrc=vels[vi2].currentSrc||vels[vi2].src||'';"
        +   "if(_ok(vsrc)){_send(vsrc,'video_el');return;}"
        + "}"
        // ── <source> elements ──
        + "var srcs=document.querySelectorAll('source[src]');"
        + "for(var si=0;si<srcs.length;si++){"
        +   "var ss=srcs[si].src||'';"
        +   "if(_ok(ss)){_send(ss,'source');return;}"
        + "}"
        // ── data-* attributes ──
        + "var datas=document.querySelectorAll('[data-src],[data-file],[data-url],[data-hls],[data-stream]');"
        + "for(var di=0;di<datas.length;di++){"
        +   "var dv=datas[di].getAttribute('data-src')||datas[di].getAttribute('data-file')"
        +       "||datas[di].getAttribute('data-url')||datas[di].getAttribute('data-hls')"
        +       "||datas[di].getAttribute('data-stream')||'';"
        +   "if(_ok(dv)){_send(dv,'data-attr');return;}"
        + "}"
        // ── window config variables (extended list) ──
        + "var wVars=['streamUrl','videoUrl','m3u8Url','hlsUrl','fileUrl','sourceUrl','src',"
        +   "'videoSrc','stream','manifestUrl','playUrl','playbackUrl','mediaUrl','contentUrl',"
        +   "'hlsSource','streamSource','videoSource','masterUrl','videoFile'];"
        + "for(var wi=0;wi<wVars.length;wi++){"
        +   "var wv=window[wVars[wi]];"
        +   "if(_ok(wv)){_send(wv,'window_var');return;}"
        + "}"
        // ── Inline script text regex — scan all at once ──
        + "var scripts=document.querySelectorAll('script');"
        + "for(var ki=0;ki<scripts.length;ki++){"
        +   "var st=scripts[ki].innerText||'';"
        +   "if(!st)continue;"
        +   "var m8=st.match(/[\"'`](https?:\\/\\/[^\"'`\\s]{8,}\\.m3u8[^\"'`\\s]*)[\"'`]/);"
        +   "if(m8){_send(m8[1],'script_m3u8');return;}"
        +   "var mp4=st.match(/[\"'`](https?:\\/\\/[^\"'`\\s]{8,}\\.mp4[^\"'`\\s]*)[\"'`]/);"
        +   "if(mp4){_send(mp4[1],'script_mp4');return;}"
        +   "var cfg=st.match(/(?:file|src|url|source|stream)\\s*[=:]\\s*[\"'](https?:\\/\\/[^\"']{8,}(?:m3u8|manifest|master)[^\"']*)[\"']/);"
        +   "if(cfg){_send(cfg[1],'config_key');return;}"
        + "}"
        + "}catch(e){}"
        + "})();";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String url = getIntent().getStringExtra("url");
        videoTitle  = getIntent().getStringExtra("title");
        item        = (ContentItem) getIntent().getSerializableExtra("item");
        autoCast    = getIntent().getBooleanExtra("autocast",    false);
        manualMode  = getIntent().getBooleanExtra("manual_mode", false);

        setContentView(item != null ? R.layout.activity_player_detail : R.layout.activity_player);

        webView             = findViewById(R.id.playerWebView);
        progressBar         = findViewById(R.id.playerProgress);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);
        webviewContainer    = findViewById(R.id.webviewContainer);

        TextView tvTitle = findViewById(R.id.playerTitle);
        if (videoTitle != null && tvTitle != null) tvTitle.setText(videoTitle);
        View btnBack = findViewById(R.id.btnPlayerBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // Desktop UA → el sitio siempre sirve la versión desktop con video directo (sin anuncios de mobile)
        s.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        s.setSupportMultipleWindows(true);

        // ── JavaScript interface to receive URLs extracted from JS ────────────
        webView.addJavascriptInterface(new HtmlOutInterface(), "HTMLOUT");

        webView.setWebViewClient(new WebViewClient() {

            // ── Intercept every network request from the WebView ──────────────
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String reqUrl = request.getUrl().toString();
                String ref    = request.getRequestHeaders().get("Referer");

                boolean isM3u8 = reqUrl.contains(".m3u8");
                boolean isMp4  = reqUrl.contains(".mp4") || reqUrl.contains(".MP4");

                if ((isM3u8 || isMp4) && capturedVideoUrl == null) {
                    // For MP4 try to check Content-Length header via a HEAD-like approach:
                    // We trust the URL pattern — if it ends with .mp4 we capture it.
                    // Large-size check is not possible without a blocking network call,
                    // so we accept any mp4 URL and filter in the player if needed.
                    capturedVideoUrl = reqUrl;
                    capturedReferer  = ref != null ? ref : currentEmbedUrl;
                    capturedIsM3u8   = isM3u8;

                    new Handler(Looper.getMainLooper()).post(() ->
                            onVideoUrlCaptured(capturedVideoUrl, capturedReferer, capturedIsM3u8));
                }

                return null; // never block the request
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    v.loadUrl(r.getUrl().toString());
                    return true;
                }
                return true;
            }

            @Override public void onPageStarted(WebView v, String u, Bitmap f) {
                if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
                // Inyectar hook XHR/fetch/src-setter lo antes posible
                v.evaluateJavascript("javascript:" + JS_XHR_HOOK, null);
                // Forzar viewport desktop
                v.evaluateJavascript(
                    "javascript:(function(){" +
                    "var m=document.querySelector('meta[name=viewport]');" +
                    "if(!m){m=document.createElement('meta');m.name='viewport';" +
                    "document.head&&document.head.appendChild(m);}" +
                    "if(m)m.content='width=1280,initial-scale=1';" +
                    "})();", null);
                // Primer intento de extracción temprana (algunos embeds tienen la URL en el HTML inicial)
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (capturedVideoUrl == null && !isFinishing())
                        v.evaluateJavascript("javascript:" + JS_EXTRACT, null);
                }, 300);
            }

            @Override public void onPageFinished(WebView v, String u) {
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                // Re-inyectar hook por si la página lo reemplazó con un reload
                v.evaluateJavascript("javascript:" + JS_XHR_HOOK, null);

                // Intento inmediato tras carga
                if (capturedVideoUrl == null) {
                    v.evaluateJavascript("javascript:" + JS_EXTRACT, null);
                }
                // Intentos escalonados más rápidos: 400 ms → 1.2 s → 2.5 s → 5 s → 8 s
                int[] delays = {400, 1200, 2500, 5000, 8000};
                for (int delay : delays) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (capturedVideoUrl == null && !isFinishing()) {
                            v.evaluateJavascript("javascript:" + JS_EXTRACT, null);
                        }
                    }, delay);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view; customViewCallback = cb;
                webviewContainer.setVisibility(View.GONE);
                fullscreenContainer.setVisibility(View.VISIBLE);
                fullscreenContainer.addView(view);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                hideSystemUI();
            }
            @Override public void onHideCustomView() {
                if (customView == null) return;
                fullscreenContainer.removeView(customView); customView = null;
                fullscreenContainer.setVisibility(View.GONE);
                webviewContainer.setVisibility(View.VISIBLE);
                customViewCallback.onCustomViewHidden();
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
            @Override public void onProgressChanged(WebView v, int p) {
                if (progressBar != null) {
                    progressBar.setProgress(p);
                    progressBar.setVisibility(p == 100 ? View.GONE : View.VISIBLE);
                }
            }
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                WebView popup = new WebView(PlayerActivity.this);
                popup.getSettings().setJavaScriptEnabled(true);
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        webView.loadUrl(r.getUrl().toString());
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });

        // ── Cast button (simple player layout) — always visible ──────────────
        View castContainer = findViewById(R.id.btnCastContainer);
        if (castContainer != null) {
            // alpha=0.45 set in XML (searching state); tap always works
            castContainer.setOnClickListener(v -> showCastOptions());
            if (autoCast) {
                TextView waitLbl = findViewById(R.id.btnPlayerCastWaiting);
                if (waitLbl != null) waitLbl.setText("preparando…");
            }
        }

        // ── Fullscreen button ─────────────────────────────────────────────────
        View btnFullscreen = findViewById(R.id.btnPlayerFullscreen);
        if (btnFullscreen != null) {
            btnFullscreen.setOnClickListener(v -> togglePlayerFullscreen());
        }

        // ── Resize handle drag ────────────────────────────────────────────────
        setupResizeHandle();

        autoFetchSeason  = getIntent().getIntExtra("auto_fetch_season",  1);
        autoFetchEpisode = getIntent().getIntExtra("auto_fetch_episode", 1);

        if (url != null && !url.isEmpty()) {
            currentEmbedUrl = url;
            webView.loadUrl(url);
        }

        if (item != null) bindDetailPanel();

        // ── Save to "Continuar Viendo" history when player opens ─────────────
        if (item != null) {
            ContinueWatchingManager.save(this, item, autoFetchSeason, autoFetchEpisode, 10, 0,
                    url != null ? url : "");
        }

        // ── Auto-fetch real server list in background ─────────────────────────
        if (item != null) {
            fetchServersInBackground();
        }
    }

    // ── Resize handle ─────────────────────────────────────────────────────────
    private void setupResizeHandle() {
        View resizeHandle = webviewContainer != null ? webviewContainer.findViewById(R.id.playerResizeHandle) : null;
        if (resizeHandle == null) return;
        final int minH = dpToPx(PLAYER_MIN_H_DP);
        final int maxH = dpToPx(PLAYER_MAX_H_DP);
        final int[] startY = {0};
        final int[] startH = {0};
        resizeHandle.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startY[0] = (int) event.getRawY();
                    startH[0] = webviewContainer.getHeight();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dy  = (int) event.getRawY() - startY[0];
                    int newH = Math.max(minH, Math.min(maxH, startH[0] + dy));
                    ViewGroup.LayoutParams lp = webviewContainer.getLayoutParams();
                    lp.height = newH;
                    webviewContainer.setLayoutParams(lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    return true;
            }
            return false;
        });
    }

    // ── Fullscreen toggle ─────────────────────────────────────────────────────
    private void togglePlayerFullscreen() {
        if (isPlayerFullscreen) {
            exitPlayerFullscreen();
        } else {
            enterPlayerFullscreen();
        }
    }

    private void enterPlayerFullscreen() {
        if (webviewContainer == null || fullscreenContainer == null) return;
        // Remember normal position
        playerNormalParent      = (ViewGroup) webviewContainer.getParent();
        playerNormalParentIndex = playerNormalParent.indexOfChild(webviewContainer);
        playerNormalHeightPx    = webviewContainer.getHeight();
        // Move to fullscreen container
        playerNormalParent.removeView(webviewContainer);
        FrameLayout.LayoutParams fslp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        webviewContainer.setLayoutParams(fslp);
        fullscreenContainer.addView(webviewContainer);
        fullscreenContainer.setVisibility(View.VISIBLE);
        isPlayerFullscreen = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        hideSystemUI();
        updateFullscreenIcon(true);
    }

    private void exitPlayerFullscreen() {
        if (webviewContainer == null || fullscreenContainer == null || playerNormalParent == null) return;
        fullscreenContainer.removeView(webviewContainer);
        fullscreenContainer.setVisibility(View.GONE);
        int restoreH = playerNormalHeightPx > 0 ? playerNormalHeightPx : dpToPx(212);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, restoreH);
        webviewContainer.setLayoutParams(lp);
        playerNormalParent.addView(webviewContainer, playerNormalParentIndex);
        isPlayerFullscreen = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        updateFullscreenIcon(false);
    }

    private void updateFullscreenIcon(boolean fullscreen) {
        if (webviewContainer == null) return;
        ImageView btn = webviewContainer.findViewById(R.id.btnPlayerFullscreen);
        if (btn != null) {
            btn.setImageResource(fullscreen
                ? R.drawable.ic_media_fullscreen_exit
                : R.drawable.ic_media_fullscreen);
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // ── Called when a video URL is captured (from intercept or JS) ────────────
    private void onVideoUrlCaptured(String url, String referer, boolean isM3u8) {
        // ── Hide intercepting overlay, show STREAM badge ──────────────────────
        if (webviewContainer != null) {
            View overlay = webviewContainer.findViewById(R.id.interceptingOverlay);
            if (overlay != null) overlay.setVisibility(View.GONE);
            TextView badge = webviewContainer.findViewById(R.id.playerStreamBadge);
            if (badge != null) {
                badge.setVisibility(View.VISIBLE);
                badge.animate().alpha(1f).setDuration(300).start();
            }
        }
        // ── Channel player: highlight 📡 button ──────────────────────────────
        View castContainer = findViewById(R.id.btnCastContainer);
        if (castContainer != null) {
            castContainer.animate().alpha(1f).setDuration(300).start();
            TextView waitLbl = findViewById(R.id.btnPlayerCastWaiting);
            if (waitLbl != null) waitLbl.setText("listo ·");
        }
        // ── Movie detail panel: mark Transmitir as ready ──────────────────────
        View btnCastDetail = findViewById(R.id.pdBtnCast);
        if (btnCastDetail != null) updateDetailCastBtn(btnCastDetail, true);

        if (autoDownload) {
            // Download runs in the background service — doesn't block play/cast below
            autoDownload = false;
            startRealDownload();
        }

        if (manualMode) {
            // Stay in WebView so user can choose quality/source manually
            Toast.makeText(this,
                "Stream capturado ✓  Usa los controles del reproductor para elegir calidad",
                Toast.LENGTH_LONG).show();
            return;
        }

        if (autoCast) {
            // Auto-open cast selector instead of launching MediaActivity
            Toast.makeText(this, "Stream listo — elige dispositivo para transmitir", Toast.LENGTH_SHORT).show();
            new Handler(Looper.getMainLooper()).postDelayed(this::showCastOptions, 300);
            return;
        }

        // Default: launch native ExoPlayer
        Intent intent = new Intent(this, MediaActivity.class);
        intent.putExtra("url",        url);
        intent.putExtra("title",      videoTitle != null ? videoTitle : "");
        intent.putExtra("referer",    referer != null ? referer : "");
        intent.putExtra("is_m3u8",   isM3u8);
        // Pass item and server so MediaActivity can update ContinueWatchingManager
        if (item != null) intent.putExtra("item", item);
        intent.putExtra("server_url", currentEmbedUrl != null ? currentEmbedUrl : "");
        intent.putExtra("poster_url", item != null ? item.getPosterUrl() : "");
        intent.putExtra("season", autoFetchSeason);
        intent.putExtra("episode", autoFetchEpisode);
        // Forward resume position (e.g. coming from widget or ContinueWatchingAdapter)
        long resumeMsFromIntent = getIntent().getLongExtra("resume_ms", 0);
        if (resumeMsFromIntent > 0) intent.putExtra("resume_ms", resumeMsFromIntent);
        startActivityForResult(intent, MediaActivity.REQUEST_CODE);
    }

    // ── Update the "Transmitir" button appearance in the detail panel ─────────
    private void updateDetailCastBtn(View btn, boolean ready) {
        if (!(btn instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup vg = (android.view.ViewGroup) btn;
        // child 0 = ImageView (icon), child 1 = TextView (label)
        for (int i = 0; i < vg.getChildCount(); i++) {
            android.view.View child = vg.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setText(ready ? "Transmitir ✓" : "Buscando…");
                ((TextView) child).setTextColor(ready ? 0xFF4FC3F7 : 0xAAFFFFFF);
            }
            if (child instanceof android.widget.ImageView) {
                ((android.widget.ImageView) child).setAlpha(ready ? 1f : 0.5f);
            }
        }
        btn.setAlpha(ready ? 1f : 0.6f);
    }

    // ── Real download (MP4 → Android DownloadManager, HLS → VideoDownloadService) ──
    private void startRealDownload() {
        if (item == null) return;
        if (capturedVideoUrl == null || capturedVideoUrl.isEmpty()) {
            Toast.makeText(this, "Espera mientras se captura el stream…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (capturedIsM3u8) {
            DownloadsManager.startVideoDownload(this, item, capturedVideoUrl, capturedReferer);
        } else {
            DownloadsManager.startDirectMp4Download(this, item, capturedVideoUrl);
        }
        Toast.makeText(this,
            "Descarga iniciada (" + (capturedIsM3u8 ? "HLS" : "MP4") + ") ⬇  Revisa tus Descargas",
            Toast.LENGTH_LONG).show();
        LinearLayout btnDownload = findViewById(R.id.pdBtnDownload);
        if (btnDownload != null) updateDownloadBtn(btnDownload);
    }

    // ── Cast options sheet (DLNA / AirPlay / Chromecast) ─────────────────────
    private void showCastOptions() {
        if (capturedVideoUrl == null || capturedVideoUrl.isEmpty()) {
            Toast.makeText(this,
                "Espera mientras se captura el stream…", Toast.LENGTH_SHORT).show();
            return;
        }
        final String castUrl   = capturedVideoUrl;
        final String castTitle = videoTitle != null ? videoTitle : "";
        final boolean castM3u8 = capturedIsM3u8;

        CastBottomSheet sheet = CastBottomSheet.newInstance();
        sheet.setVideoInfo(castUrl, castTitle, castM3u8);
        sheet.setCallback(device -> {
            if (device == null) return;
            switch (device.getType()) {
                case DLNA:
                    Toast.makeText(this, "Conectando a " + device.getName() + "…", Toast.LENGTH_SHORT).show();
                    DLNAManager.getInstance().playVideo(device, castUrl, () ->
                        runOnUiThread(() -> Toast.makeText(this,
                            "No se pudo conectar al TV DLNA. ¿Estás en la misma red WiFi?",
                            Toast.LENGTH_LONG).show()));
                    break;
                case AIRPLAY:
                    Toast.makeText(this, "Conectando a " + device.getName() + "…", Toast.LENGTH_SHORT).show();
                    AirPlayManager.getInstance().playVideo(device, castUrl, 0, () ->
                        runOnUiThread(() -> Toast.makeText(this,
                            "Apple TV no respondió. Verifica que esté en la misma red WiFi.",
                            Toast.LENGTH_LONG).show()));
                    break;
                case CHROMECAST:
                    Intent castIntent = new Intent(this, MediaActivity.class);
                    castIntent.putExtra("url",     castUrl);
                    castIntent.putExtra("title",   castTitle);
                    castIntent.putExtra("referer", capturedReferer != null ? capturedReferer : "");
                    castIntent.putExtra("is_m3u8", castM3u8);
                    startActivityForResult(castIntent, MediaActivity.REQUEST_CODE);
                    Toast.makeText(this, "Abriendo para Chromecast…", Toast.LENGTH_SHORT).show();
                    break;
            }
        });
        sheet.show(getSupportFragmentManager(), "castSheet");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MediaActivity.REQUEST_CODE && resultCode == MediaActivity.RESULT_RETRY) {
            // User wants to try another server — reset and auto-try next
            capturedVideoUrl = null;
            capturedReferer  = null;
            if (!serverUrls.isEmpty()) {
                currentServerIndex++;
                loadNextServer();
            } else {
                Toast.makeText(this, "Selecciona otro servidor", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ── Auto-fetch servers from StreamingApi in background ───────────────────

    private void fetchServersInBackground() {
        if (item == null) return;
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.execute(() -> {
            try {
                StreamingApi.ServerData data = item.isAnime()
                        ? AnimeApi.fetchEpisodeServers(item, autoFetchSeason, autoFetchEpisode)
                        : item.getContentType() == com.ultragol.app.models.ContentItem.TYPE_MOVIE
                        ? StreamingApi.fetchMovieServers(item.getTmdbId())
                        : StreamingApi.fetchSeriesServers(item.getTmdbId(), autoFetchSeason, autoFetchEpisode);

                // Build ordered URL list: latino → español → subtitulado → english (last resort)
                List<String> urls = new ArrayList<>();
                for (StreamingApi.Server s : data.latino)      if (s.url != null && !s.url.isEmpty()) urls.add(s.url);
                for (StreamingApi.Server s : data.espanol)     if (s.url != null && !s.url.isEmpty()) urls.add(s.url);
                for (StreamingApi.Server s : data.subtitulado) if (s.url != null && !s.url.isEmpty()) urls.add(s.url);
                for (StreamingApi.Server s : data.english)     if (s.url != null && !s.url.isEmpty()) urls.add(s.url);
                if (urls.isEmpty() && data.embedUrl != null && !data.embedUrl.isEmpty()) urls.add(data.embedUrl);

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (isFinishing()) return;
                    serverUrls.clear();
                    serverUrls.addAll(urls);
                    currentServerIndex = 0;

                    if (!serverUrls.isEmpty()) {
                        String bestUrl = serverUrls.get(0);
                        // Load best server if it differs from what's already loading
                        if (!bestUrl.equals(currentEmbedUrl)) {
                            capturedVideoUrl = null;
                            capturedReferer  = null;
                            currentEmbedUrl  = bestUrl;
                            webView.loadUrl(bestUrl);
                        }
                        // Schedule auto-retry in case this server doesn't play
                        scheduleAutoRetry();
                    }
                });
            } catch (Exception ignored) {
                // Keep loading whatever URL was already set; schedule retry from that
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isFinishing()) scheduleAutoRetry();
                });
            }
        });
        exec.shutdown();
    }

    /**
     * Schedules a check: if no stream URL has been captured after AUTO_RETRY_DELAY_MS,
     * automatically load the next server from the list.
     */
    private void scheduleAutoRetry() {
        autoRetryHandler.removeCallbacksAndMessages(null);
        autoRetryHandler.postDelayed(() -> {
            if (isFinishing()) return;
            if (capturedVideoUrl != null) return; // already playing
            if (serverUrls.isEmpty()) return;
            currentServerIndex++;
            loadNextServer();
        }, AUTO_RETRY_DELAY_MS);
    }

    /**
     * Loads the next server URL from the list, cycling back if needed.
     * Shows a subtle toast so the user knows the app is trying another source.
     */
    private void loadNextServer() {
        if (serverUrls.isEmpty()) return;
        // Wrap around the list
        if (currentServerIndex >= serverUrls.size()) currentServerIndex = 0;

        String nextUrl = serverUrls.get(currentServerIndex);
        capturedVideoUrl = null;
        capturedReferer  = null;
        currentEmbedUrl  = nextUrl;
        webView.loadUrl(nextUrl);

        int humanIdx = currentServerIndex + 1;
        Toast.makeText(this,
                "Probando servidor " + humanIdx + " de " + serverUrls.size() + "…",
                Toast.LENGTH_SHORT).show();

        // Schedule another retry in case this server also fails
        scheduleAutoRetry();
    }

    // ── Detail panel ──────────────────────────────────────────────────────────

    private void bindDetailPanel() {
        TextView pdTitle     = findViewById(R.id.pdTitle);
        TextView pdMeta      = findViewById(R.id.pdMeta);
        TextView pdOverview  = findViewById(R.id.pdOverview);
        TextView pdTypeBadge = findViewById(R.id.pdTypeBadge);

        if (pdTitle != null) pdTitle.setText(item.getTitle());
        if (pdOverview != null) pdOverview.setText(item.getOverview());
        if (pdTypeBadge != null) {
            switch (item.getContentType()) {
                case ContentItem.TYPE_SERIES: pdTypeBadge.setText("SERIE");   break;
                case ContentItem.TYPE_ANIME:  pdTypeBadge.setText("ANIME");   break;
                case ContentItem.TYPE_DORAMA: pdTypeBadge.setText("DORAMA");  break;
                case ContentItem.TYPE_SPORT:  pdTypeBadge.setText("EN VIVO"); break;
                case ContentItem.TYPE_TV:     pdTypeBadge.setText("TV");      break;
                default:                      pdTypeBadge.setText("PELÍCULA"); break;
            }
        }

        // ── Carga poster/backdrop en el overlay de carga ───────────────────────
        ImageView posterBg = webviewContainer != null
            ? webviewContainer.findViewById(R.id.playerPosterBg) : null;
        if (posterBg != null) {
            String imgUrl = item.getBackdropUrl() != null && !item.getBackdropUrl().isEmpty()
                ? item.getBackdropUrl() : item.getPosterUrl();
            if (imgUrl != null && !imgUrl.isEmpty()) {
                Glide.with(this).load(imgUrl)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .centerCrop()
                    .into(posterBg);
            }
        }
        if (pdMeta != null) {
            StringBuilder sb = new StringBuilder();
            if (!item.getYear().isEmpty()) sb.append(item.getYear());
            if (!item.getGenre().isEmpty()) {
                if (sb.length() > 0) sb.append("   \u00B7   ");
                sb.append(item.getGenre());
            }
            if (!item.getRating().isEmpty()) {
                if (sb.length() > 0) sb.append("   \u00B7   ");
                sb.append("\u2605 ").append(item.getRating());
            }
            pdMeta.setText(sb.toString());
        }

        LinearLayout btnLike = findViewById(R.id.pdBtnLike);
        if (btnLike != null) {
            updateLikeBtn(btnLike);
            btnLike.setOnClickListener(v -> { FavoritesManager.toggle(this, item); updateLikeBtn(btnLike); });
        }

        LinearLayout btnDownload = findViewById(R.id.pdBtnDownload);
        if (btnDownload != null) {
            updateDownloadBtn(btnDownload);
            btnDownload.setOnClickListener(v -> {
                String state = DownloadsManager.getVideoState(this, item);
                if ("COMPLETE".equals(state)) {
                    new android.app.AlertDialog.Builder(this)
                        .setTitle("Eliminar descarga")
                        .setMessage("\u00bfEliminar \"" + item.getTitle() + "\" de tus descargas?")
                        .setPositiveButton("Eliminar", (d, w) -> {
                            DownloadsManager.remove(this, item);
                            updateDownloadBtn(btnDownload);
                            Toast.makeText(this, "Descarga eliminada", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
                } else if ("DOWNLOADING".equals(state)) {
                    Toast.makeText(this, "Ya se est\u00e1 descargando...", Toast.LENGTH_SHORT).show();
                } else if (!DownloadsManager.ensureStoragePermission(this)) {
                    Toast.makeText(this,
                        "Concede el permiso de almacenamiento y toca Descargar otra vez",
                        Toast.LENGTH_LONG).show();
                } else if (capturedVideoUrl != null && !capturedVideoUrl.isEmpty()) {
                    startRealDownload();
                } else {
                    autoDownload = true;
                    TextView icon  = findViewById(R.id.pdDownloadIcon);
                    TextView label = findViewById(R.id.pdDownloadLabel);
                    if (icon  != null) { icon.setText("\u23f3"); icon.setTextColor(0xFFAAAAAA); }
                    if (label != null) { label.setText("Buscando\u2026"); label.setTextColor(0xFFAAAAAA); }
                    Toast.makeText(this, "Buscando stream para descargar\u2026", Toast.LENGTH_SHORT).show();
                }
            });
        }

        View btnCastDetail = findViewById(R.id.pdBtnCast);
        if (btnCastDetail != null) {
            // Show ready state if URL was already captured (e.g. returning from MediaActivity)
            updateDetailCastBtn(btnCastDetail, capturedVideoUrl != null && !capturedVideoUrl.isEmpty());

            btnCastDetail.setOnClickListener(v -> {
                String castUrl = capturedVideoUrl != null ? capturedVideoUrl : "";
                if (castUrl.isEmpty()) {
                    // Stream not captured yet → activate autocast and wait for WebView intercept
                    autoCast   = true;
                    manualMode = false;
                    updateDetailCastBtn(btnCastDetail, false);
                    Toast.makeText(this,
                        "Buscando stream… el selector aparecerá automáticamente al detectarlo",
                        Toast.LENGTH_LONG).show();
                    return;
                }
                // URL ready → show cast sheet immediately
                showCastOptions();
            });
        }

        View btnShare = findViewById(R.id.pdBtnShare);
        if (btnShare != null) btnShare.setOnClickListener(v -> {
            ServerSelectDialog.show(this, item, 1, 1);
        });

        loadSimilar();
    }

    private void updateLikeBtn(LinearLayout btn) {
        boolean isFav = FavoritesManager.isFav(this, item);
        TextView icon  = findViewById(R.id.pdLikeIcon);
        TextView label = findViewById(R.id.pdLikeLabel);
        if (icon != null) {
            icon.setText(isFav ? "\u2665" : "\u2661");
            icon.setTextColor(isFav ? android.graphics.Color.parseColor("#FF5252") : android.graphics.Color.WHITE);
        }
        if (label != null) {
            label.setText(isFav ? "Te gusta" : "Me gusta");
            label.setTextColor(isFav ? android.graphics.Color.parseColor("#FF5252") : android.graphics.Color.WHITE);
        }
    }

    private void updateDownloadBtn(LinearLayout btn) {
        String state = item != null ? DownloadsManager.getVideoState(this, item) : "NONE";
        TextView icon  = findViewById(R.id.pdDownloadIcon);
        TextView label = findViewById(R.id.pdDownloadLabel);
        switch (state) {
            case "COMPLETE":
                if (icon  != null) { icon.setText("\u2713");        icon.setTextColor(android.graphics.Color.parseColor("#4CAF50")); }
                if (label != null) { label.setText("Descargado"); label.setTextColor(android.graphics.Color.parseColor("#4CAF50")); }
                break;
            case "DOWNLOADING":
                if (icon  != null) { icon.setText("\u23f3");           icon.setTextColor(0xFFAAAAAA); }
                if (label != null) { label.setText("Descargando..."); label.setTextColor(0xFFAAAAAA); }
                break;
            case "FAILED":
                if (icon  != null) { icon.setText("\u26a0");       icon.setTextColor(android.graphics.Color.parseColor("#FF5252")); }
                if (label != null) { label.setText("Reintentar"); label.setTextColor(android.graphics.Color.parseColor("#FF5252")); }
                break;
            default:
                if (icon  != null) { icon.setText("\u2b07");       icon.setTextColor(android.graphics.Color.parseColor("#4FC3F7")); }
                if (label != null) { label.setText("Descargar"); label.setTextColor(android.graphics.Color.parseColor("#4FC3F7")); }
                break;
        }
    }

    private void loadSimilar() {
        View row = findViewById(R.id.pdRowSimilar);
        if (row == null) return;
        TextView rowTitle = row.findViewById(R.id.rowTitle);
        RecyclerView rv   = row.findViewById(R.id.rowRv);
        if (rv != null) rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        String sectionTitle = item.getContentType() == ContentItem.TYPE_MOVIE ? "Pel\u00edculas similares"
                : item.getContentType() == ContentItem.TYPE_ANIME ? "Animes similares" : "Series similares";
        if (rowTitle != null) rowTitle.setText(sectionTitle);
        Handler h = new Handler(Looper.getMainLooper());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.execute(() -> {
            try {
                List<ContentItem> related = TmdbApi.fetchSimilar(item.getTmdbId(), item.getContentType());
                if (related.isEmpty()) {
                    switch (item.getContentType()) {
                        case ContentItem.TYPE_ANIME:  related = TmdbApi.fetchAnime(); break;
                        case ContentItem.TYPE_SERIES: related = TmdbApi.fetchSeries(); break;
                        case ContentItem.TYPE_DORAMA: related = TmdbApi.fetchDoramas(); break;
                        default:                      related = TmdbApi.fetchMovies(); break;
                    }
                }
                final List<ContentItem> fin = related;
                h.post(() -> { if (!isFinishing() && rv != null) rv.setAdapter(new ContentRowAdapter(this, fin)); });
            } catch (Exception ignored) {}
        });
        pool.shutdown();
    }

    // ── System UI ─────────────────────────────────────────────────────────────

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override public void onBackPressed() {
        if (isPlayerFullscreen) { exitPlayerFullscreen(); return; }
        if (customView != null) { webView.getWebChromeClient().onHideCustomView(); return; }
        if (webView.canGoBack()) { webView.goBack(); return; }
        super.onBackPressed();
    }
    @Override protected void onPause()   { super.onPause();   webView.onPause(); }
    @Override protected void onResume()  { super.onResume();  webView.onResume(); }
    @Override protected void onDestroy() {
        autoRetryHandler.removeCallbacksAndMessages(null);
        captureGraceHandler.removeCallbacksAndMessages(null);
        webView.destroy();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (TvHelper.handleGlobalKeyEvent(this, event)) return true;
        return super.dispatchKeyEvent(event);
    }

    // ── Inner class: receives URLs from injected JavaScript ──────────────────

    private final class HtmlOutInterface {
        @JavascriptInterface
        public void onUrl(final String url, final String source) {
            if (url == null || url.isEmpty() || capturedVideoUrl != null) return;
            boolean isM3u8 = url.contains(".m3u8") || url.contains("m3u8");
            boolean isMp4  = url.contains(".mp4");
            if (!isM3u8 && !isMp4) return;
            if (isLikelyDecoyStream(url)) return; // known ad/placeholder/test video — never accept

            pendingCandidateUrl     = url;
            pendingCandidateReferer = currentEmbedUrl;
            pendingCandidateIsM3u8  = isM3u8;

            // Reset the grace window: if a different (real) source shows up shortly
            // after this one — e.g. once a preroll ad finishes — it replaces this
            // candidate. Only whichever URL is still pending once things go quiet
            // gets treated as final.
            captureGraceHandler.removeCallbacksAndMessages(null);
            captureGraceHandler.postDelayed(() -> {
                if (capturedVideoUrl != null || pendingCandidateUrl == null) return;
                capturedVideoUrl = pendingCandidateUrl;
                capturedReferer  = pendingCandidateReferer;
                capturedIsM3u8   = pendingCandidateIsM3u8;
                onVideoUrlCaptured(capturedVideoUrl, capturedReferer, capturedIsM3u8);
            }, CAPTURE_GRACE_MS);
        }
    }
}
