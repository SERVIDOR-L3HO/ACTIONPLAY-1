package com.ultragol.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Favorite live-TV channels, keyed by stream URL — persisted locally per device. */
public class TvFavoritesManager {
    private static final String PREFS = "tv_favorites";
    private static final String KEY   = "urls";

    public static boolean isFavorite(Context ctx, String url) {
        return prefs(ctx).getStringSet(KEY, new HashSet<>()).contains(url);
    }

    public static void toggle(Context ctx, String url) {
        if (isFavorite(ctx, url)) remove(ctx, url); else add(ctx, url);
    }

    public static void add(Context ctx, String url) {
        Set<String> set = new HashSet<>(prefs(ctx).getStringSet(KEY, new HashSet<>()));
        set.add(url);
        prefs(ctx).edit().putStringSet(KEY, set).apply();
    }

    public static void remove(Context ctx, String url) {
        Set<String> set = new HashSet<>(prefs(ctx).getStringSet(KEY, new HashSet<>()));
        set.remove(url);
        prefs(ctx).edit().putStringSet(KEY, set).apply();
    }

    public static Set<String> getAll(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY, new HashSet<>()));
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
