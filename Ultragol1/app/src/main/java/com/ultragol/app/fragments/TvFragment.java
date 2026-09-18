package com.ultragol.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.video.VideoSize;

import com.ultragol.app.MediaActivity;
import com.ultragol.app.R;
import com.ultragol.app.SearchActivity;
import com.ultragol.app.TvFavoritesManager;
import com.ultragol.app.adapters.TvAdapter;
import com.ultragol.app.models.TvChannel;
import com.ultragol.app.network.A7xConstants;
import com.ultragol.app.network.A7xIptvApi;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV en vivo — canal reproduciéndose incrustado arriba (ExoPlayer, sin salir
 * de la pantalla) + tabs Categoría/Favoritos + chips de categoría + lista de
 * canales numerada. Tocar una fila cambia lo que suena arriba; el botón de
 * pantalla completa abre el reproductor inmersivo (MediaActivity) para ver
 * el canal actual a pantalla completa.
 */
public class TvFragment extends Fragment {

    // ── Categorías del menú ───────────────────────────────────────────────────
    private static final List<String> CATEGORIES = Arrays.asList(
            TvChannel.CAT_TODOS,
            TvChannel.CAT_NOTICIAS,
            TvChannel.CAT_DEPORTES,
            TvChannel.CAT_ENTRETENIMIENTO,
            TvChannel.CAT_MUSICA,
            TvChannel.CAT_DOCUMENTALES,
            TvChannel.CAT_INFANTIL,
            TvChannel.CAT_NEGOCIOS,
            TvChannel.CAT_CIENCIA
    );

    /** Canales de respaldo siempre disponibles — accesibles desde otras clases. */
    public static TvChannel[] getFallbackChannels() { return FALLBACK; }

    /**
     * Caché global de canales cargados — accesible desde SearchActivity.
     * Se puebla progresivamente conforme el fragment carga fuentes M3U.
     */
    private static final List<TvChannel> CHANNEL_CACHE = new ArrayList<>();
    public static List<TvChannel> getCachedChannels() {
        synchronized (CHANNEL_CACHE) { return new ArrayList<>(CHANNEL_CACHE); }
    }

    // ── Canales de respaldo (siempre disponibles, streams conocidos) ──────────
    private static final TvChannel[] FALLBACK = {
        // Noticias internacionales
        ch("DW Español",      "https://dwamdstream104.akamaized.net/hls/live/2015530/dwstream104/index.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/7/75/DW_Espa%C3%B1ol.svg/200px-DW_Espa%C3%B1ol.svg.png",
           TvChannel.CAT_NOTICIAS, "DE"),
        ch("France 24 ES",    "https://f24hls-i.akamaihd.net/hls/live/976956/F24_ES_LO_HLS/master.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8b/France_24_logo.svg/200px-France_24_logo.svg.png",
           TvChannel.CAT_NOTICIAS, "FR"),
        ch("France 24 EN",    "https://f24hls-i.akamaihd.net/hls/live/976932/F24_EN_LO_HLS/master.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8b/France_24_logo.svg/200px-France_24_logo.svg.png",
           TvChannel.CAT_NOTICIAS, "FR"),
        ch("Al Jazeera EN",   "https://live-hls-web-aje.getaj.net/AJE/01.m3u8",
           "https://upload.wikimedia.org/wikipedia/en/thumb/f/f2/Aljazeera_eng.svg/200px-Aljazeera_eng.svg.png",
           TvChannel.CAT_NOTICIAS, "QA"),
        ch("DW English",      "https://dwamdstream106.akamaized.net/hls/live/2015530/dwstream106/index.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/d/d0/DW_logo.svg/200px-DW_logo.svg.png",
           TvChannel.CAT_NOTICIAS, "DE"),
        ch("CGTN Español",    "https://news.cgtn.com/resource/live/spanish/8VXBVBVh.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/0/04/CGTN_logo.svg/200px-CGTN_logo.svg.png",
           TvChannel.CAT_NOTICIAS, "CN"),
        ch("CGTN English",    "https://news.cgtn.com/resource/live/english/8VXBVBVh.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/0/04/CGTN_logo.svg/200px-CGTN_logo.svg.png",
           TvChannel.CAT_NOTICIAS, "CN"),
        ch("Euronews EN",     "https://rakuten-euronews-1-gb.samsung.wurl.tv/manifest/playlist.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/9/9e/Euronews_2022_logo.svg/200px-Euronews_2022_logo.svg.png",
           TvChannel.CAT_NOTICIAS, "FR"),
        // Ciencia
        ch("NASA TV",         "https://nasa-i.akamaihd.net/hls/live/253565/NASA-NTV1-HLS/master.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e5/NASA_logo.svg/200px-NASA_logo.svg.png",
           TvChannel.CAT_CIENCIA, "US"),
        ch("NASA TV UHD",     "https://nasa-i.akamaihd.net/hls/live/253566/NASA-NTV3-HLS/master.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e5/NASA_logo.svg/200px-NASA_logo.svg.png",
           TvChannel.CAT_CIENCIA, "US"),
        // Entretenimiento / Música
        ch("Rakuten Action",  "https://rakuten-rakutenactionmovieschannel-1-us.samsung.wurl.tv/manifest/playlist.m3u8",
           "",
           TvChannel.CAT_ENTRETENIMIENTO, "US"),
        ch("Rakuten Drama",   "https://rakuten-rakutendramas-1-us.samsung.wurl.tv/manifest/playlist.m3u8",
           "",
           TvChannel.CAT_ENTRETENIMIENTO, "US"),

        // ── México ─────────────────────────────────────────────────────────────
        ch("Once TV México",  "https://oncetv-hls.akamaized.net/hls/live/577042/OnceTVdirecto/master.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/8/81/Once_TV_M%C3%A9xico_logo.svg/200px-Once_TV_M%C3%A9xico_logo.svg.png",
           TvChannel.CAT_ENTRETENIMIENTO, "MX"),
        ch("Canal 22",        "https://d1k5j68ob7clqb.cloudfront.net/soft754312/out/v1/7a5d0b27aedc46028a44530924e0c2f6/index.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/9/96/Canal_22_Mexico_logo.svg/200px-Canal_22_Mexico_logo.svg.png",
           TvChannel.CAT_ENTRETENIMIENTO, "MX"),
        ch("Imagen TV",       "https://d1gh9g9es3s3n7.cloudfront.net/hls/live/2003011/imagentv/playlist.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e4/Imagen_TV_logo.svg/200px-Imagen_TV_logo.svg.png",
           TvChannel.CAT_NOTICIAS, "MX"),
        ch("Milenio TV",      "https://d2oamh5dj6fb37.cloudfront.net/out/v1/7f57f5f5c0d54e50b9a5571e3d79d7c0/index.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/2/2c/Milenio_television_2020.svg/200px-Milenio_television_2020.svg.png",
           TvChannel.CAT_NOTICIAS, "MX"),
        ch("ADN 40",          "https://mdstrm.com/live-stream/58c5237793490d0e008b456b.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/7/79/ADN40_logo.svg/200px-ADN40_logo.svg.png",
           TvChannel.CAT_NOTICIAS, "MX"),
        ch("Excélsior TV",    "https://mdstrm.com/live-stream/5a7f9802d5c6b84c008b4572.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e5/Excelsiortv_logo.svg/200px-Excelsiortv_logo.svg.png",
           TvChannel.CAT_NOTICIAS, "MX"),
        ch("Multimedios TV",  "https://mmdlive.lldns.net/liveedge/f84bf4e857bd47ae9f90d4f9573a6acb/playlist.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/7/74/Multimedios_Television_2022.svg/200px-Multimedios_Television_2022.svg.png",
           TvChannel.CAT_ENTRETENIMIENTO, "MX"),
        ch("Efekto TV",       "https://streaming.efektotv.com/output/hls/stream.m3u8",
           "",
           TvChannel.CAT_ENTRETENIMIENTO, "MX"),
        ch("TV UNAM",         "https://tvunam.unam.mx/hls/live/main.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/c/ca/Escudo-UNAM-escalable.svg/200px-Escudo-UNAM-escalable.svg.png",
           TvChannel.CAT_CIENCIA, "MX"),
        ch("Foro TV",         "https://telecanal.multimedios.com:1935/live/noticias24/playlist.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/4/42/ForoTV_logo.svg/200px-ForoTV_logo.svg.png",
           TvChannel.CAT_NOTICIAS, "MX"),

        // ── Televisa / TelevisaUnivision ────────────────────────────────────────
        ch("Las Estrellas",   "https://mdustvmxmdu4-lh.akamaihd.net/i/Estrellas_1@459383/master.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/0/09/Las_Estrellas_logo.svg/200px-Las_Estrellas_logo.svg.png",
           TvChannel.CAT_ENTRETENIMIENTO, "MX"),
        ch("Canal 5 MX",      "https://mdustvmxmdu5-lh.akamaihd.net/i/CanalCinco_1@459339/master.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6f/Canal_5_Mexico_2016.svg/200px-Canal_5_Mexico_2016.svg.png",
           TvChannel.CAT_ENTRETENIMIENTO, "MX"),
        ch("Gala TV (Nu9ve)", "https://mdustvmxmdu9-lh.akamaihd.net/i/CanalNueve_1@459392/master.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/d/d4/Gala_TV_Logo.svg/200px-Gala_TV_Logo.svg.png",
           TvChannel.CAT_ENTRETENIMIENTO, "MX"),
        ch("Telehit",         "https://mdust4vmxmdu-lh.akamaihd.net/i/Telehit_1@459388/master.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a3/Telehit_logo.svg/200px-Telehit_logo.svg.png",
           TvChannel.CAT_MUSICA, "MX"),
        ch("Bandamax",        "https://mdustbmax-lh.akamaihd.net/i/Bandamax_1@459378/master.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a5/Bandamax_logo_2019.svg/200px-Bandamax_logo_2019.svg.png",
           TvChannel.CAT_MUSICA, "MX"),
        ch("De Película",     "https://mdust4vmxmdu-lh.akamaihd.net/i/DePelicula_1@459381/master.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/5/58/De_pelicula_logo.svg/200px-De_pelicula_logo.svg.png",
           TvChannel.CAT_ENTRETENIMIENTO, "MX"),
        ch("Televisa Deportes","https://mdust4vmxmdu-lh.akamaihd.net/i/TelevisaDeportes_1@459390/master.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/b/bb/TUDN_logo.svg/200px-TUDN_logo.svg.png",
           TvChannel.CAT_DEPORTES, "MX"),

        // ── TV Azteca ──────────────────────────────────────────────────────────
        ch("Azteca Uno",      "https://mdstrm.com/live-stream/58c5237793490d0e008b456b.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/4/47/Azteca_Uno_logo_%282017%29.svg/200px-Azteca_Uno_logo_%282017%29.svg.png",
           TvChannel.CAT_ENTRETENIMIENTO, "MX"),
        ch("Azteca 7",        "https://mdstrm.com/live-stream/azteca7live.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/6/68/Azteca_7_2017.svg/200px-Azteca_7_2017.svg.png",
           TvChannel.CAT_ENTRETENIMIENTO, "MX"),
        ch("Azteca Noticias", "https://mdstrm.com/live-stream/5a7f9802d5c6b84c008b4572.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/5/56/Azteca_Noticias_2019.svg/200px-Azteca_Noticias_2019.svg.png",
           TvChannel.CAT_NOTICIAS, "MX"),
        ch("Azteca Deportes", "https://mdstrm.com/live-stream/aztecadeportes.m3u8",
           "https://upload.wikimedia.org/wikipedia/commons/thumb/8/83/Azteca_Deportes_2022.svg/200px-Azteca_Deportes_2022.svg.png",
           TvChannel.CAT_DEPORTES, "MX"),
    };

    // ── Fuentes IPTV remotas (iptv-org + otros agregadores) ──────────────────
    private static final String[] M3U_SOURCES = {
        "https://iptv-org.github.io/iptv/categories/news.m3u",
        "https://iptv-org.github.io/iptv/categories/sports.m3u",
        "https://iptv-org.github.io/iptv/categories/entertainment.m3u",
        "https://iptv-org.github.io/iptv/categories/music.m3u",
        "https://iptv-org.github.io/iptv/categories/documentary.m3u",
        "https://iptv-org.github.io/iptv/categories/kids.m3u",
        "https://iptv-org.github.io/iptv/categories/general.m3u",
        "https://iptv-org.github.io/iptv/categories/science.m3u",
        "https://iptv-org.github.io/iptv/categories/business.m3u",
        "https://iptv-org.github.io/iptv/categories/travel.m3u",
        "https://iptv-org.github.io/iptv/categories/cooking.m3u",
        "https://iptv-org.github.io/iptv/categories/religious.m3u",
        "https://iptv-org.github.io/iptv/languages/spa.m3u",
        "https://iptv-org.github.io/iptv/languages/por.m3u",
        "https://iptv-org.github.io/iptv/countries/mx.m3u",
        "https://iptv-org.github.io/iptv/countries/ar.m3u",
        "https://iptv-org.github.io/iptv/countries/co.m3u",
        "https://iptv-org.github.io/iptv/countries/es.m3u",
        "https://iptv-org.github.io/iptv/countries/pe.m3u",
        "https://iptv-org.github.io/iptv/countries/ve.m3u",
        "https://iptv-org.github.io/iptv/countries/cl.m3u",
        "https://iptv-org.github.io/iptv/countries/ec.m3u",
        "https://iptv-org.github.io/iptv/countries/bo.m3u",
        "https://iptv-org.github.io/iptv/countries/py.m3u",
        "https://iptv-org.github.io/iptv/countries/uy.m3u",
        "https://iptv-org.github.io/iptv/countries/cr.m3u",
        "https://iptv-org.github.io/iptv/countries/do.m3u",
        "https://iptv-org.github.io/iptv/countries/gt.m3u",
        "https://iptv-org.github.io/iptv/countries/us.m3u",
        "https://iptv-org.github.io/iptv/countries/br.m3u",
        "https://iptv-org.github.io/iptv/countries/fr.m3u",
        "https://iptv-org.github.io/iptv/countries/de.m3u",
        "https://iptv-org.github.io/iptv/countries/it.m3u",
        "https://iptv-org.github.io/iptv/countries/gb.m3u",
        "https://iptv-org.github.io/iptv/countries/pt.m3u",
    };

    private static final int MAX_PER_SOURCE = 50;

    private static final int COLOR_ACTIVE   = 0xFFFFFFFF;
    private static final int COLOR_INACTIVE = 0xFF8A8296;
    private static final int COLOR_PURPLE   = 0xFF8B5CF6;
    private static final int COLOR_TRANSPARENT = 0x00000000;

    // ── Estado ────────────────────────────────────────────────────────────────
    private TvAdapter adapter;
    private String selectedCategory = TvChannel.CAT_TODOS;
    private boolean favoritesTab = false;
    private final List<TvChannel> allChannels = new ArrayList<>();
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Reproductor incrustado ───────────────────────────────────────────────
    private ExoPlayer player;
    private PlayerView playerView;
    private ProgressBar playerLoading;
    private TextView qualityBadge;
    private TvChannel currentChannel;
    private boolean muted = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_tv, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        executor = Executors.newFixedThreadPool(8);
        synchronized (allChannels) { allChannels.clear(); }

        View btnSearch = view.findViewById(R.id.btnTvSearch);
        if (btnSearch != null) btnSearch.setOnClickListener(v ->
            startActivity(new Intent(requireContext(), SearchActivity.class)));

        RecyclerView rv = view.findViewById(R.id.rvTvChannels);
        adapter = new TvAdapter(requireContext(), this::bindHeader);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);
        adapter.setOnChannelClickListener(this::playChannel);

        // Show fallback channels immediately
        synchronized (allChannels) {
            for (TvChannel ch : FALLBACK) allChannels.add(ch);
        }
        synchronized (CHANNEL_CACHE) {
            if (CHANNEL_CACHE.isEmpty()) {
                for (TvChannel ch : FALLBACK) CHANNEL_CACHE.add(ch);
            }
        }
        applyFilter();

        loadA7xChannels();
        loadRemoteChannels();
    }

    @Override public void onPause() {
        super.onPause();
        if (player != null) player.setPlayWhenReady(false);
    }

    @Override public void onResume() {
        super.onResume();
        if (player != null) player.setPlayWhenReady(true);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        playerView = null;
        playerLoading = null;
        qualityBadge = null;
    }

    // ── Carga canales A7X ─────────────────────────────────────────────────────

    private void loadA7xChannels() {
        if (executor == null) return;
        executor.execute(() -> {
            try {
                List<TvChannel> a7xChannels =
                        A7xIptvApi.loadLiveChannels(requireContext());
                if (a7xChannels.isEmpty() || !isAdded()) return;

                List<TvChannel> newChannels = new ArrayList<>();
                synchronized (allChannels) {
                    LinkedHashSet<String> existingUrls = new LinkedHashSet<>();
                    for (TvChannel c : allChannels) existingUrls.add(c.url);
                    for (TvChannel c : a7xChannels) {
                        if (!existingUrls.contains(c.url)) {
                            existingUrls.add(c.url);
                            allChannels.add(0, c);
                            newChannels.add(c);
                        }
                    }
                }
                if (!newChannels.isEmpty()) {
                    synchronized (CHANNEL_CACHE) {
                        LinkedHashSet<String> cacheUrls = new LinkedHashSet<>();
                        for (TvChannel c : CHANNEL_CACHE) cacheUrls.add(c.url);
                        for (TvChannel c : newChannels) {
                            if (!cacheUrls.contains(c.url)) CHANNEL_CACHE.add(0, c);
                        }
                    }
                    mainHandler.post(this::applyFilter);
                }
            } catch (Exception ignored) {}
        });
    }

    // ── Reproducción incrustada ──────────────────────────────────────────────

    /** Tap en una fila: carga ese canal en el reproductor de arriba (no navega). */
    private void playChannel(TvChannel ch) {
        currentChannel = ch;
        if (qualityBadge != null) qualityBadge.setText("LIVE");
        adapter.setCurrentUrl(ch.url);
        loadIntoPlayer(ch);
    }

    /** Abre el canal actual en el reproductor de pantalla completa existente. */
    private void openFullscreen(TvChannel ch) {
        Intent intent = new Intent(requireContext(), MediaActivity.class);
        intent.putExtra("url",             ch.url);
        intent.putExtra("title",           ch.name);
        intent.putExtra("is_m3u8",         ch.url.contains(".m3u8") || ch.url.contains("m3u"));
        intent.putExtra("referer",         "");
        intent.putExtra("use_a7x_headers", isA7xStream(ch.url));
        startActivity(intent);
    }

    private boolean isA7xStream(String url) {
        return !url.contains("iptv-org.github.io")
                && !url.contains("akamaized.net")
                && !url.contains("akamaihd.net")
                && !url.contains("cloudfront.net")
                && !url.contains("wurl.tv")
                && !url.contains("samsung.wurl");
    }

    private void ensurePlayerReady() {
        if (player != null) return;
        player = new ExoPlayer.Builder(requireContext()).build();
        player.setVolume(muted ? 0f : 1f);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (playerLoading != null) {
                    playerLoading.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                if (qualityBadge != null && videoSize.height > 0) {
                    qualityBadge.setText(videoSize.height + "p");
                }
            }
            @Override public void onPlayerError(@NonNull PlaybackException error) {
                if (playerLoading != null) playerLoading.setVisibility(View.GONE);
            }
        });
        if (playerView != null) playerView.setPlayer(player);
        if (currentChannel != null) loadIntoPlayer(currentChannel);
    }

    private void loadIntoPlayer(TvChannel ch) {
        if (player == null) return;
        boolean useA7xHeaders = isA7xStream(ch.url);
        String ua = useA7xHeaders
                ? A7xConstants.SECURE_USER_AGENT
                : "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36";

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", ua);
        if (useA7xHeaders) headers.put("X-A7X-Client", A7xConstants.SECURE_CLIENT_ID);

        DataSource.Factory dsFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000);

        MediaSource src = new HlsMediaSource.Factory(dsFactory)
                .createMediaSource(MediaItem.fromUri(ch.url));
        player.setMediaSource(src);
        player.setPlayWhenReady(true);
        player.prepare();
    }

    // ── Header: reproductor + tabs + chips ───────────────────────────────────

    private void bindHeader(View header) {
        PlayerView pv = header.findViewById(R.id.tvPlayerView);
        if (pv != null) playerView = pv;
        playerLoading = header.findViewById(R.id.tvPlayerLoading);
        qualityBadge  = header.findViewById(R.id.tvQualityBadge);
        ensurePlayerReady();
        if (playerView != null && playerView.getPlayer() != player) playerView.setPlayer(player);

        TextView nowPlaying = header.findViewById(R.id.tvNowPlayingName);
        if (nowPlaying != null) nowPlaying.setText(currentChannel != null ? currentChannel.name : "");

        View share = header.findViewById(R.id.btnTvShare);
        if (share != null) share.setOnClickListener(v -> {
            if (currentChannel == null) return;
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, "Estoy viendo " + currentChannel.name + " en vivo 📺");
            startActivity(Intent.createChooser(shareIntent, "Compartir canal"));
        });

        View help = header.findViewById(R.id.btnTvHelp);
        if (help != null) help.setOnClickListener(v -> Toast.makeText(requireContext(),
            "Si el canal no carga, prueba con otro de la lista de abajo.", Toast.LENGTH_SHORT).show());

        TextView fav = header.findViewById(R.id.btnTvFav);
        if (fav != null) {
            boolean isFav = currentChannel != null
                && TvFavoritesManager.isFavorite(requireContext(), currentChannel.url);
            fav.setText(isFav ? "♥" : "♡");
            fav.setTextColor(isFav ? 0xFFFF5C8A : COLOR_ACTIVE);
            fav.setOnClickListener(v -> {
                if (currentChannel == null) return;
                TvFavoritesManager.toggle(requireContext(), currentChannel.url);
                adapter.notifyItemChanged(0);
                if (favoritesTab) applyFilter();
            });
        }

        TextView mute = header.findViewById(R.id.btnTvMute);
        if (mute != null) {
            mute.setText(muted ? "🔇" : "🔊");
            mute.setOnClickListener(v -> {
                muted = !muted;
                if (player != null) player.setVolume(muted ? 0f : 1f);
                mute.setText(muted ? "🔇" : "🔊");
            });
        }

        ImageView fullscreen = header.findViewById(R.id.btnTvFullscreen);
        if (fullscreen != null) fullscreen.setOnClickListener(v -> {
            if (currentChannel != null) openFullscreen(currentChannel);
        });

        bindTabs(header);
        buildChipsOnce(header);
        updateChipsSelection(header);
    }

    private void bindTabs(View header) {
        LinearLayout catWrap = header.findViewById(R.id.tabCategoriaWrap);
        LinearLayout favWrap = header.findViewById(R.id.tabFavoritosWrap);
        TextView tabCategoria = header.findViewById(R.id.tabCategoria);
        TextView tabFavoritos = header.findViewById(R.id.tabFavoritos);
        View indCategoria = header.findViewById(R.id.indCategoria);
        View indFavoritos = header.findViewById(R.id.indFavoritos);

        if (tabCategoria != null) tabCategoria.setTextColor(favoritesTab ? COLOR_INACTIVE : COLOR_ACTIVE);
        if (tabFavoritos != null) tabFavoritos.setTextColor(favoritesTab ? COLOR_ACTIVE : COLOR_INACTIVE);
        if (indCategoria != null) indCategoria.setBackgroundColor(favoritesTab ? COLOR_TRANSPARENT : COLOR_PURPLE);
        if (indFavoritos != null) indFavoritos.setBackgroundColor(favoritesTab ? COLOR_PURPLE : COLOR_TRANSPARENT);

        if (catWrap != null) catWrap.setOnClickListener(v -> { favoritesTab = false; applyFilter(); });
        if (favWrap != null) favWrap.setOnClickListener(v -> { favoritesTab = true; applyFilter(); });
    }

    private void buildChipsOnce(View header) {
        LinearLayout container = header.findViewById(R.id.chipContainer);
        if (container == null || container.getChildCount() == CATEGORIES.size()) return;
        container.removeAllViews();
        LayoutInflater li = LayoutInflater.from(requireContext());
        for (String cat : CATEGORIES) {
            View chip = li.inflate(R.layout.item_tv_chip, container, false);
            TextView tv = chip.findViewById(R.id.tvChip);
            if (tv != null) tv.setText(cat);
            chip.setOnClickListener(v -> {
                selectedCategory = cat;
                favoritesTab = false;
                applyFilter();
            });
            container.addView(chip);
        }
    }

    private void updateChipsSelection(View header) {
        LinearLayout container = header.findViewById(R.id.chipContainer);
        if (container == null) return;
        for (int i = 0; i < container.getChildCount() && i < CATEGORIES.size(); i++) {
            View chip = container.getChildAt(i);
            TextView tv = chip.findViewById(R.id.tvChip);
            if (tv == null) continue;
            boolean active = !favoritesTab && CATEGORIES.get(i).equals(selectedCategory);
            tv.setBackgroundResource(active ? R.drawable.tv_chip_active_purple : R.drawable.tv_chip_inactive_purple);
            tv.setTextColor(active ? 0xFFFFFFFF : 0xCCFFFFFF);
        }
    }

    // ── Filtrado ──────────────────────────────────────────────────────────────

    private void applyFilter() {
        List<TvChannel> filtered = new ArrayList<>();
        synchronized (allChannels) {
            if (favoritesTab) {
                Set<String> favs = TvFavoritesManager.getAll(requireContext());
                for (TvChannel ch : allChannels) {
                    if (ch.isAlive && favs.contains(ch.url)) filtered.add(ch);
                }
            } else {
                for (TvChannel ch : allChannels) {
                    if (ch.isAlive &&
                        (selectedCategory.equals(TvChannel.CAT_TODOS) ||
                         ch.category.equals(selectedCategory))) {
                        filtered.add(ch);
                    }
                }
            }
        }
        adapter.setChannels(filtered);
        if (currentChannel == null && !filtered.isEmpty()) {
            playChannel(filtered.get(0));
        }
        adapter.notifyItemChanged(0);
    }

    // ── Carga remota de M3U ───────────────────────────────────────────────────

    private void loadRemoteChannels() {
        for (String src : M3U_SOURCES) {
            executor.execute(() -> {
                List<TvChannel> parsed = fetchAndParse(src, MAX_PER_SOURCE);
                if (parsed.isEmpty()) return;

                List<TvChannel> verified = new ArrayList<>();
                List<Runnable> checks = new ArrayList<>();
                Object lock = new Object();

                for (TvChannel ch : parsed) {
                    checks.add(() -> {
                        if (checkStream(ch.url)) {
                            synchronized (lock) { verified.add(ch); }
                        }
                    });
                }

                List<Thread> threads = new ArrayList<>();
                for (Runnable r : checks) {
                    Thread t = new Thread(r);
                    t.start();
                    threads.add(t);
                }
                for (Thread t : threads) {
                    try { t.join(6000); } catch (InterruptedException ignored) {}
                }

                if (!verified.isEmpty() && isAdded()) {
                    List<TvChannel> newChannels = new ArrayList<>();
                    synchronized (allChannels) {
                        LinkedHashSet<String> existingUrls = new LinkedHashSet<>();
                        for (TvChannel c : allChannels) existingUrls.add(c.url);
                        for (TvChannel c : verified) {
                            if (!existingUrls.contains(c.url)) {
                                existingUrls.add(c.url);
                                allChannels.add(c);
                                newChannels.add(c);
                            }
                        }
                    }
                    if (!newChannels.isEmpty()) {
                        synchronized (CHANNEL_CACHE) {
                            LinkedHashSet<String> cacheUrls = new LinkedHashSet<>();
                            for (TvChannel c : CHANNEL_CACHE) cacheUrls.add(c.url);
                            for (TvChannel c : newChannels) {
                                if (!cacheUrls.contains(c.url)) CHANNEL_CACHE.add(c);
                            }
                        }
                        mainHandler.post(this::applyFilter);
                    }
                }
            });
        }
    }

    // ── Parser de M3U ─────────────────────────────────────────────────────────

    private List<TvChannel> fetchAndParse(String src, int limit) {
        List<TvChannel> result = new ArrayList<>();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(src).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Android; Mobile) AppleWebKit/537.36");
            conn.connect();
            if (conn.getResponseCode() != 200) { conn.disconnect(); return result; }

            BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"), 16384);

            String name = null, logo = null, group = null, country = null;
            String line;
            int count = 0;

            while ((line = br.readLine()) != null && count < limit) {
                line = line.trim();
                if (line.startsWith("#EXTINF")) {
                    name    = extractAttr(line, "tvg-name",  extractAfterComma(line));
                    logo    = extractAttr(line, "tvg-logo",  "");
                    group   = extractAttr(line, "group-title","");
                    country = extractAttr(line, "tvg-country","");
                    if (country.isEmpty()) country = extractAttr(line, "tvg-id", "").replaceAll("[^A-Z]", "");
                } else if (!line.startsWith("#") && !line.isEmpty() && name != null) {
                    if (line.startsWith("http")) {
                        String cat = TvChannel.mapGroup(group);
                        TvChannel ch = new TvChannel(name, line, logo, cat,
                            country.length() >= 2 ? country.substring(0,2).toUpperCase() : "");
                        result.add(ch);
                        count++;
                    }
                    name = null; logo = null; group = null; country = null;
                }
            }
            br.close();
            conn.disconnect();
        } catch (Exception ignored) {}
        return result;
    }

    private String extractAttr(String line, String attr, String def) {
        String search = attr + "=\"";
        int start = line.indexOf(search);
        if (start < 0) return def;
        start += search.length();
        int end = line.indexOf('"', start);
        return end > start ? line.substring(start, end) : def;
    }

    private String extractAfterComma(String line) {
        int idx = line.lastIndexOf(',');
        return idx >= 0 && idx < line.length() - 1 ? line.substring(idx + 1).trim() : "";
    }

    // ── Verificación de stream ─────────────────────────────────────────────────

    private boolean checkStream(String streamUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(streamUrl).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Android; Mobile) AppleWebKit/537.36");
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            int code = conn.getResponseCode();
            if (code == 200) {
                byte[] buf = new byte[512];
                int read = conn.getInputStream().read(buf);
                conn.disconnect();
                if (read > 0) {
                    String header = new String(buf, 0, read, "UTF-8");
                    return header.contains("#EXT") || header.contains("http");
                }
                return false;
            }
            conn.disconnect();
            return code == 302 || code == 301;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static TvChannel ch(String n, String u, String l, String c, String co) {
        return new TvChannel(n, u, l, c, co);
    }
}
