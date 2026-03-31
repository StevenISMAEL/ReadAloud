package com.example.readaloud;

import android.app.Activity;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class WebAppInterface {
    private Activity activity;
    private TextToSpeech tts;
    private static final String TAG = "WebAppInterface";
    private String currentPageNum;
    private List<String> currentPageFragments = new ArrayList<>();
    private int currentFragmentIndex = 0;
    private int currentPageOffset = 0;

    public WebAppInterface(Activity activity, TextToSpeech tts) {
        this.activity = activity;
        this.tts = tts;
    }

    @JavascriptInterface
    public void onStartPageRead(String pageNum, String pageText, String lengthsJson, int startWordIndex) {
        Log.d(TAG, "onStartPageRead página " + pageNum + ", startWordIndex=" + startWordIndex +
                ", texto longitud: " + pageText.length());
        activity.runOnUiThread(() -> {
            currentPageNum = pageNum;

            // Procesar longitudes de palabras (corresponden al texto ya recortado)
            List<Integer> wordLengths = new ArrayList<>();
            if (lengthsJson != null && !lengthsJson.isEmpty()) {
                for (String s : lengthsJson.split(",")) {
                    if (!s.trim().isEmpty()) {
                        wordLengths.add(Integer.parseInt(s.trim()));
                    }
                }
            }
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).setWordLengths(wordLengths);
                ((MainActivity) activity).setStartWordIndex(startWordIndex);
            }

            // Dividir el texto de la página en fragmentos (máx 4000 caracteres)
            currentPageFragments = splitIntoFragments(pageText, 4000);
            currentFragmentIndex = 0;
            currentPageOffset = 0;
            speakNextFragment();
        });
    }

    private void speakNextFragment() {
        if (currentFragmentIndex >= currentPageFragments.size()) {
            // Página completada
            Log.d(TAG, "Página " + currentPageNum + " completada");
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).runOnUiThread(() -> {
                    ((MainActivity) activity).evaluateJavascript("window.onPageReadComplete();");
                });
            }
            return;
        }

        String fragment = currentPageFragments.get(currentFragmentIndex);
        Log.d(TAG, "Reproduciendo fragmento " + (currentFragmentIndex + 1) + "/" + currentPageFragments.size() +
                ", longitud: " + fragment.length());

        if (activity instanceof MainActivity) {
            ((MainActivity) activity).setCurrentFragmentOffset(currentPageOffset);
        }

        String utteranceId = "PAGE_" + currentPageNum + "_" + currentFragmentIndex;
        int result = tts.speak(fragment, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "Error al reproducir fragmento");
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).showError("Error en TTS al reproducir fragmento");
            }
        }

        // Actualizar offset para el próximo fragmento
        currentPageOffset += fragment.length() + 1; // +1 aproximado por espacio entre fragmentos
        currentFragmentIndex++;
    }

    private List<String> splitIntoFragments(String text, int maxLength) {
        List<String> fragments = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxLength, text.length());
            if (end < text.length()) {
                int lastPunct = Math.max(
                        Math.max(text.lastIndexOf('.', end), text.lastIndexOf('!', end)),
                        text.lastIndexOf('?', end)
                );
                int lastSpace = text.lastIndexOf(' ', end);
                int cut = Math.max(lastPunct, lastSpace);
                if (cut > start) {
                    end = cut + 1;
                }
            }
            fragments.add(text.substring(start, end));
            start = end;
        }
        return fragments;
    }

    @JavascriptInterface
    public void onError(String error) {
        Log.e(TAG, "Error desde JS: " + error);
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).showError(error);
        }
    }

    @JavascriptInterface
    public void onReadingFinished() {
        Log.d(TAG, "Lectura completada");
        activity.runOnUiThread(() -> {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).setReadingState(false);
                ((MainActivity) activity).evaluateJavascript("window.clearAllHighlights();");
                ((MainActivity) activity).showError("Lectura completada");
            }
        });
    }

    @JavascriptInterface
    public void onStartContinuousRead(String fullText, String lengthsJson, int startIndex) {
        activity.runOnUiThread(() -> {
            try {
                // 1. Guardamos dónde empezamos en la página
                ((MainActivity) activity).globalOffsetIndex = startIndex;

                // 2. Convertimos el mapa de longitudes
                List<Integer> wordLengths = new ArrayList<>();
                for (String s : lengthsJson.split(",")) {
                    if (!s.isEmpty()) wordLengths.add(Integer.parseInt(s));
                }
                ((MainActivity) activity).setWordMap(wordLengths);

                // 3. Hablamos
                tts.stop();
                tts.speak(fullText, TextToSpeech.QUEUE_FLUSH, null, "READ_ID");
            } catch (Exception e) {
                Log.e("BRIDGE", "Error en sincronización: " + e.getMessage());
            }
        });
    }
}