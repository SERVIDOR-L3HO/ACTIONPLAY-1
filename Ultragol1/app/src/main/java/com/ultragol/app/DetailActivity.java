package com.ultragol.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.*;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.ultragol.app.DownloadsManager;
import com.ultragol.app.adapters.ContentRowAdapter;
import com.ultragol.app.models.ContentItem;
import com.ultragol.app.network.A7xConstants;
import com.ultragol.app.network.A7xIptvApi;
import com.ultragol.app.network.AnimeApi;
import com.ultragol.app.network.StreamingApi;
import com.ultragol.app.network.TmdbApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class DetailActivity extends AppCompatActivity {

    private ContentItem item;
    private int currentSeason = 1;
    private int totalSeasons  = 1;

    // Loading-servers overlay
    private final Handler loadingHandler = new Handler(Looper.getMainLooper());
    private final List<Animator> dotAnimators = new ArrayList<>();

    // Trailer
    private String trailerKey = "";

    // Background WebView for real MP4 URL capture (download flow)
    private WebView            captureWebView       = null;
    private volatile String    capturedDownloadUrl  = null;
    private volatile String    capturedDownloadRef  = null;
    private volatile boolean   capturedIsM3u8       = false;
    private AlertDialog        downloadingDialog    = null;
    private LinearLayout       pendingDownloadBtn   = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        item = (ContentItem) getIntent().getSerializableExtra("item");
        if (item == null) { finish(); return; }

        bindViews();
        loadSaga();
        loadRelated();
        loadTrailer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loadingHandler.removeCallbacksAndMessages(null);
        destroyCaptureWebView();
        if (downloadingDialog != null && downloadingDialog.isShowing()) {
            downloadingDialog.dismiss();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DOWNLOAD CAPTURE — hidden WebView intercepts real MP4/M3U8 URL
    // ══════════════════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════════════════
    //  SMART DOWNLOAD — tries StreamingApi first, then WebView capture fallback
    // ══════════════════════════════════════════════════════════════════════════

    private void startSmartDownload() {
        // Show progress dialog immediately
        downloadingDialog = new AlertDialog.Builder(this)
            .setTitle("Preparando descarga")
            .setMessage("Buscando fuente de video…")
            .setCancelable(true)
            .setNegativeButton("Cancelar", (d, w) -> {
                destroyCaptureWebView();
                if (pendingDownloadBtn != null) updateDownloadBtn(pendingDownloadBtn);
            })
            .create();
        downloadingDialog.show();

        ExecutorService exec = java.util.concurrent.Executors.newSingleThreadExecutor();
        exec.execute(() -> {
            String mp4Url  = null;
            String m3u8Url = null;
            try {
                StreamingApi.ServerData data = item.isAnime()
                    ? AnimeApi.fetchEpisodeServers(item, 1, 1)
                    : item.getContentType() == ContentItem.TYPE_MOVIE
                    ? StreamingApi.fetchMovieServers(item.getTmdbId())
                    : StreamingApi.fetchSeriesServers(item.getTmdbId(), 1, 1);

                // Collect all servers in priority order
                List<StreamingApi.Server> all = new ArrayList<>(data.latino);
                all.addAll(data.espanol);
                all.addAll(data.subtitulado);

                for (StreamingApi.Server s : all) {
                    String u = s.url != null ? s.url : "";
                    if (StreamValidator.isKnownDecoyUrl(u)) continue;   // ad/test clip, not the title
                    if (u.contains(".mp4")  && mp4Url  == null) mp4Url  = u;
                    if (u.contains(".m3u8") && m3u8Url == null) m3u8Url = u;
                }
                // Also scan embedUrl
                String emb = data.embedUrl != null ? data.embedUrl : "";
                if (!StreamValidator.isKnownDecoyUrl(emb)) {
                    if (emb.contains(".mp4")  && mp4Url  == null) mp4Url  = emb;
                    if (emb.contains(".m3u8") && m3u8Url == null) m3u8Url = emb;
                }

            } catch (Exception ignored) {}

            final String finalMp4  = mp4Url;
            final String finalM3u8 = m3u8Url;

            new Handler(Looper.getMainLooper()).post(() -> {
                if (isFinishing()) return;
                if (downloadingDialog != null && downloadingDialog.isShowing())
                    downloadingDialog.dismiss();

                if (finalMp4 != null) {
                    // Best case: direct MP4 → Android DownloadManager → real .mp4 file
                    DownloadsManager.startDirectMp4Download(this, item, finalMp4);
                    if (pendingDownloadBtn != null) updateDownloadBtn(pendingDownloadBtn);
                    Toast.makeText(this,
                        "⬇ Descargando MP4 — revisa la bandeja de notificaciones",
                        Toast.LENGTH_LONG).show();

                } else if (finalM3u8 != null) {
                    // M3U8 direct → ExoPlayer offline download
                    DownloadsManager.startVideoDownload(this, item, finalM3u8, null);
                    if (pendingDownloadBtn != null) updateDownloadBtn(pendingDownloadBtn);
                    Toast.makeText(this,
                        "⬇ Descarga iniciada (HLS) — revisa tus Descargas",
                        Toast.LENGTH_LONG).show();

                } else {
                    // Fallback: WebView capture
                    startDownloadCapture();
                }
            });
        });
        exec.shutdown();
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private void startDownloadCapture() {
        capturedDownloadUrl = null;
        capturedDownloadRef = null;
        capturedIsM3u8      = false;

        // Build the capture WebView (invisible, not attached to hierarchy)
        captureWebView = new WebView(this);
        WebSettings ws = captureWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setUserAgentString(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        captureWebView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                String ref = req.getRequestHeaders().get("Referer");
                boolean m3u8 = url.contains(".m3u8");
                boolean mp4  = url.contains(".mp4") || url.contains(".MP4");
                if ((mp4 || m3u8) && capturedDownloadUrl == null
                        && !StreamValidator.isKnownDecoyUrl(url)) {
                    capturedDownloadUrl = url;
                    capturedDownloadRef = ref != null ? ref : item.getStreamUrl();
                    capturedIsM3u8      = m3u8;
                    new Handler(Looper.getMainLooper()).post(() -> onVideoUrlCapturedForDownload());
                }
                return null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // JS extraction attempts at 2s and 5s — same as PlayerActivity
                String JS =
                    "(function(){try{"
                    + "if(window.jwplayer&&jwplayer().getPlaylistItem()){"
                    +   "var pi=jwplayer().getPlaylistItem();"
                    +   "var src=pi.file||(pi.sources&&pi.sources[0]&&pi.sources[0].file);"
                    +   "if(src){window.DLOAD.onUrl(src);return;}"
                    + "}"
                    + "var srcs=document.querySelectorAll('source[src]');"
                    + "for(var j=0;j<srcs.length;j++){"
                    +   "var s=srcs[j].src;"
                    +   "if(s&&(s.includes('.m3u8')||s.includes('.mp4'))){"
                    +     "window.DLOAD.onUrl(s);return;"
                    +   "}"
                    + "}"
                    + "var scripts=document.querySelectorAll('script');"
                    + "for(var k=0;k<scripts.length;k++){"
                    +   "var t=scripts[k].innerText;"
                    +   "var m=t.match(/[\"'](https?:\\/\\/[^\"']+\\.mp4[^\"']*)[\"']/);"
                    +   "if(m){window.DLOAD.onUrl(m[1]);return;}"
                    +   "var m2=t.match(/[\"'](https?:\\/\\/[^\"']+\\.m3u8[^\"']*)[\"']/);"
                    +   "if(m2){window.DLOAD.onUrl(m2[1]);return;}"
                    + "}"
                    + "}catch(e){}})();";
                captureWebView.addJavascriptInterface(new Object() {
                    @android.webkit.JavascriptInterface
                    public void onUrl(String url) {
                        if (capturedDownloadUrl == null && url != null && !url.isEmpty()
                                && !StreamValidator.isKnownDecoyUrl(url)) {
                            capturedDownloadUrl = url;
                            capturedDownloadRef = item.getStreamUrl();
                            capturedIsM3u8      = url.contains(".m3u8");
                            new Handler(Looper.getMainLooper()).post(() -> onVideoUrlCapturedForDownload());
                        }
                    }
                }, "DLOAD");
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (capturedDownloadUrl == null && !isFinishing() && captureWebView != null)
                        captureWebView.evaluateJavascript("javascript:" + JS, null);
                }, 2000);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (capturedDownloadUrl == null && !isFinishing() && captureWebView != null)
                        captureWebView.evaluateJavascript("javascript:" + JS, null);
                }, 5000);
            }

            @Override public void onPageStarted(WebView v, String u, Bitmap f) {}
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    v.loadUrl(r.getUrl().toString()); return true;
                }
                return true;
            }
        });

        // Show progress dialog
        downloadingDialog = new AlertDialog.Builder(this)
            .setTitle("Preparando descarga")
            .setMessage("Buscando video... esto puede tardar unos segundos.")
            .setCancelable(true)
            .setNegativeButton("Cancelar", (d, w) -> {
                destroyCaptureWebView();
                if (pendingDownloadBtn != null) updateDownloadBtn(pendingDownloadBtn);
            })
            .create();
        downloadingDialog.show();

        // Timeout: if no URL after 15s → give up
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (capturedDownloadUrl == null && !isFinishing()) {
                destroyCaptureWebView();
                if (downloadingDialog != null && downloadingDialog.isShowing())
                    downloadingDialog.dismiss();
                if (pendingDownloadBtn != null) updateDownloadBtn(pendingDownloadBtn);
                Toast.makeText(this,
                    "No se pudo obtener el video para descargar.\nIntenta reproducirlo primero.",
                    Toast.LENGTH_LONG).show();
            }
        }, 15000);

        captureWebView.loadUrl(item.getStreamUrl());
    }

    private void onVideoUrlCapturedForDownload() {
        if (isFinishing() || capturedDownloadUrl == null) return;

        if (downloadingDialog != null && downloadingDialog.isShowing())
            downloadingDialog.dismiss();

        // Accept both M3U8 (HLS) and MP4 — ExoPlayer offline handles both
        String videoUrl = capturedDownloadUrl;
        String referer  = capturedDownloadRef;
        destroyCaptureWebView();

        DownloadsManager.startVideoDownload(this, item, videoUrl, referer);
        if (pendingDownloadBtn != null) updateDownloadBtn(pendingDownloadBtn);
        String fmt = capturedIsM3u8 ? "HLS" : "MP4";
        Toast.makeText(this,
            "Descarga iniciada (" + fmt + ") ⬇  Revisa tus Descargas",
            Toast.LENGTH_LONG).show();
    }

    private void destroyCaptureWebView() {
        if (captureWebView != null) {
            captureWebView.stopLoading();
            captureWebView.destroy();
            captureWebView = null;
        }
    }

    private void bindViews() {
        ImageView backdrop     = findViewById(R.id.detailBackdrop);
        ImageView poster       = findViewById(R.id.detailPoster);
        TextView  typeBadge    = findViewById(R.id.detailTypeBadge);
        TextView  ratingBadge  = findViewById(R.id.detailBadge);
        TextView  title        = findViewById(R.id.detailTitle);
        TextView  meta         = findViewById(R.id.detailMeta);
        TextView  rating       = findViewById(R.id.detailRating);
        TextView  overview     = findViewById(R.id.detailOverview);
        LinearLayout genreChips = findViewById(R.id.genreChips);
        View btnPlay             = findViewById(R.id.btnPlay);
        View btnBack             = findViewById(R.id.btnDetailBack);
        LinearLayout btnFavorite = findViewById(R.id.btnFavorite);
        LinearLayout btnMyList   = findViewById(R.id.btnMyList);
        LinearLayout btnDownload = findViewById(R.id.btnDownload);

        // ── Title ──────────────────────────────────────────────────────────────
        if (title != null) title.setText(item.getTitle());

        // ── Type badge (PELÍCULA / SERIE / ANIME / DORAMA) ────────────────────
        if (typeBadge != null) {
            String label;
            int badgeColor;
            switch (item.getContentType()) {
                case ContentItem.TYPE_SERIES:
                    label = "SERIE";    badgeColor = Color.parseColor("#00838F"); break;
                case ContentItem.TYPE_ANIME:
                    label = "ANIME";    badgeColor = Color.parseColor("#AD1457"); break;
                case ContentItem.TYPE_DORAMA:
                    label = "DORAMA";   badgeColor = Color.parseColor("#00695C"); break;
                case ContentItem.TYPE_SPORT:
                    label = "EN VIVO";  badgeColor = Color.parseColor("#C62828"); break;
                case ContentItem.TYPE_TV:
                    label = "TV";       badgeColor = Color.parseColor("#2E7D32"); break;
                default:
                    label = "PELÍCULA"; badgeColor = Color.parseColor("#CC1111"); break;
            }
            typeBadge.setText(label);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(badgeColor);
            bg.setCornerRadius(dp(5));
            typeBadge.setBackground(bg);
        }

        // ── Rating badge (C, B, A etc.) ────────────────────────────────────────
        if (ratingBadge != null) ratingBadge.setText(item.getBadge());

        // ── Meta: "2026  1h 25m  ES" ───────────────────────────────────────────
        if (meta != null) {
            StringBuilder sb = new StringBuilder();
            if (!item.getYear().isEmpty()) sb.append(item.getYear());
            if (!item.getRating().isEmpty()) {
                if (sb.length() > 0) sb.append("   \u00B7   ");
                sb.append(item.getRating());
            }
            sb.append("   \u00B7   ES");
            meta.setText(sb.toString());
        }

        // ── ◆ Rating number ────────────────────────────────────────────────────
        if (rating != null) {
            String r = item.getRating();
            rating.setText(r.isEmpty() ? "—" : r);
        }

        // ── Genre chips ────────────────────────────────────────────────────────
        if (genreChips != null && !item.getGenre().isEmpty()) {
            String[] genres = item.getGenre().split("[,/•]");
            for (String g : genres) {
                String lbl = g.trim();
                if (lbl.isEmpty()) continue;
                TextView chip = new TextView(this);
                chip.setText(lbl);
                chip.setTextColor(0xCCFFFFFF);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                GradientDrawable chipBg = new GradientDrawable();
                chipBg.setShape(GradientDrawable.RECTANGLE);
                chipBg.setColor(0x00000000);
                chipBg.setStroke(dp(1), 0x55FFFFFF);
                chipBg.setCornerRadius(dp(20));
                chip.setBackground(chipBg);
                int padH = dp(14), padV = dp(6);
                chip.setPadding(padH, padV, padH, padV);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(dp(8));
                chip.setLayoutParams(lp);
                genreChips.addView(chip);
            }
        }

        // ── Overview ───────────────────────────────────────────────────────────
        if (overview != null) overview.setText(item.getOverview());

        // ── Images ─────────────────────────────────────────────────────────────
        if (backdrop != null && !item.getBackdropUrl().isEmpty()) {
            Glide.with(this).load(item.getBackdropUrl())
                .transition(DrawableTransitionOptions.withCrossFade()).into(backdrop);
        }
        if (poster != null && !item.getPosterUrl().isEmpty()) {
            Glide.with(this).load(item.getPosterUrl())
                .transition(DrawableTransitionOptions.withCrossFade()).into(poster);
        }

        // ── Buttons ─────────────────────────────────────────────────────────────
        if (btnBack != null) btnBack.setOnClickListener(v -> {
            finish();
            overridePendingTransition(0, android.R.anim.fade_out);
        });
        // ── REPRODUCIR: pulse + shimmer animation ────────────────────────────
        startReproducirAnimations(btnPlay);

        if (btnPlay != null) btnPlay.setOnClickListener(v -> {
            fetchAndPlay(item, 1, 1);
        });

        // ── Ver Tráiler button ────────────────────────────────────────────────
        View btnTrailer = findViewById(R.id.btnTrailer);
        if (btnTrailer != null) btnTrailer.setOnClickListener(v -> {
            if (!trailerKey.isEmpty()) showTrailerModal(trailerKey);
        });


        // ── Favorito button ───────────────────────────────────────────────────
        if (btnFavorite != null) {
            updateFavoriteBtn(btnFavorite);
            btnFavorite.setOnClickListener(v -> {
                FavoritesManager.toggle(this, item);
                updateFavoriteBtn(btnFavorite);
                boolean isFav = FavoritesManager.isFav(this, item);
                Toast.makeText(this,
                    isFav ? "Agregado a Favoritos" : "Eliminado de Favoritos",
                    Toast.LENGTH_SHORT).show();
            });
        }

        // ── Mi Lista button ───────────────────────────────────────────────────
        if (btnMyList != null) {
            updateMyListBtn(btnMyList);
            btnMyList.setOnClickListener(v -> {
                MyListManager.toggle(this, item);
                updateMyListBtn(btnMyList);
                boolean inList = MyListManager.isInList(this, item);
                Toast.makeText(this,
                    inList ? "Agregado a Mi Lista" : "Eliminado de Mi Lista",
                    Toast.LENGTH_SHORT).show();
            });
        }

        // ── Descargar button ──────────────────────────────────────────────────
        if (btnDownload != null) {
            updateDownloadBtn(btnDownload);
            btnDownload.setOnClickListener(v -> {
                String state = DownloadsManager.getVideoState(this, item);
                if ("COMPLETE".equals(state)) {
                    // Already downloaded → offer to remove
                    new android.app.AlertDialog.Builder(this)
                        .setTitle("Eliminar descarga")
                        .setMessage("¿Eliminar \"" + item.getTitle() + "\" de tus descargas?")
                        .setPositiveButton("Eliminar", (d, w) -> {
                            DownloadsManager.remove(this, item);
                            updateDownloadBtn(btnDownload);
                            Toast.makeText(this, "Descarga eliminada", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
                } else if ("DOWNLOADING".equals(state)) {
                    Toast.makeText(this, "Ya se está descargando...", Toast.LENGTH_SHORT).show();
                } else if (!DownloadsManager.ensureStoragePermission(this)) {
                    Toast.makeText(this,
                        "Concede el permiso de almacenamiento y toca Descargar otra vez",
                        Toast.LENGTH_LONG).show();
                } else {
                    // Smart download: StreamingApi first → Android DM for MP4 → WebView fallback
                    pendingDownloadBtn = btnDownload;
                    setDownloadBtnLoading(btnDownload, true);
                    startSmartDownload();
                }
            });
        }

        setupEpisodeSection();
    }

    private void updateFavoriteBtn(LinearLayout btn) {
        boolean isFav = FavoritesManager.isFav(this, item);
        if (btn.getChildCount() >= 2) {
            TextView icon  = (TextView) btn.getChildAt(0);
            TextView label = (TextView) btn.getChildAt(1);
            icon.setText(isFav ? "♥  " : "♡  ");
            icon.setTextColor(isFav ? Color.parseColor("#FF5252") : Color.WHITE);
            label.setText(isFav ? "Favorito ✓" : "Favorito");
            label.setTextColor(isFav ? Color.parseColor("#FF5252") : Color.WHITE);
        }
    }

    private void updateMyListBtn(LinearLayout btn) {
        boolean inList = MyListManager.isInList(this, item);
        if (btn.getChildCount() >= 2) {
            TextView icon  = (TextView) btn.getChildAt(0);
            TextView label = (TextView) btn.getChildAt(1);
            icon.setText(inList ? "⊞  " : "⊟  ");
            icon.setTextColor(inList ? Color.parseColor("#4FC3F7") : Color.WHITE);
            label.setText(inList ? "Mi Lista ✓" : "Mi Lista");
            label.setTextColor(inList ? Color.parseColor("#4FC3F7") : Color.WHITE);
        }
    }

    private void updateDownloadBtn(LinearLayout btn) {
        btn.setEnabled(true);
        String state = DownloadsManager.getVideoState(this, item);
        TextView icon  = btn.findViewById(R.id.downloadIcon);
        TextView label = btn.findViewById(R.id.downloadLabel);
        switch (state) {
            case "COMPLETE":
                if (icon  != null) { icon.setText("✓  ");        icon.setTextColor(Color.parseColor("#4CAF50")); }
                if (label != null) { label.setText("Descargado"); label.setTextColor(Color.parseColor("#4CAF50")); }
                break;
            case "DOWNLOADING":
                if (icon  != null) { icon.setText("⏳  ");           icon.setTextColor(0xFFAAAAAA); }
                if (label != null) { label.setText("Descargando..."); label.setTextColor(0xFFAAAAAA); }
                btn.setEnabled(false);
                break;
            case "FAILED":
                if (icon  != null) { icon.setText("⚠  ");       icon.setTextColor(Color.parseColor("#FF5252")); }
                if (label != null) { label.setText("Reintentar"); label.setTextColor(Color.parseColor("#FF5252")); }
                break;
            default:
                if (icon  != null) { icon.setText("⬇  ");       icon.setTextColor(Color.parseColor("#4FC3F7")); }
                if (label != null) { label.setText("Descargar"); label.setTextColor(Color.parseColor("#4FC3F7")); }
                break;
        }
    }

    private void setDownloadBtnLoading(LinearLayout btn, boolean loading) {
        TextView icon  = btn.findViewById(R.id.downloadIcon);
        TextView label = btn.findViewById(R.id.downloadLabel);
        if (icon  != null) { icon.setText("⏳  "); icon.setTextColor(0xFFAAAAAA); }
        if (label != null) { label.setText("Descargando..."); label.setTextColor(0xFFAAAAAA); }
        btn.setEnabled(!loading);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  EPISODE SECTION — glass UI
    // ══════════════════════════════════════════════════════════════════════════

    private void setupEpisodeSection() {
        View section = findViewById(R.id.episodeSectionDetail);
        if (section == null) return;

        boolean isTV = item.getContentType() != ContentItem.TYPE_MOVIE;
        section.setVisibility(isTV ? View.VISIBLE : View.GONE);
        if (!isTV) return;

        // Wire overview into Información tab
        TextView tvInfoOv = findViewById(R.id.tvInfoOverview);
        if (tvInfoOv != null) tvInfoOv.setText(item.getOverview());

        // Tab switching
        TextView tabEp   = findViewById(R.id.tabEpisodios);
        TextView tabInfo = findViewById(R.id.tabInformacion);
        View     epContent   = findViewById(R.id.episodeTabContent);
        View     infoContent = findViewById(R.id.infoTabContent);
        View     indicator   = findViewById(R.id.tabIndicatorEp);

        if (tabEp != null) tabEp.setOnClickListener(v -> {
            setTabActive(tabEp, tabInfo, indicator, true);
            if (epContent   != null) epContent.setVisibility(View.VISIBLE);
            if (infoContent != null) infoContent.setVisibility(View.GONE);
        });
        if (tabInfo != null) tabInfo.setOnClickListener(v -> {
            setTabActive(tabInfo, tabEp, indicator, false);
            if (epContent   != null) epContent.setVisibility(View.GONE);
            if (infoContent != null) infoContent.setVisibility(View.VISIBLE);
        });

        // Season selector pill
        View seasonSel = findViewById(R.id.seasonSelector);
        if (seasonSel != null) seasonSel.setOnClickListener(v -> showSeasonPicker());

        // Fetch season count then load season 1
        loadSeasonCount();
    }

    private void setTabActive(TextView active, TextView inactive, View indicator, boolean isEpisodes) {
        if (active   != null) { active.setTextColor(0xFFFFFFFF);   active.setTypeface(null, Typeface.BOLD); }
        if (inactive != null) { inactive.setTextColor(0x44FFFFFF); inactive.setTypeface(null, Typeface.NORMAL); }
        if (indicator != null) indicator.setVisibility(isEpisodes ? View.VISIBLE : View.GONE);
    }

    private void loadSeasonCount() {
        Handler h = new Handler(android.os.Looper.getMainLooper());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.execute(() -> {
            try {
                int seasons = item.isAnime()
                        ? AnimeApi.fetchSeasonCount(item)
                        : TmdbApi.fetchSeriesSeasonCount(item.getTmdbId());
                h.post(() -> {
                    if (isFinishing()) return;
                    totalSeasons = Math.max(1, seasons);
                    updateSeasonPill();
                    loadEpisodes(currentSeason);
                });
            } catch (Exception e) {
                h.post(() -> {
                    if (!isFinishing()) loadEpisodes(1);
                });
            }
        });
        pool.shutdown();
    }

    private void updateSeasonPill() {
        TextView pill = findViewById(R.id.tvSeasonPill);
        if (pill != null) {
            String label = totalSeasons > 1
                ? "Temporada " + currentSeason + " de " + totalSeasons
                : "Temporada " + currentSeason;
            pill.setText(label);
        }
    }

    private void showSeasonPicker() {
        if (totalSeasons <= 1) return;
        String[] items = new String[totalSeasons];
        for (int i = 0; i < totalSeasons; i++) items[i] = "Temporada " + (i + 1);

        new AlertDialog.Builder(this)
            .setTitle("Seleccionar temporada")
            .setItems(items, (d, which) -> {
                currentSeason = which + 1;
                updateSeasonPill();
                loadEpisodes(currentSeason);
            })
            .show();
    }

    private void loadEpisodes(int season) {
        View loadFrame  = findViewById(R.id.epLoadingFrame);
        LinearLayout cnt = findViewById(R.id.episodeListContainer);
        if (loadFrame != null) loadFrame.setVisibility(View.VISIBLE);
        if (cnt       != null) { cnt.setVisibility(View.GONE); cnt.removeAllViews(); }

        Handler h = new Handler(android.os.Looper.getMainLooper());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.execute(() -> {
            try {
                List<TmdbApi.EpisodeInfo> eps = item.isAnime()
                        ? AnimeApi.fetchSeasonEpisodes(item, season)
                        : TmdbApi.fetchSeasonEpisodes(item.getTmdbId(), season);
                h.post(() -> {
                    if (isFinishing()) return;
                    if (loadFrame != null) loadFrame.setVisibility(View.GONE);
                    buildEpisodeCards(eps, season);
                });
            } catch (Exception e) {
                h.post(() -> {
                    if (isFinishing()) return;
                    if (loadFrame != null) loadFrame.setVisibility(View.GONE);
                    buildEpisodeCards(null, season);
                });
            }
        });
        pool.shutdown();
    }

    private void buildEpisodeCards(List<TmdbApi.EpisodeInfo> eps, int season) {
        LinearLayout cnt = findViewById(R.id.episodeListContainer);
        if (cnt == null) return;
        cnt.removeAllViews();
        cnt.setVisibility(View.VISIBLE);

        if (eps == null || eps.isEmpty()) {
            // Fallback: show E1..E12 placeholder cards
            for (int i = 1; i <= 12; i++) {
                addEpisodeCard(cnt, season, i, "Episodio " + i, "", "", 0);
            }
            return;
        }

        for (TmdbApi.EpisodeInfo ep : eps) {
            addEpisodeCard(cnt, season, ep.number, ep.name, ep.overview, ep.stillUrl, ep.runtime);
        }
    }

    private void addEpisodeCard(LinearLayout parent, int season, int epNum,
                                String name, String overview, String stillUrl, int runtime) {
        // Card container
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setClickable(true);
        card.setFocusable(true);
        card.setBackgroundResource(R.drawable.ep_card_glass);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cardLp);

        // Thumbnail
        ImageView thumb = new ImageView(this);
        int thumbW = dp(120), thumbH = dp(76);
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(thumbW, thumbH);
        thumb.setLayoutParams(thumbLp);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);

        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setColor(0x1AFFFFFF);
        thumbBg.setCornerRadii(new float[]{dp(12), dp(12), 0, 0, 0, 0, dp(12), dp(12)});
        thumb.setBackground(thumbBg);

        String imageUrl = (stillUrl != null && !stillUrl.isEmpty()) ? stillUrl : item.getPosterUrl();
        if (!imageUrl.isEmpty()) {
            Glide.with(this).load(imageUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .centerCrop().into(thumb);
        }
        card.addView(thumb);

        // Right column
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(android.view.Gravity.CENTER_VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, thumbH, 1f));
        col.setPadding(dp(12), dp(10), dp(10), dp(10));

        // Episode badge row
        LinearLayout badgeRow = new LinearLayout(this);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        badgeRow.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // E-number badge
        TextView badge = new TextView(this);
        badge.setText("E" + epNum);
        badge.setTextColor(0xFFFF6B00);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setLetterSpacing(0.06f);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(0x22FF6B00);
        badgeBg.setStroke(dp(1), 0x55FF6B00);
        badgeBg.setCornerRadius(dp(4));
        badge.setBackground(badgeBg);
        int bPadH = dp(6), bPadV = dp(2);
        badge.setPadding(bPadH, bPadV, bPadH, bPadV);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.setMarginEnd(dp(7));
        badge.setLayoutParams(badgeLp);
        badgeRow.addView(badge);

        // Episode title
        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextColor(0xFFFFFFFF);
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setMaxLines(1);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        badgeRow.addView(tvName);
        col.addView(badgeRow);

        // Overview
        if (overview != null && !overview.isEmpty()) {
            TextView tvOv = new TextView(this);
            tvOv.setText(overview);
            tvOv.setTextColor(0x88FFFFFF);
            tvOv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            tvOv.setMaxLines(2);
            tvOv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvOv.setLineSpacing(0, 1.3f);
            LinearLayout.LayoutParams ovLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ovLp.setMargins(0, dp(4), 0, 0);
            tvOv.setLayoutParams(ovLp);
            col.addView(tvOv);
        }

        // Runtime chip (if available)
        if (runtime > 0) {
            TextView tvTime = new TextView(this);
            tvTime.setText(runtime + " min");
            tvTime.setTextColor(0x55FFFFFF);
            tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            timeLp.setMargins(0, dp(3), 0, 0);
            tvTime.setLayoutParams(timeLp);
            col.addView(tvTime);
        }

        card.addView(col);

        // ── Watched indicator (✓) — top-right corner overlay ─────────────────
        final int s = season, e = epNum;
        boolean watched = WatchedManager.isWatched(this, item.getTmdbId(), s, e);

        TextView checkBadge = new TextView(this);
        checkBadge.setText("✓ Vista");
        checkBadge.setTextColor(0xFF4CAF50);
        checkBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        checkBadge.setTypeface(null, Typeface.BOLD);
        checkBadge.setLetterSpacing(0.04f);
        GradientDrawable checkBg = new GradientDrawable();
        checkBg.setColor(0x1A4CAF50);
        checkBg.setStroke(dp(1), 0x554CAF50);
        checkBg.setCornerRadius(dp(4));
        checkBadge.setBackground(checkBg);
        checkBadge.setPadding(dp(5), dp(2), dp(5), dp(2));

        LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        checkLp.setMargins(dp(6), 0, 0, 0);
        checkBadge.setLayoutParams(checkLp);
        checkBadge.setVisibility(watched ? View.VISIBLE : View.GONE);

        // Insert check badge into the badge row (after episode title)
        badgeRow.addView(checkBadge);

        // Dim thumbnail overlay if watched
        thumb.setAlpha(watched ? 0.55f : 1.0f);

        // Click → mark watched + prefetch servers in parallel with animation
        card.setOnClickListener(v -> {
            WatchedManager.markWatched(this, item.getTmdbId(), s, e);
            checkBadge.setVisibility(View.VISIBLE);
            thumb.setAlpha(0.55f);
            fetchAndPlay(item, s, e);
        });

        // Long-press → toggle watched without opening player
        card.setOnLongClickListener(v -> {
            WatchedManager.toggle(this, item.getTmdbId(), s, e);
            boolean nowWatched = WatchedManager.isWatched(this, item.getTmdbId(), s, e);
            checkBadge.setVisibility(nowWatched ? View.VISIBLE : View.GONE);
            thumb.setAlpha(nowWatched ? 0.55f : 1.0f);
            Toast.makeText(this,
                nowWatched ? "Marcado como vista" : "Desmarcado",
                Toast.LENGTH_SHORT).show();
            return true;
        });

        parent.addView(card);
    }

    // ══════════════════════════════════════════════════════════════════════════

    /** Shows the "saga completa" row when this movie belongs to a TMDB collection/franchise. */
    private void loadSaga() {
        View rowSaga = findViewById(R.id.rowSaga);
        if (rowSaga == null) return;
        if (item.getContentType() != ContentItem.TYPE_MOVIE) {
            rowSaga.setVisibility(View.GONE);
            return;
        }

        TextView rowTitle = rowSaga.findViewById(R.id.rowTitle);
        RecyclerView rv    = rowSaga.findViewById(R.id.rowRv);
        View verTodos      = rowSaga.findViewById(R.id.rowVerTodos);
        if (verTodos != null) verTodos.setVisibility(View.GONE);
        if (rv != null) rv.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));

        int currentId = item.getTmdbId();
        Handler h = new Handler(android.os.Looper.getMainLooper());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.execute(() -> {
            TmdbApi.SagaResult saga = TmdbApi.fetchCollectionForMovie(currentId);
            List<ContentItem> parts = new ArrayList<>();
            for (ContentItem c : saga.parts) if (c.getTmdbId() != currentId) parts.add(c);
            final List<ContentItem> finalParts = parts;
            final String title = saga.name != null && !saga.name.isEmpty() ? saga.name.toUpperCase() : "SAGA COMPLETA";
            h.post(() -> {
                if (isFinishing() || finalParts.isEmpty()) {
                    rowSaga.setVisibility(View.GONE);
                    return;
                }
                rowSaga.setVisibility(View.VISIBLE);
                if (rowTitle != null) rowTitle.setText(title);
                if (rv != null) {
                    rv.setAdapter(new ContentRowAdapter(this, finalParts));
                    TvHelper.makeFocusable(rv);
                }
            });
        });
        pool.shutdown();
    }

    private void loadRelated() {
        View rowRelated = findViewById(R.id.rowRelated);
        if (rowRelated == null) return;

        TextView rowTitle = rowRelated.findViewById(R.id.rowTitle);
        RecyclerView rv   = rowRelated.findViewById(R.id.rowRv);
        if (rv != null) rv.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));

        String sectionTitle = item.getContentType() == ContentItem.TYPE_MOVIE
            ? "Películas Relacionadas"
            : item.getContentType() == ContentItem.TYPE_ANIME
            ? "Animes Relacionados"
            : "Series Relacionadas";
        if (rowTitle != null) rowTitle.setText(sectionTitle);

        Handler h = new Handler(android.os.Looper.getMainLooper());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.execute(() -> {
            try {
                // Use TMDB recommendations/similar for this specific title
                List<ContentItem> related = TmdbApi.fetchSimilar(
                        item.getTmdbId(), item.getContentType());
                // Fallback to genre-based list if no results returned
                if (related.isEmpty()) {
                    switch (item.getContentType()) {
                        case ContentItem.TYPE_ANIME:  related = TmdbApi.fetchAnime();  break;
                        case ContentItem.TYPE_SERIES: related = TmdbApi.fetchSeries(); break;
                        case ContentItem.TYPE_DORAMA: related = TmdbApi.fetchDoramas(); break;
                        default:                      related = TmdbApi.fetchMovies();  break;
                    }
                }
                final List<ContentItem> finalRelated = related;
                h.post(() -> {
                    if (!isFinishing() && rv != null) {
                        rv.setAdapter(new ContentRowAdapter(this, finalRelated));
                        TvHelper.makeFocusable(rv);
                    }
                });
            } catch (Exception ignored) {}
        });
        pool.shutdown();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  FETCH + PLAY — fetch servers in parallel with the loading animation
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Detecta si un ContentItem proviene de A7X IPTV.
     * Usa el flag a7xSource que se establece en A7xIptvApi al parsear los ítems.
     */
    private boolean isA7xContent(ContentItem ci) {
        if (ci == null) return false;
        // Flag explícito — forma más confiable
        if (ci.isA7xSource()) return true;
        // Fallback: detectar por URL interna a7x://
        String url = ci.getStreamUrl();
        return url != null && url.startsWith("a7x://");
    }

    /**
     * Lanza MediaActivity directamente con headers A7X para streams A7X TV.
     */
    private void playA7xDirect(ContentItem ci, String streamUrl) {
        ContinueWatchingManager.save(this, ci, 1, 1);
        ContinueWatchingWidget.refresh(this);
        Intent intent = new Intent(this, MediaActivity.class);
        intent.putExtra("url",             streamUrl);
        intent.putExtra("title",           ci.getTitle());
        intent.putExtra("poster_url",      ci.getPosterUrl());
        intent.putExtra("is_m3u8",         streamUrl.contains(".m3u8")
                || streamUrl.contains("m3u") || streamUrl.contains(".ts"));
        intent.putExtra("use_a7x_headers", true);
        intent.putExtra("item",            ci);
        startActivity(intent);
    }

    /**
     * Lanza PlayerActivity INMEDIATAMENTE al tocar REPRODUCIR, sin esperar al API.
     * PlayerActivity se encarga de buscar y probar servidores en segundo plano.
     * Para contenido A7X con URL directa, lanza MediaActivity de inmediato.
     */
    private void fetchAndPlay(ContentItem ci, int season, int episode) {
        // ── A7X content — reproducción directa sin selector de servidor ──────
        if (isA7xContent(ci)) {
            String url = ci.getStreamUrl();
            if (url.startsWith("a7x://series/")) {
                String seriesId = url.replace("a7x://series/", "");
                fetchAndPlayA7xSeries(ci, seriesId);
                return;
            }
            playA7xDirect(ci, url);
            return;
        }

        // ── Feedback visual inmediato en el botón ────────────────────────────
        View btnPlay = findViewById(R.id.btnPlay);
        if (btnPlay != null) {
            btnPlay.setEnabled(false);
            btnPlay.setAlpha(0.6f);
            // Re-habilitar tras 3 s por si el usuario vuelve atrás
            loadingHandler.postDelayed(() -> {
                if (!isFinishing()) {
                    btnPlay.setEnabled(true);
                    btnPlay.setAlpha(1f);
                }
            }, 3000);
        }

        // ── Lanzar PlayerActivity de inmediato — sin esperar al API ──────────
        ContinueWatchingManager.save(this, ci, season, episode);
        ContinueWatchingWidget.refresh(this);
        Intent intent = new Intent(this, PlayerActivity.class);
        // URL de respaldo inmediata (puede ser la embedUrl base del ítem)
        String fallbackUrl = (ci.getStreamUrl() != null && !ci.getStreamUrl().isEmpty())
                ? ci.getStreamUrl() : "";
        intent.putExtra("url",                fallbackUrl);
        intent.putExtra("title",              ci.getTitle());
        intent.putExtra("item",               ci);
        // Pasar temporada/episodio para que PlayerActivity busque los servidores reales
        intent.putExtra("auto_fetch_season",  season);
        intent.putExtra("auto_fetch_episode", episode);
        startActivity(intent);
    }

    /**
     * Carga el detalle de una serie A7X y muestra el selector de episodios.
     */
    private void fetchAndPlayA7xSeries(ContentItem ci, String seriesId) {
        showLoadingOverlay();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.execute(() -> {
            try {
                org.json.JSONObject detail = A7xIptvApi.loadSeriesDetail(this, seriesId);
                org.json.JSONArray seasons = detail.optJSONArray("seasons");

                // Extraer primer episodio disponible como default
                String firstEpUrl = "";
                if (seasons != null && seasons.length() > 0) {
                    org.json.JSONObject s1 = seasons.getJSONObject(0);
                    org.json.JSONArray eps = s1.optJSONArray("episodes");
                    if (eps != null && eps.length() > 0) {
                        firstEpUrl = eps.getJSONObject(0).optString("stream_url", "");
                    }
                }

                final String epUrl = firstEpUrl;
                loadingHandler.post(() -> {
                    if (isFinishing()) return;
                    hideLoadingOverlay(() -> {
                        if (!epUrl.isEmpty()) {
                            playA7xDirect(ci, epUrl);
                        } else {
                            // Mostrar en WebView como fallback
                            StreamingApi.ServerData fallback = new StreamingApi.ServerData();
                            fallback.latino.add(new StreamingApi.Server(
                                "A7X TV", ci.getStreamUrl(), "embed"));
                            ServerSelectDialog.showPreloaded(this, ci, fallback, 1, 1);
                        }
                    });
                });
            } catch (Exception e) {
                loadingHandler.post(() -> {
                    if (isFinishing()) return;
                    hideLoadingOverlay(() ->
                        android.widget.Toast.makeText(this,
                            "Error al cargar la serie, intenta de nuevo",
                            android.widget.Toast.LENGTH_SHORT).show());
                });
            }
        });
        exec.shutdown();
    }

    private void showLoadingOverlay() {
        // overlay "CARGANDO SERVIDORES" deshabilitado
    }

    private void hideLoadingOverlay(Runnable onHidden) {
        onHidden.run();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  REPRODUCIR — continuous pulse + shimmer sweep
    // ══════════════════════════════════════════════════════════════════════════

    private void startReproducirAnimations(View btn) {
        if (btn == null) return;

        // Subtle scale pulse
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(btn, "scaleX", 1f, 1.025f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(btn, "scaleY", 1f, 1.025f, 1f);
        scaleX.setDuration(1800);
        scaleY.setDuration(1800);
        scaleX.setRepeatCount(ObjectAnimator.INFINITE);
        scaleY.setRepeatCount(ObjectAnimator.INFINITE);
        scaleX.setInterpolator(new AccelerateDecelerateInterpolator());
        scaleY.setInterpolator(new AccelerateDecelerateInterpolator());
        scaleX.start();
        scaleY.start();

        // Shimmer sweep across button
        View shimmer = btn.findViewById(R.id.btnPlayShimmer);
        if (shimmer != null && btn instanceof FrameLayout) {
            btn.post(() -> {
                float btnW = btn.getWidth();
                shimmer.setTranslationX(-80f);
                ObjectAnimator sweep = ObjectAnimator.ofFloat(shimmer, "translationX", -80f, btnW + 80f);
                sweep.setDuration(1600);
                sweep.setStartDelay(600);
                sweep.setRepeatCount(ObjectAnimator.INFINITE);
                sweep.setRepeatMode(ObjectAnimator.RESTART);
                sweep.setInterpolator(new LinearInterpolator());

                ObjectAnimator shimmerAlpha = ObjectAnimator.ofFloat(shimmer, "alpha", 0f, 0.5f, 0f);
                shimmerAlpha.setDuration(1600);
                shimmerAlpha.setStartDelay(600);
                shimmerAlpha.setRepeatCount(ObjectAnimator.INFINITE);
                shimmerAlpha.setRepeatMode(ObjectAnimator.RESTART);
                shimmerAlpha.setInterpolator(new LinearInterpolator());

                sweep.start();
                shimmerAlpha.start();
            });
        }
    }

    private void animateDot(View parent, int dotId, long delay) {
        View dot = parent.findViewById(dotId);
        if (dot == null) return;
        ObjectAnimator anim = ObjectAnimator.ofFloat(dot, "alpha", 0.15f, 1f, 0.15f);
        anim.setDuration(750);
        anim.setStartDelay(delay);
        anim.setRepeatCount(ObjectAnimator.INFINITE);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        anim.start();
        dotAnimators.add(anim);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TRAILER — fetch key → backdrop autoplay → modal
    // ══════════════════════════════════════════════════════════════════════════

    private void loadTrailer() {
        if (item.getTmdbId() == 0) return;
        boolean isMovie = item.getContentType() == ContentItem.TYPE_MOVIE;
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.execute(() -> {
            String key = TmdbApi.fetchYouTubeTrailerKey(item.getTmdbId(), isMovie);
            loadingHandler.post(() -> {
                if (isFinishing()) return;
                trailerKey = key;
                if (!key.isEmpty()) {
                    // Show "Ver Tráiler" button
                    View btn = findViewById(R.id.btnTrailer);
                    if (btn != null) {
                        btn.setVisibility(View.VISIBLE);
                        btn.setAlpha(0f);
                        btn.animate().alpha(1f).setDuration(500).start();
                    }
                }
            });
        });
        exec.shutdown();
    }

    /**
     * Opens the trailer in the YouTube app. If the app is not installed,
     * falls back to the system browser. This avoids embed errors (150/152/153)
     * that YouTube enforces on restricted videos in WebViews.
     */
    private void showTrailerModal(String key) {
        // Try YouTube app first (deep link)
        Intent ytApp = new Intent(Intent.ACTION_VIEW,
            Uri.parse("vnd.youtube:" + key));
        ytApp.putExtra("force_fullscreen", true);

        // Fallback: open watch URL in browser/YouTube app
        Intent ytWeb = new Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/watch?v=" + key));

        try {
            startActivity(ytApp);
        } catch (android.content.ActivityNotFoundException e) {
            startActivity(ytWeb);
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (TvHelper.handleGlobalKeyEvent(this, event)) return true;
        return super.dispatchKeyEvent(event);
    }
}
