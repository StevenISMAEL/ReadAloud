package com.example.readaloud;

import android.app.Activity;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;

public class WebAppInterface {
    private Activity activity;
    private TextToSpeech tts;

    public WebAppInterface(Activity activity, TextToSpeech tts) {
        this.activity = activity;
        this.tts = tts;
    }

    @JavascriptInterface
    public void onStartContinuousRead(String fullText, int startIndex) {
        activity.runOnUiThread(() -> {
            if (tts != null) {
                // Reiniciamos el contador de palabras de la actividad al punto de inicio
                ((MainActivity)activity).currentWordIndex = startIndex;

                // Empezamos a hablar el bloque de texto
                tts.speak(fullText, TextToSpeech.QUEUE_FLUSH, null, "CONTINUOUS_READ");
            }
        });
    }
}