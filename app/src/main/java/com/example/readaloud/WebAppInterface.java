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
    public void onStartContinuousRead(String fullText, String lengthsJson, int startIndex, int pageNum) {
        activity.runOnUiThread(() -> {
            try {
                if (activity instanceof MainActivity) {
                    MainActivity main = (MainActivity) activity;

                    // Sincronizar el estado actual
                    main.currentReadingPage = pageNum;
                    main.setStartWordIndex(startIndex);

                    // Convertir el mapa de longitudes de palabras
                    List<Integer> wordLengths = new ArrayList<>();
                    String[] parts = lengthsJson.split(",");
                    for (String s : parts) {
                        if (!s.isEmpty()) wordLengths.add(Integer.parseInt(s));
                    }
                    main.setWordLengths(wordLengths);

                    // Detener cualquier lectura previa e iniciar la nueva
                    tts.stop();
                    tts.speak(fullText, TextToSpeech.QUEUE_FLUSH, null, "READ_ID");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}