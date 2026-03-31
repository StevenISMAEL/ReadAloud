package com.example.readaloud;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.List;

public class RecentManager {
    private static final String PREFS_NAME = "RecentPdfs";
    private static final String KEY_LIST = "pdf_list";

    public static void savePdf(Context context, RecentPdf pdf) {
        List<RecentPdf> list = getRecentPdfs(context);
        // Evitar duplicados: si ya existe, lo borramos para ponerlo al principio
        list.removeIf(item -> item.uriString.equals(pdf.uriString));
        list.add(0, pdf);
        if (list.size() > 10) list.remove(list.size() - 1); // Limitar a 10 recientes

        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(KEY_LIST, new Gson().toJson(list));
        editor.apply();
    }

    public static List<RecentPdf> getRecentPdfs(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_LIST, null);
        if (json == null) return new ArrayList<>();
        return new Gson().fromJson(json, new TypeToken<List<RecentPdf>>(){}.getType());
    }
}