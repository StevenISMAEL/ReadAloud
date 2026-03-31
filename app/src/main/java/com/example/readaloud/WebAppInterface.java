package com.example.readaloud;

import android.app.Activity;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import java.util.ArrayList;
import java.util.List;

public class WebAppInterface {
    private Activity activity;
    private TextToSpeech tts;

    public WebAppInterface(Activity activity, TextToSpeech tts) {
        this.activity = activity;
        this.tts = tts;
    }

    @JavascriptInterface
    public void onStartContinuousRead(String fullText, String lengthsJson) {
        activity.runOnUiThread(() -> {
            List<Integer> wordLengths = new ArrayList<>();
            try {
                // Convertimos el string "5,3,8" en lista de Integers
                String[] parts = lengthsJson.split(",");
                for (String s : parts) {
                    if (!s.isEmpty()) wordLengths.add(Integer.parseInt(s));
                }

                // IMPORTANTE: Pasamos el mapa a MainActivity
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).setWordMap(wordLengths);
                }

                tts.stop();
                // El ID "READ_ID" es necesario para que onRangeStart se dispare
                tts.speak(fullText, TextToSpeech.QUEUE_FLUSH, null, "READ_ID");

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}