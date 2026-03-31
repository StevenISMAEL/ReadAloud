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
    public void onStartPageRead(String pageNum, String pageText, String lengthsJson) {
        Log.d(TAG, "onStartPageRead página " + pageNum + ", texto longitud: " + pageText.length());
        activity.runOnUiThread(() -> {
            currentPageNum = pageNum;

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
            }

            currentPageFragments = splitIntoFragments(pageText, 4000);
            currentFragmentIndex = 0;
            currentPageOffset = 0;
            speakNextFragment();
        });
    }

    private void speakNextFragment() {
        if (currentFragmentIndex >= currentPageFragments.size()) {
            Log.d(TAG, "Página " + currentPageNum + " completada");
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).evaluateJavaScript("window.onPageReadComplete();");
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

        currentPageOffset += fragment.length() + 1;
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
                ((MainActivity) activity).evaluateJavaScript("window.clearAllHighlights();");
                ((MainActivity) activity).showError("Lectura completada");
            }
        });
    }
}