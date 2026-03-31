package com.example.readaloud;

import android.app.Activity;
import android.speech.tts.TextToSpeech;
import android.util.Log;
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
    public void onStartContinuousRead(String fullText, String lengthsJson, int startIndex, int pageNum, int totalPages) {
        Log.d("TTS_BRIDGE", "Recibido de JS: Pág " + pageNum + " de " + totalPages);

        activity.runOnUiThread(() -> {
            try {
                if (activity instanceof MainActivity) {
                    MainActivity main = (MainActivity) activity;

                    // Sincronizar estado
                    main.currentReadingPage = pageNum;
                    main.totalPagesInCurrentPdf = totalPages;
                    main.setStartWordIndex(startIndex);

                    // Procesar mapa de palabras
                    List<Integer> wordLengths = new ArrayList<>();
                    String[] parts = lengthsJson.split(",");
                    for (String s : parts) {
                        if (!s.isEmpty()) wordLengths.add(Integer.parseInt(s));
                    }
                    main.setWordLengths(wordLengths);

                    // Iniciar voz
                    tts.stop();
                    tts.speak(fullText, TextToSpeech.QUEUE_FLUSH, null, "READ_ID");
                }
            } catch (Exception e) {
                Log.e("TTS_BRIDGE", "Error en el puente: " + e.getMessage());
            }
        });
    }
}