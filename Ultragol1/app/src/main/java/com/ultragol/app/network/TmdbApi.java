package com.ultragol.app.network;

import com.ultragol.app.models.ContentItem;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class TmdbApi {
    private static final String BASE = "https://api.themoviedb.org/3";
    public  static final String IMG_W  = "https://image.tmdb.org/t/p/w342";
    public  static final String IMG_BG = "https://image.tmdb.org/t/p/w780";
    private static final String BEARER =
        "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI4NmQ5YTgzNGQ0NDEzNzAwYjQ5MWNjMjY4OTIxNDdhYSIsIm5iZiI6MTc1MjQ1NjQ4My4zNDUsInN1YiI6IjY4NzQ1ZDIzNjIwNzU1OWUwNDVhZTRjMiIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.Mm-GBMnPS_WUAslIwTiewd6khCIFIqR4XDBqTlT9Yx0";

    private static String fetch(String path) throws Exception {
        URL url = new URL(BASE + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("Authorization", "Bearer " + BEARER);
        c.setRequestProperty("accept", "application/json");
        c.setConnectTimeout(12000); c.setReadTimeout(12000);
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close(); return sb.toString();
    }

    private static String genre(JSONArray ids) {
        if (ids == null || ids.length() == 0) return "Drama";
        switch (ids.optInt(0, 18)) {
            case 28: case 10759: return "Acción"; case 12: return "Aventura";
            case 16: return "Animación"; case 35: return "Comedia";
            case 80: return "Crimen"; case 99: return "Documental";
            case 18: return "Drama"; case 10751: return "Familia";
            case 14: case 10765: return "Fantasía"; case 27: return "Terror";
            case 9648: return "Misterio"; case 10749: return "Romance";
            case 878: return "Sci-Fi"; case 53: return "Thriller";
            default: return "Drama";
        }
    }

    private static String year(String d) { return (d != null && d.length() >= 4) ? d.substring(0,4) : "2024"; }
    private static String rating(double v) { return String.format("%.1f", v); }
    private static String poster(String p) { return (p == null || p.isEmpty()) ? "" : IMG_W + p; }
    private static String backdrop(String p) { return (p == null || p.isEmpty()) ? "" : IMG_BG + p; }

    private static List<ContentItem> parse(JSONArray arr, int type) {
        List<ContentItem> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                boolean mov = o.has("title");
                String title = mov ? o.optString("title") : o.optString("name");
                if (title == null || title.isEmpty()) continue;
                int id = o.optInt("id", 0);
                String g = genre(o.optJSONArray("genre_ids"));
                String y = year(o.optString(mov ? "release_date" : "first_air_date"));
                String r = rating(o.optDouble("vote_average", 7.0));
                String post = poster(o.optString("poster_path"));
                String back = backdrop(o.optString("backdrop_path"));
                String ov = o.optString("overview", "");
                boolean isNew = false;
                try { isNew = Integer.parseInt(y) >= 2024; } catch (Exception ignored) {}
                ContentItem item = new ContentItem(title, g, y, r, post, ov, type, isNew, false);
                item.setTmdbId(id); item.setBackdropUrl(back);
                list.add(item);
            } catch (Exception ignored) {}
        }
        return list;
    }

    public static List<ContentItem> fetchTrending() throws Exception {
        JSONArray arr = new JSONObject(fetch("/trending/all/week?language=es-MX")).getJSONArray("results");
        List<ContentItem> list = new ArrayList<>();
        for (int i = 0; i < arr.length() && list.size() < 10; i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                String mt = o.optString("media_type", "movie");
                if ("person".equals(mt)) continue;
                boolean mov = "movie".equals(mt);
                String title = mov ? o.optString("title") : o.optString("name");
                if (title == null || title.isEmpty()) continue;
                int id = o.optInt("id", 0);
                String g = genre(o.optJSONArray("genre_ids"));
                String y = year(o.optString(mov ? "release_date" : "first_air_date"));
                String r = rating(o.optDouble("vote_average", 7.0));
                String post = poster(o.optString("poster_path"));
                String back = backdrop(o.optString("backdrop_path"));
                String ov = o.optString("overview", "");
                int type = mov ? ContentItem.TYPE_MOVIE : ContentItem.TYPE_SERIES;
                boolean isNew = false;
                try { isNew = Integer.parseInt(y) >= 2024; } catch (Exception ignored) {}
                ContentItem item = new ContentItem(title, g, y, r, post, ov, type, isNew, false);
                item.setTmdbId(id); item.setBackdropUrl(back);
                list.add(item);
            } catch (Exception ignored) {}
        }
        return list;
    }

    public static List<ContentItem> fetchMovies() throws Exception {
        return parse(new JSONObject(fetch("/discover/movie?sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_MOVIE);
    }
    // ── Adult content section ─────────────────────────────────────────────────

    /** Most popular adult movies (include_adult=true). */
    public static List<ContentItem> fetchAdult() throws Exception {
        return parse(new JSONObject(fetch("/discover/movie?include_adult=true&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_MOVIE);
    }

    /** Adult content sorted by popularity – alias used by AdultFragment row. */
    public static List<ContentItem> fetchAdultPopular() throws Exception {
        return parse(new JSONObject(fetch("/discover/movie?include_adult=true&sort_by=popularity.desc&language=es-MX&page=2")).getJSONArray("results"), ContentItem.TYPE_MOVIE);
    }

    /** Romance genre (10749) adult movies. */
    public static List<ContentItem> fetchAdultRomance() throws Exception {
        return parse(new JSONObject(fetch("/discover/movie?include_adult=true&with_genres=10749&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_MOVIE);
    }

    /** Thriller genre (53) adult movies. */
    public static List<ContentItem> fetchAdultThriller() throws Exception {
        return parse(new JSONObject(fetch("/discover/movie?include_adult=true&with_genres=53&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_MOVIE);
    }

    /** Drama genre (18) adult movies with high vote average. */
    public static List<ContentItem> fetchAdultDrama() throws Exception {
        return parse(new JSONObject(fetch("/discover/movie?include_adult=true&with_genres=18&sort_by=vote_average.desc&vote_count.gte=100&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_MOVIE);
    }

    /** Best rated adult movies (vote_average desc). */
    public static List<ContentItem> fetchAdultTopRated() throws Exception {
        return parse(new JSONObject(fetch("/discover/movie?include_adult=true&sort_by=vote_average.desc&vote_count.gte=200&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_MOVIE);
    }

    /** Spanish-language adult movies. */
    public static List<ContentItem> fetchAdultSpanish() throws Exception {
        return parse(new JSONObject(fetch("/discover/movie?include_adult=true&with_original_language=es&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_MOVIE);
    }

    /** Mature TV series (TV-MA equivalent: drama/thriller series, high rating). */
    public static List<ContentItem> fetchAdultSeries() throws Exception {
        return parse(new JSONObject(fetch("/discover/tv?with_genres=18,10749&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_SERIES);
    }

    /** Crime + Mystery adult films (Noir). */
    public static List<ContentItem> fetchAdultNoir() throws Exception {
        return parse(new JSONObject(fetch("/discover/movie?include_adult=true&with_genres=80,9648&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_MOVIE);
    }
    public static List<ContentItem> fetchTopMovies() throws Exception {
        return parse(new JSONObject(fetch("/movie/top_rated?language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_MOVIE);
    }
    public static List<ContentItem> fetchSeries() throws Exception {
        return parse(new JSONObject(fetch("/discover/tv?sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_SERIES);
    }
    public static List<ContentItem> fetchAnime() throws Exception {
        return parse(new JSONObject(fetch("/discover/tv?with_genres=16&with_origin_country=JP&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_ANIME);
    }
    public static List<ContentItem> fetchDoramas() throws Exception {
        return parse(new JSONObject(fetch("/discover/tv?with_origin_country=KR&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_DORAMA);
    }
    public static List<ContentItem> fetchNewMovies() throws Exception {
        return parse(new JSONObject(fetch("/movie/now_playing?language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_MOVIE);
    }

    public static List<ContentItem> fetchTopSeries() throws Exception {
        return parse(new JSONObject(fetch("/tv/top_rated?language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_SERIES);
    }
    public static List<ContentItem> fetchSeriesSpanish() throws Exception {
        return parse(new JSONObject(fetch("/discover/tv?with_original_language=es&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_SERIES);
    }
    public static List<ContentItem> fetchSeriesByGenre(int genreId) throws Exception {
        return parse(new JSONObject(fetch("/discover/tv?with_genres=" + genreId + "&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_SERIES);
    }
    public static List<ContentItem> fetchTopAnime() throws Exception {
        return parse(new JSONObject(fetch("/discover/tv?with_genres=16&with_origin_country=JP&sort_by=vote_average.desc&vote_count.gte=200&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_ANIME);
    }
    public static List<ContentItem> fetchAnimeByGenre(int genreId) throws Exception {
        return parse(new JSONObject(fetch("/discover/tv?with_genres=" + genreId + "&with_origin_country=JP&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_ANIME);
    }
    public static List<ContentItem> fetchDoramasByCountry(String countryCode) throws Exception {
        return parse(new JSONObject(fetch("/discover/tv?with_origin_country=" + countryCode + "&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_DORAMA);
    }
    public static List<ContentItem> fetchDoramasByGenre(int genreId) throws Exception {
        return parse(new JSONObject(fetch("/discover/tv?with_origin_country=KR&with_genres=" + genreId + "&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_DORAMA);
    }

    // ── Kids / Family content (genre 10751) ───────────────────────────────────
    public static List<ContentItem> fetchKidsMovies() throws Exception {
        return parse(new JSONObject(fetch("/discover/movie?with_genres=10751&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_MOVIE);
    }
    public static List<ContentItem> fetchKidsSeries() throws Exception {
        return parse(new JSONObject(fetch("/discover/tv?with_genres=10751&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_SERIES);
    }
    public static List<ContentItem> fetchKidsTrending() throws Exception {
        return parse(new JSONObject(fetch("/discover/movie?with_genres=10751&sort_by=vote_average.desc&vote_count.gte=100&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_MOVIE);
    }
    public static List<ContentItem> fetchKidsAnimation() throws Exception {
        return parse(new JSONObject(fetch("/discover/movie?with_genres=16&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_MOVIE);
    }
    public static List<ContentItem> fetchKidsAnimationSeries() throws Exception {
        return parse(new JSONObject(fetch("/discover/tv?with_genres=16,10751&sort_by=popularity.desc&language=es-MX&page=1")).getJSONArray("results"), ContentItem.TYPE_SERIES);
    }

    /**
     * Paginated kids-only discover feed (family/animation, no adult genres).
     * Uses genre 10751 (Family) and explicitly excludes Horror, Thriller, Crime,
     * War, Adult, and Romance so non-kids films never slip through.
     */
    public static List<ContentItem> fetchDiscoverKidsMovies(int page) throws Exception {
        // with_genres=10751 (Family) | without_genres=27,53,80,10752,10749,36,9648,99
        // (Horror, Thriller, Crime, War, Romance, History, Mystery, Documentary)
        String path = "/discover/movie"
            + "?with_genres=10751"
            + "&without_genres=27,53,80,10752,10749,36,9648,99"
            + "&sort_by=popularity.desc"
            + "&vote_count.gte=20"
            + "&language=es-MX"
            + "&page=" + page;
        return parse(new JSONObject(fetch(path)).getJSONArray("results"), ContentItem.TYPE_MOVIE);
    }

    /**
     * Paginated mixed discover feed (alternates movies / series pages).
     * page 1 → popular movies p1, page 2 → popular series p1,
     * page 3 → popular movies p2, page 4 → popular series p2, …
     */
    public static List<ContentItem> fetchDiscoverMixed(int page) throws Exception {
        if (page % 2 == 1) {
            int apiPage = (page + 1) / 2;
            return parse(
                new JSONObject(fetch("/discover/movie?sort_by=popularity.desc&language=es-MX&page=" + apiPage))
                    .getJSONArray("results"),
                ContentItem.TYPE_MOVIE);
        } else {
            int apiPage = page / 2;
            return parse(
                new JSONObject(fetch("/discover/tv?sort_by=popularity.desc&language=es-MX&page=" + apiPage))
                    .getJSONArray("results"),
                ContentItem.TYPE_SERIES);
        }
    }

    public static List<ContentItem> fetchByProvider(int providerId, String mediaType) throws Exception {
        int type = "movie".equals(mediaType) ? ContentItem.TYPE_MOVIE : ContentItem.TYPE_SERIES;
        String path = "/discover/" + mediaType
            + "?with_watch_providers=" + providerId
            + "&watch_region=MX"
            + "&sort_by=popularity.desc"
            + "&language=es-MX"
            + "&page=1";
        return parse(new JSONObject(fetch(path)).getJSONArray("results"), type);
    }
    // ── Episode data ──────────────────────────────────────────────────────────
    public static class EpisodeInfo {
        public final int season, number, runtime;
        public final String name, overview, stillUrl;
        public EpisodeInfo(int season, int number, String name, String overview, String stillUrl, int runtime) {
            this.season = season; this.number = number; this.name = name;
            this.overview = overview; this.stillUrl = stillUrl; this.runtime = runtime;
        }
    }

    public static List<EpisodeInfo> fetchSeasonEpisodes(int seriesId, int season) throws Exception {
        JSONObject root = new JSONObject(fetch("/tv/" + seriesId + "/season/" + season + "?language=es-MX"));
        JSONArray arr = root.optJSONArray("episodes");
        List<EpisodeInfo> list = new ArrayList<>();
        if (arr == null) return list;
        // Today's date string (yyyy-MM-dd) for filtering unaired episodes
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                .format(new java.util.Date());
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                // Skip episodes with no air_date or a future air_date — they have no content yet
                String airDate = o.optString("air_date", "");
                if (airDate.isEmpty() || airDate.compareTo(today) > 0) continue;
                int epNum = o.optInt("episode_number", i + 1);
                String name = o.optString("name", "Episodio " + epNum);
                String overview = o.optString("overview", "");
                String stillPath = o.optString("still_path", "");
                String stillUrl = stillPath.isEmpty() ? "" : "https://image.tmdb.org/t/p/w300" + stillPath;
                int runtime = o.optInt("runtime", 0);
                list.add(new EpisodeInfo(season, epNum, name, overview, stillUrl, runtime));
            } catch (Exception ignored) {}
        }
        return list;
    }

    public static int fetchSeriesSeasonCount(int seriesId) throws Exception {
        JSONObject root = new JSONObject(fetch("/tv/" + seriesId + "?language=es-MX"));
        return Math.max(1, root.optInt("number_of_seasons", 1));
    }

    public static List<ContentItem> searchMulti(String query) throws Exception {
        String enc = java.net.URLEncoder.encode(query, "UTF-8");
        JSONArray arr = new JSONObject(fetch("/search/multi?query=" + enc + "&language=es-MX&page=1")).getJSONArray("results");
        List<ContentItem> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                String mt = o.optString("media_type", "movie");
                if ("person".equals(mt)) continue;
                boolean mov = "movie".equals(mt);
                String title = mov ? o.optString("title") : o.optString("name");
                if (title == null || title.isEmpty()) continue;
                int id = o.optInt("id", 0);
                String g = genre(o.optJSONArray("genre_ids"));
                String y = year(o.optString(mov ? "release_date" : "first_air_date"));
                String r = rating(o.optDouble("vote_average", 7.0));
                String post = poster(o.optString("poster_path"));
                String back = backdrop(o.optString("backdrop_path"));
                String ov = o.optString("overview", "");
                int type = mov ? ContentItem.TYPE_MOVIE : ContentItem.TYPE_SERIES;
                ContentItem item = new ContentItem(title, g, y, r, post, ov, type, false, false);
                item.setTmdbId(id); item.setBackdropUrl(back);
                list.add(item);
            } catch (Exception ignored) {}
        }
        return list;
    }

    /**
     * Kids-safe search: searches movies and TV shows, keeps only results
     * that include Family (10751) or Animation (16) in their genre list.
     */
    public static List<ContentItem> searchMultiKids(String query) throws Exception {
        String enc = java.net.URLEncoder.encode(query, "UTF-8");
        List<ContentItem> list = new ArrayList<>();

        // Search movies
        JSONArray movies = new JSONObject(fetch("/search/movie?query=" + enc + "&language=es-MX&page=1")).getJSONArray("results");
        for (int i = 0; i < movies.length(); i++) {
            try {
                JSONObject o = movies.getJSONObject(i);
                JSONArray genreIds = o.optJSONArray("genre_ids");
                if (!hasKidsGenre(genreIds)) continue;
                String title = o.optString("title");
                if (title == null || title.isEmpty()) continue;
                int id = o.optInt("id", 0);
                String g = genre(genreIds);
                String y = year(o.optString("release_date"));
                String r = rating(o.optDouble("vote_average", 7.0));
                String post = poster(o.optString("poster_path"));
                String back = backdrop(o.optString("backdrop_path"));
                String ov = o.optString("overview", "");
                ContentItem item = new ContentItem(title, g, y, r, post, ov, ContentItem.TYPE_MOVIE, false, false);
                item.setTmdbId(id); item.setBackdropUrl(back);
                list.add(item);
            } catch (Exception ignored) {}
        }

        // Search TV series
        JSONArray series = new JSONObject(fetch("/search/tv?query=" + enc + "&language=es-MX&page=1")).getJSONArray("results");
        for (int i = 0; i < series.length(); i++) {
            try {
                JSONObject o = series.getJSONObject(i);
                JSONArray genreIds = o.optJSONArray("genre_ids");
                if (!hasKidsGenre(genreIds)) continue;
                String title = o.optString("name");
                if (title == null || title.isEmpty()) continue;
                int id = o.optInt("id", 0);
                String g = genre(genreIds);
                String y = year(o.optString("first_air_date"));
                String r = rating(o.optDouble("vote_average", 7.0));
                String post = poster(o.optString("poster_path"));
                String back = backdrop(o.optString("backdrop_path"));
                String ov = o.optString("overview", "");
                ContentItem item = new ContentItem(title, g, y, r, post, ov, ContentItem.TYPE_SERIES, false, false);
                item.setTmdbId(id); item.setBackdropUrl(back);
                list.add(item);
            } catch (Exception ignored) {}
        }

        return list;
    }

    /** Returns true if genre_ids contains Family (10751) or Animation (16). */
    private static boolean hasKidsGenre(JSONArray genreIds) {
        if (genreIds == null) return false;
        for (int i = 0; i < genreIds.length(); i++) {
            int gid = genreIds.optInt(i, -1);
            if (gid == 10751 || gid == 16) return true;
        }
        return false;
    }

    /**
     * Fetches content truly similar/recommended based on the given TMDB ID.
     * Uses TMDB's /recommendations endpoint first (curated), falls back to /similar.
     */
    public static List<ContentItem> fetchSimilar(int tmdbId, int contentType) throws Exception {
        boolean isMovie = contentType == ContentItem.TYPE_MOVIE;
        String mediaType = isMovie ? "movie" : "tv";
        // Recommendations are curated and more accurate
        String recPath = "/" + mediaType + "/" + tmdbId + "/recommendations?language=es-MX&page=1";
        try {
            JSONArray arr = new JSONObject(fetch(recPath)).getJSONArray("results");
            List<ContentItem> list = parse(arr, contentType);
            if (!list.isEmpty()) return list;
        } catch (Exception ignored) {}
        // Fallback: similar items
        String simPath = "/" + mediaType + "/" + tmdbId + "/similar?language=es-MX&page=1";
        JSONArray arr = new JSONObject(fetch(simPath)).getJSONArray("results");
        return parse(arr, contentType);
    }

    /** Result of {@link #fetchCollectionForMovie}: the saga's id/name plus its movies. */
    public static final class SagaResult {
        public final int collectionId;
        public final String name;
        public final List<ContentItem> parts;
        SagaResult(int collectionId, String name, List<ContentItem> parts) {
            this.collectionId = collectionId; this.name = name; this.parts = parts;
        }
    }

    /**
     * If this movie belongs to a franchise/saga (TMDB "collection"), returns
     * its id/display name plus every entry in that collection, sorted by
     * release year. Empty parts for standalone movies, TV/anime/doramas, or
     * on any error — collections are a movie-only TMDB concept.
     */
    public static SagaResult fetchCollectionForMovie(int movieTmdbId) {
        try {
            JSONObject details = new JSONObject(fetch("/movie/" + movieTmdbId + "?language=es-MX"));
            JSONObject collection = details.optJSONObject("belongs_to_collection");
            if (collection == null) return new SagaResult(0, "", new ArrayList<>());
            int collectionId = collection.optInt("id", 0);
            if (collectionId == 0) return new SagaResult(0, collection.optString("name", ""), new ArrayList<>());

            JSONObject root = new JSONObject(fetch("/collection/" + collectionId + "?language=es-MX"));
            JSONArray parts = root.optJSONArray("parts");
            List<ContentItem> list = parts != null ? parse(parts, ContentItem.TYPE_MOVIE) : new ArrayList<>();
            list.sort((a, b) -> a.getYear().compareTo(b.getYear()));
            return new SagaResult(collectionId, root.optString("name", collection.optString("name", "")), list);
        } catch (Exception e) {
            return new SagaResult(0, "", new ArrayList<>());
        }
    }

    /**
     * "Sagas Completas" discovery for the Home row: scans currently popular
     * movies and returns one representative item per distinct franchise
     * found — the earliest movie of each saga (opening it shows the rest of
     * the collection via fetchCollectionForMovie). Always derived from live
     * TMDB data, never a hardcoded collection list. Probes are run in
     * parallel and capped so a home load never stalls waiting on this row.
     */
    public static List<ContentItem> fetchFeaturedSagas() {
        List<ContentItem> candidates = new ArrayList<>();
        try { candidates.addAll(fetchTopMovies()); } catch (Exception ignored) {}
        try { candidates.addAll(fetchMovies()); } catch (Exception ignored) {}
        try { candidates.addAll(fetchNewMovies()); } catch (Exception ignored) {}

        Set<Integer> seenMovies = new HashSet<>();
        List<ContentItem> unique = new ArrayList<>();
        for (ContentItem c : candidates) {
            if (c.getContentType() == ContentItem.TYPE_MOVIE && seenMovies.add(c.getTmdbId())) unique.add(c);
        }
        int probeLimit = Math.min(unique.size(), 30);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<SagaResult>> futures = new ArrayList<>();
        for (int i = 0; i < probeLimit; i++) {
            ContentItem c = unique.get(i);
            futures.add(pool.submit(() -> fetchCollectionForMovie(c.getTmdbId())));
        }

        List<ContentItem> out = new ArrayList<>();
        Set<Integer> seenCollections = new HashSet<>();
        for (Future<SagaResult> f : futures) {
            try {
                SagaResult saga = f.get(10, TimeUnit.SECONDS);
                if (saga.collectionId == 0 || saga.parts.size() < 2) continue;
                if (!seenCollections.add(saga.collectionId)) continue;
                out.add(saga.parts.get(0));
            } catch (Exception ignored) {}
        }
        pool.shutdown();
        return out;
    }

    /**
     * Returns the YouTube video key for the official trailer of a movie or TV show.
     * Tries Spanish first, falls back to English. Returns "" if nothing found.
     */
    public static String fetchYouTubeTrailerKey(int tmdbId, boolean isMovie) {
        String base = isMovie ? "/movie/" + tmdbId : "/tv/" + tmdbId;
        String[] paths = { base + "/videos?language=es", base + "/videos" };
        for (String path : paths) {
            try {
                JSONObject root = new JSONObject(fetch(path));
                JSONArray arr = root.optJSONArray("results");
                if (arr == null) continue;
                String fallback = "";
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject v = arr.getJSONObject(i);
                    if (!"YouTube".equals(v.optString("site"))) continue;
                    String key  = v.optString("key", "");
                    if (key.isEmpty()) continue;
                    if ("Trailer".equals(v.optString("type"))) return key;
                    if (fallback.isEmpty()) fallback = key;
                }
                if (!fallback.isEmpty()) return fallback;
            } catch (Exception ignored) {}
        }
        return "";
    }
}
