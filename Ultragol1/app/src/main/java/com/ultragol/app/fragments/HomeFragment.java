package com.ultragol.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.*;
import androidx.viewpager2.widget.ViewPager2;
import com.ultragol.app.*;
import com.ultragol.app.adapters.*;
import com.ultragol.app.fragments.TvFragment;
import com.ultragol.app.models.ContentItem;
import com.ultragol.app.network.A7xIptvApi;
import com.ultragol.app.network.DramaShortsApi;
import com.ultragol.app.network.TmdbApi;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class HomeFragment extends Fragment {

    // Top-level RecyclerView (header item + infinite discover grid, concatenated)
    private RecyclerView rvHome;
    private View headerView;

    // Hero banner
    private ViewPager2 hero;
    private LinearLayout dots;
    private final Handler autoHandler = new Handler();
    private int bannerPage = 0, bannerCount = 0;
    private BannerAdapter bannerAdapter;
    private final List<ContentItem> bannerItems = new ArrayList<>();

    // Content rows
    private View rowTop10, rowNew, rowMovies, rowSeries, rowAnime, rowDoramas;
    private View rowShortsdramas;
    private View rowLiveGlass;
    private View rowSagas;
    private View rowTvLive;

    // Continue watching
    private View sectionContinueWatching;
    private RecyclerView rvContinueWatching;

    // Discover (infinite grid)
    private InfiniteDiscoverAdapter discoverAdapter;
    private int  discoverPage       = 0;
    private boolean discoverLoading = false;
    private boolean kidsMode        = false;

    private static final String GOL3_API = "https://ultrago-xi.vercel.app/gol-3";

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_home, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        rvHome = view.findViewById(R.id.rvHome);

        // Detect kidsMode EARLY so the first discover page loads correctly
        try {
            ProfileManager.Profile prof = ProfileManager.getCurrentProfile(requireContext());
            if (prof != null) kidsMode = prof.isKids;
        } catch (Exception ignored) {}

        discoverAdapter = new InfiniteDiscoverAdapter(requireContext());
        discoverAdapter.setOnLoadMoreListener(this::loadMoreDiscover);

        HomeHeaderAdapter headerAdapter = new HomeHeaderAdapter(hv -> {
            headerView = hv;
            setupTopBar(hv);
            setupHero(hv);
            setupContinueWatching(hv);
            setupRows(hv);
            loadAll();
        });

        // Use resource integer so phone=2, tablet=3, TV=4 columns automatically
        int gridCols = getResources().getInteger(R.integer.home_grid_columns);
        GridLayoutManager glm = new GridLayoutManager(requireContext(), gridCols);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int pos) {
                // Position 0 is always the header item (full width).
                if (pos == 0) return gridCols;
                // For discover items: featured spans ALL columns, normal spans 1.
                // discoverAdapter.getSpanSize returns 1=normal, 2=featured (legacy hardcode).
                // We map: 1→1, anything else→gridCols so it works on TV (4 cols) too.
                if (discoverAdapter == null) return 1;
                int s = discoverAdapter.getSpanSize(pos - 1);
                return s == 1 ? 1 : gridCols;
            }
        });

        rvHome.setLayoutManager(glm);
        rvHome.setItemViewCacheSize(6);
        rvHome.setAdapter(new ConcatAdapter(headerAdapter, discoverAdapter));

        // Make RecyclerView items focusable for D-pad / keyboard navigation (TV & PC)
        TvHelper.makeFocusable(rvHome);

        // Load first discover page immediately.
        loadMoreDiscover();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (headerView != null) refreshContinueWatching();
    }

    private void setupTopBar(View view) {
        // ── Profile avatar button ──────────────────────────────────────────────
        FrameLayout btnProfile = view.findViewById(R.id.btnProfile);
        ImageView ivProfileMini = view.findViewById(R.id.ivProfileMini);
        TextView tvProfileInitial = view.findViewById(R.id.tvProfileInitial);
        if (btnProfile != null) {
            ProfileManager.Profile profile = ProfileManager.getCurrentProfile(requireContext());
            if (profile != null && ivProfileMini != null) {
                ivProfileMini.setImageResource(profile.getAvatarResId());
                ivProfileMini.setVisibility(View.VISIBLE);
                if (tvProfileInitial != null) tvProfileInitial.setVisibility(View.GONE);
            } else if (tvProfileInitial != null) {
                // No profile yet (shouldn't normally happen here) — fall back to "?"
                tvProfileInitial.setVisibility(View.VISIBLE);
                if (ivProfileMini != null) ivProfileMini.setVisibility(View.GONE);
            }
            btnProfile.setOnClickListener(v -> {
                startActivity(new android.content.Intent(
                    requireContext(), ProfileSelectorActivity.class));
                requireActivity().overridePendingTransition(
                    android.R.anim.fade_in, android.R.anim.fade_out);
            });
        }

        View search = view.findViewById(R.id.btnSearch);
        if (search != null) search.setOnClickListener(v ->
            startActivity(new Intent(requireContext(), SearchActivity.class)));

        View surprise = view.findViewById(R.id.btnSurprise);
        if (surprise != null) surprise.setOnClickListener(v -> pickSurpriseItem());

        View bell = view.findViewById(R.id.btnBell);
        if (bell != null) bell.setOnClickListener(v -> {
            com.ultragol.app.NotificationsSheet sheet = new com.ultragol.app.NotificationsSheet();
            sheet.show(getParentFragmentManager(), "notifications");
        });

        View menu = view.findViewById(R.id.btnMenu);
        if (menu != null) menu.setOnClickListener(v -> {
            if (requireActivity() instanceof com.ultragol.app.MainActivity) {
                ((com.ultragol.app.MainActivity) requireActivity()).showMenu();
            }
        });
    }

    // ── "¿No sé qué ver?" surprise pick ─────────────────────────────────────────

    private boolean surpriseLoading = false;

    private void pickSurpriseItem() {
        if (surpriseLoading || !isAdded()) return;
        surpriseLoading = true;
        Toast.makeText(requireContext(), "Buscando algo para ti…", Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            ContentItem picked = null;
            try {
                Random rng = new Random();
                List<ContentItem> pool = TmdbApi.fetchDiscoverMixed(rng.nextInt(20) + 1);
                if (pool != null && !pool.isEmpty()) picked = pool.get(rng.nextInt(pool.size()));
            } catch (Exception ignored) {}
            final ContentItem result = picked;
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                surpriseLoading = false;
                if (!isAdded()) return;
                if (result == null) {
                    Toast.makeText(requireContext(), "No se pudo encontrar nada, intenta de nuevo", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent i = new Intent(requireContext(), DetailActivity.class);
                i.putExtra("item", result);
                startActivity(i);
            });
        });
    }

    // ── Hero banner ──────────────────────────────────────────────────────────

    private void setupHero(View view) {
        hero = view.findViewById(R.id.heroBanner);
        dots = view.findViewById(R.id.bannerDots);
        bannerAdapter = new BannerAdapter(requireContext(), bannerItems);
        if (hero == null) return;
        hero.setAdapter(bannerAdapter);
        hero.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int p) { bannerPage = p; updateDots(p); }
        });
        autoHandler.postDelayed(autoScroll, 5000);
    }

    private final Runnable autoScroll = new Runnable() {
        @Override public void run() {
            if (hero != null && bannerCount > 0) {
                bannerPage = (bannerPage + 1) % bannerCount;
                hero.setCurrentItem(bannerPage, true);
                autoHandler.postDelayed(this, 5000);
            }
        }
    };

    private void buildDots() {
        if (dots == null) return;
        dots.removeAllViews();
        for (int i = 0; i < bannerCount; i++) {
            View d = new View(requireContext());
            int sz = i == 0 ? dp(10) : dp(6);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(sz, sz);
            p.setMargins(dp(4), 0, dp(4), 0);
            d.setLayoutParams(p);
            d.setBackgroundResource(i == 0 ? R.drawable.dot_active : R.drawable.dot_inactive);
            dots.addView(d);
        }
    }

    private void updateDots(int active) {
        if (dots == null) return;
        for (int i = 0; i < dots.getChildCount(); i++) {
            View d = dots.getChildAt(i);
            boolean a = i == active;
            int sz = a ? dp(10) : dp(6);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(sz, sz);
            p.setMargins(dp(4), 0, dp(4), 0);
            d.setLayoutParams(p);
            d.setBackgroundResource(a ? R.drawable.dot_active : R.drawable.dot_inactive);
        }
    }

    // ── Continuar viendo ─────────────────────────────────────────────────────

    private void setupContinueWatching(View view) {
        sectionContinueWatching = view.findViewById(R.id.sectionContinueWatching);
        rvContinueWatching      = view.findViewById(R.id.rvContinueWatching);
        if (rvContinueWatching != null) {
            rvContinueWatching.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        }
        refreshContinueWatching();
    }

    private void refreshContinueWatching() {
        if (!isAdded() || sectionContinueWatching == null || rvContinueWatching == null) return;
        List<ContinueWatchingManager.Entry> entries =
            ContinueWatchingManager.getAll(requireContext());
        if (entries.isEmpty()) {
            sectionContinueWatching.setVisibility(View.GONE);
            return;
        }
        sectionContinueWatching.setVisibility(View.VISIBLE);
        ContinueWatchingAdapter adapter =
            new ContinueWatchingAdapter(requireContext(), entries);
        adapter.setOnChangedListener(() -> {
            if (entries.isEmpty()) sectionContinueWatching.setVisibility(View.GONE);
        });
        rvContinueWatching.setAdapter(adapter);
    }

    // ── Content rows ─────────────────────────────────────────────────────────

    private void setupRows(View view) {
        rowTop10         = view.findViewById(R.id.rowTop10);
        rowNew           = view.findViewById(R.id.rowNew);
        rowShortsdramas  = view.findViewById(R.id.rowShortsdramas);
        rowMovies        = view.findViewById(R.id.rowMovies);
        rowSeries        = view.findViewById(R.id.rowSeries);
        rowAnime         = view.findViewById(R.id.rowAnime);
        rowDoramas       = view.findViewById(R.id.rowDoramas);
        rowLiveGlass     = view.findViewById(R.id.rowLiveGlass);
        rowTvLive        = view.findViewById(R.id.rowTvLive);
        rowSagas         = view.findViewById(R.id.rowSagas);

        initRow(rowNew,          "Últimos Estrenos",    new MoviesFragment());
        initRow(rowShortsdramas, "🎬 Shorts Dramas",   null);
        initRow(rowSagas,        "🎬 Sagas Completas", new SagasFragment());
        initRow(rowMovies,       "Películas Populares", new MoviesFragment());
        initRow(rowSeries,       "Series Populares",    new SeriesFragment());
        initRow(rowAnime,        "Animes",              new AnimeFragment());
        initRow(rowDoramas,      "Doramas",             new DoramasFragment());
        initRow(rowTop10,        "Top 10",              new MoviesFragment());

        // Init TV Live row
        if (rowTvLive != null) {
            RecyclerView rvTv = rowTvLive.findViewById(R.id.rvTvLive);
            if (rvTv != null) {
                rvTv.setLayoutManager(
                    new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
                rvTv.setHasFixedSize(true);
                // Populate with fallback channels
                java.util.List<com.ultragol.app.models.TvChannel> tvChs = new java.util.ArrayList<>();
                for (com.ultragol.app.models.TvChannel ch : TvFragment.getFallbackChannels()) tvChs.add(ch);
                com.ultragol.app.adapters.HomeTvAdapter tvAdapter =
                    new com.ultragol.app.adapters.HomeTvAdapter(requireContext(), tvChs);
                tvAdapter.setOnClickListener(ch -> {
                    Intent tvIntent = new Intent(requireContext(), MediaActivity.class);
                    tvIntent.putExtra("url",     ch.url);
                    tvIntent.putExtra("title",   ch.name);
                    tvIntent.putExtra("is_m3u8", true);
                    tvIntent.putExtra("referer", "");
                    startActivity(tvIntent);
                });
                rvTv.setAdapter(tvAdapter);
            }
            // "Ver TV" button
            View btnVerTv = rowTvLive.findViewById(R.id.btnVerTv);
            if (btnVerTv != null) {
                btnVerTv.setOnClickListener(v -> {
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).navigate(new TvFragment());
                    }
                });
            }
        }

        // Init live glass carousel RecyclerView
        if (rowLiveGlass != null) {
            RecyclerView rv = rowLiveGlass.findViewById(R.id.liveCarouselRv);
            if (rv != null) {
                rv.setLayoutManager(
                    new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
                rv.setHasFixedSize(true);
                rv.setItemViewCacheSize(4);
            }
        }
    }

    private void initRow(View row, String title, Fragment verTodosTarget) {
        if (row == null) return;
        TextView tv   = row.findViewById(R.id.rowTitle);
        RecyclerView rv = row.findViewById(R.id.rowRv);
        View verTodos = row.findViewById(R.id.rowVerTodos);
        if (tv != null) tv.setText(title);
        if (rv != null) {
            rv.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
            rv.setHasFixedSize(true);
            rv.setItemViewCacheSize(6);
        }
        if (verTodos != null) {
            if (verTodosTarget != null) {
                verTodos.setOnClickListener(v -> {
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).navigate(verTodosTarget);
                    }
                });
            } else {
                verTodos.setVisibility(View.GONE);
            }
        }
    }

    // ── Load data ─────────────────────────────────────────────────────────────

    private void loadAll() {
        // Detect kids profile
        boolean isKids = false;
        try {
            ProfileManager.Profile prof = ProfileManager.getCurrentProfile(requireContext());
            if (prof != null) isKids = prof.isKids;
        } catch (Exception ignored) {}

        kidsMode = isKids;
        final boolean kidsMode = isKids;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        Handler h = new Handler(android.os.Looper.getMainLooper());

        if (kidsMode) {
            // ── Kids mode: hide sports/anime/doramas rows, load family content ──
            h.post(() -> {
                if (!isAdded()) return;
                if (rowAnime != null)         rowAnime.setVisibility(View.GONE);
                if (rowDoramas != null)       rowDoramas.setVisibility(View.GONE);
                if (rowShortsdramas != null)  rowShortsdramas.setVisibility(View.GONE);
                if (rowLiveGlass != null)     rowLiveGlass.setVisibility(View.GONE);
                if (rowTvLive != null)        rowTvLive.setVisibility(View.GONE);
                if (rowSagas != null)         rowSagas.setVisibility(View.GONE);
                if (rowMovies != null)    rowMovies.setVisibility(View.VISIBLE);
                if (rowSeries != null)    rowSeries.setVisibility(View.VISIBLE);
                if (rowNew != null)       rowNew.setVisibility(View.VISIBLE);
                if (rowTop10 != null)     rowTop10.setVisibility(View.VISIBLE);
                // Relabel rows for kids
                setRowTitle(rowMovies,   "🎬 Películas Familiares");
                setRowTitle(rowSeries,   "📺 Series para Niños");
                setRowTitle(rowNew,      "✨ Animaciones");
                setRowTitle(rowTop10,    "🏆 Top Favoritos");
            });

            pool.execute(() -> { try {
                List<ContentItem> r = TmdbApi.fetchKidsTrending();
                h.post(() -> {
                    try {
                        if (!isAdded()) return;
                        bannerItems.clear();
                        bannerItems.addAll(r.size() > 6 ? r.subList(0, 6) : r);
                        if (bannerAdapter != null) bannerAdapter.notifyDataSetChanged();
                        bannerCount = bannerItems.size();
                        buildDots();
                    } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {} });

            pool.execute(() -> { try {
                List<ContentItem> r = TmdbApi.fetchKidsMovies();
                h.post(() -> { try { if (isAdded()) fillRow(rowMovies, r); } catch (Exception ignored) {} });
            } catch (Exception ignored) {} });

            pool.execute(() -> { try {
                List<ContentItem> r = TmdbApi.fetchKidsSeries();
                h.post(() -> { try { if (isAdded()) fillRow(rowSeries, r); } catch (Exception ignored) {} });
            } catch (Exception ignored) {} });

            pool.execute(() -> { try {
                List<ContentItem> r = TmdbApi.fetchKidsAnimation();
                h.post(() -> { try { if (isAdded()) fillRow(rowNew, r); } catch (Exception ignored) {} });
            } catch (Exception ignored) {} });

            pool.execute(() -> { try {
                List<ContentItem> r = TmdbApi.fetchKidsAnimationSeries();
                h.post(() -> { try { if (isAdded()) fillRow(rowTop10, r); } catch (Exception ignored) {} });
            } catch (Exception ignored) {} });

        } else {
            // ── Normal mode: explicitly restore all row visibilities ─────────
            h.post(() -> {
                if (!isAdded()) return;
                if (rowAnime != null)        rowAnime.setVisibility(View.VISIBLE);
                if (rowDoramas != null)      rowDoramas.setVisibility(View.VISIBLE);
                if (rowShortsdramas != null) rowShortsdramas.setVisibility(View.VISIBLE);
                if (rowMovies != null)       rowMovies.setVisibility(View.VISIBLE);
                if (rowSeries != null)       rowSeries.setVisibility(View.VISIBLE);
                if (rowNew != null)          rowNew.setVisibility(View.VISIBLE);
                if (rowTop10 != null)        rowTop10.setVisibility(View.VISIBLE);
                if (rowSagas != null)        rowSagas.setVisibility(View.VISIBLE);
                // Restore default row titles
                setRowTitle(rowMovies,       "Películas Populares");
                setRowTitle(rowSeries,       "Series Populares");
                setRowTitle(rowNew,          "Últimos Estrenos");
                setRowTitle(rowShortsdramas, "🎬 Shorts Dramas");
                setRowTitle(rowTop10,        "Top 10");
                setRowTitle(rowAnime,        "Animes");
                setRowTitle(rowDoramas,      "Doramas");
            });

            pool.execute(() -> { try {
                List<ContentItem> r = TmdbApi.fetchTrending();
                h.post(() -> {
                    try {
                        if (!isAdded()) return;
                        bannerItems.clear();
                        bannerItems.addAll(r.size() > 6 ? r.subList(0, 6) : r);
                        if (bannerAdapter != null) bannerAdapter.notifyDataSetChanged();
                        bannerCount = bannerItems.size();
                        buildDots();
                    } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {} });

            pool.execute(() -> { try {
                List<ContentItem> r = TmdbApi.fetchNewMovies();
                h.post(() -> { try { if (isAdded()) fillRow(rowNew, r); } catch (Exception ignored) {} });
            } catch (Exception ignored) {} });

            // Películas: A7X IPTV como fuente principal, TMDB como respaldo
            pool.execute(() -> {
                List<ContentItem> r = new java.util.ArrayList<>();
                try { r = A7xIptvApi.loadMovies(requireContext()); } catch (Exception ignored) {}
                if (r.isEmpty()) { try { r = TmdbApi.fetchMovies(); } catch (Exception ignored) {} }
                final List<ContentItem> fr = r;
                h.post(() -> { try { if (isAdded()) fillRow(rowMovies, fr); } catch (Exception ignored) {} });
            });

            // Series: A7X IPTV como fuente principal, TMDB como respaldo
            pool.execute(() -> {
                List<ContentItem> r = new java.util.ArrayList<>();
                try { r = A7xIptvApi.loadSeries(requireContext()); } catch (Exception ignored) {}
                if (r.isEmpty()) { try { r = TmdbApi.fetchSeries(); } catch (Exception ignored) {} }
                final List<ContentItem> fr = r;
                h.post(() -> { try { if (isAdded()) fillRow(rowSeries, fr); } catch (Exception ignored) {} });
            });

            pool.execute(() -> { try {
                List<ContentItem> r = TmdbApi.fetchAnime();
                h.post(() -> { try { if (isAdded()) fillRow(rowAnime, r); } catch (Exception ignored) {} });
            } catch (Exception ignored) {} });

            pool.execute(() -> { try {
                List<ContentItem> r = TmdbApi.fetchDoramas();
                h.post(() -> { try { if (isAdded()) fillRow(rowDoramas, r); } catch (Exception ignored) {} });
            } catch (Exception ignored) {} });

            pool.execute(() -> { try {
                List<ContentItem> r = TmdbApi.fetchTopMovies();
                h.post(() -> { try { if (isAdded()) fillRow(rowTop10, r); } catch (Exception ignored) {} });
            } catch (Exception ignored) {} });

            // Sagas Completas: franquicias descubiertas dinámicamente entre lo popular
            pool.execute(() -> {
                List<ContentItem> r = TmdbApi.fetchFeaturedSagas();
                h.post(() -> {
                    try {
                        if (!isAdded() || rowSagas == null) return;
                        if (r.isEmpty()) rowSagas.setVisibility(View.GONE);
                        else fillRow(rowSagas, r);
                    } catch (Exception ignored) {}
                });
            });

            // ── Shorts Dramas (Dailymotion, random) ──
            pool.execute(() -> {
                try {
                    List<DramaShortsApi.VideoItem> shorts = DramaShortsApi.fetchRecientes(1);
                    h.post(() -> {
                        try {
                            if (!isAdded() || rowShortsdramas == null) return;
                            if (shorts.isEmpty()) {
                                rowShortsdramas.setVisibility(View.GONE);
                                return;
                            }
                            RecyclerView rv = rowShortsdramas.findViewById(R.id.rowRv);
                            if (rv != null) {
                                rv.setAdapter(new DramaShortsRowAdapter(requireContext(), shorts));
                            }
                        } catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {
                    h.post(() -> { if (rowShortsdramas != null) rowShortsdramas.setVisibility(View.GONE); });
                }
            });

            // Live glass carousel
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    List<LiveMatchServerDialog.LiveMatch> matches = fetchAndParseGol3();
                    h.post(() -> {
                        try {
                            if (!isAdded() || rowLiveGlass == null) return;
                            RecyclerView rv = rowLiveGlass.findViewById(R.id.liveCarouselRv);
                            if (rv == null) return;
                            if (matches.isEmpty()) {
                                rowLiveGlass.setVisibility(View.GONE);
                            } else {
                                rv.setAdapter(new GlassLiveAdapter(requireContext(), matches));
                                rowLiveGlass.setVisibility(View.VISIBLE);
                            }
                        } catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {
                    h.post(() -> { if (rowLiveGlass != null) rowLiveGlass.setVisibility(View.GONE); });
                }
            });
        }

        pool.shutdown();
    }

    private void setRowTitle(View row, String title) {
        if (row == null) return;
        TextView tv = row.findViewById(R.id.rowTitle);
        if (tv != null) tv.setText(title);
    }

    // ── Gol-3 fetch & parse ───────────────────────────────────────────────────

    private List<LiveMatchServerDialog.LiveMatch> fetchAndParseGol3() throws Exception {
        URL url = new URL(GOL3_API);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        c.setConnectTimeout(10000);
        c.setReadTimeout(12000);
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();

        JSONObject root = new JSONObject(sb.toString());
        JSONArray arr   = root.optJSONArray("transmisiones");
        if (arr == null || arr.length() == 0) return Collections.emptyList();

        LinkedHashMap<String, List<String[]>> grouped = new LinkedHashMap<>();
        LinkedHashMap<String, JSONObject>     meta    = new LinkedHashMap<>();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.getJSONObject(i);
            String titulo   = item.optString("titulo", "Partido " + (i + 1));
            String canal    = item.optString("canal", "Servidor " + (i + 1));
            String streamUrl = item.optString("url", "");
            if (streamUrl.isEmpty()) continue;
            if (!grouped.containsKey(titulo)) {
                grouped.put(titulo, new ArrayList<>());
                meta.put(titulo, item);
            }
            grouped.get(titulo).add(new String[]{canal, streamUrl});
        }

        List<LiveMatchServerDialog.LiveMatch> result = new ArrayList<>();
        for (String titulo : grouped.keySet()) {
            JSONObject m = meta.get(titulo);
            result.add(new LiveMatchServerDialog.LiveMatch(
                titulo,
                m.optString("liga", ""),
                m.optString("hora", ""),
                m.optString("fecha", ""),
                m.optString("logoUrl", ""),
                grouped.get(titulo)
            ));
        }
        return result;
    }

    private void fillRow(View row, List<ContentItem> items) {
        if (row == null || !isAdded()) return;
        RecyclerView rv = row.findViewById(R.id.rowRv);
        if (rv != null) rv.setAdapter(new ContentRowAdapter(requireContext(), items));
    }

    // ── Discover infinite grid ────────────────────────────────────────────────
    // Note: the grid's items are appended directly to rvHome's adapter (via
    // ConcatAdapter), NOT to a separate nested RecyclerView, so they get real
    // view recycling as the user scrolls. Load-more is triggered from inside
    // InfiniteDiscoverAdapter#onBindViewHolder when nearing the end.

    private void loadMoreDiscover() {
        if (discoverLoading || discoverAdapter == null || !isAdded()) return;
        discoverLoading = true;
        discoverPage++;
        // Post to avoid IllegalStateException when called during RecyclerView layout/scroll pass
        if (rvHome != null) {
            rvHome.post(() -> discoverAdapter.showLoading());
        }

        final int pageToLoad = discoverPage;
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Handler h = new Handler(android.os.Looper.getMainLooper());
        final boolean kidsDiscover = kidsMode;
        exec.execute(() -> {
            List<ContentItem> result = new ArrayList<>();
            try {
                if (kidsDiscover) {
                    result = TmdbApi.fetchDiscoverKidsMovies(pageToLoad);
                } else {
                    result = TmdbApi.fetchDiscoverMixed(pageToLoad);
                }
            } catch (Exception ignored) {}
            final List<ContentItem> finalResult = result;
            h.post(() -> {
                // Always reset the flag so future loads can proceed
                discoverLoading = false;
                if (!isAdded() || discoverAdapter == null) return;
                discoverAdapter.addPage(finalResult);
                // Allow up to 40 pages; kids has ~20 pages of family movies
                discoverAdapter.setHasMore(discoverPage < (kidsDiscover ? 20 : 40));
            });
            exec.shutdown();
        });
    }

    private int dp(int v) {
        return Math.round(v * requireContext().getResources().getDisplayMetrics().density);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        autoHandler.removeCallbacksAndMessages(null);
    }
}
