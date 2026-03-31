package com.example.readaloud;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private WebView webView;
    private TextToSpeech tts;
    private Button btnSelectPdf, btnReadAloud;
    public int currentWordIndex = 0; // Índice de la palabra que se está leyendo

    private final ActivityResultLauncher<Intent> pdfPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    loadPdfInWebView(uri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        btnSelectPdf = findViewById(R.id.btnSelectPdf);
        btnReadAloud = findViewById(R.id.btnReadAloud);

        tts = new TextToSpeech(this, this);

        setupWebView();

        btnSelectPdf.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("application/pdf");
            pdfPickerLauncher.launch(intent);
        });

        btnReadAloud.setOnClickListener(v -> {
            webView.evaluateJavascript("window.startReadingFrom(0);", null);
        });
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        webView.addJavascriptInterface(new WebAppInterface(this, tts), "AndroidApp");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                // Inyectamos inmediatamente
                injectInteractionScript();

                // Re-inyectamos a los 2 y 5 segundos porque PDF.js es lento cargando el texto Layer
                webView.postDelayed(() -> injectInteractionScript(), 2000);
                webView.postDelayed(() -> injectInteractionScript(), 5000);
            }
        });

        webView.loadUrl("file:///android_asset/pdfjs/web/viewer.html");
    }

    private void loadPdfInWebView(Uri uri) {
        try {
            File tempFile = new File(getCacheDir(), "temp_viewer.pdf");
            InputStream inputStream = getContentResolver().openInputStream(uri);
            FileOutputStream outputStream = new FileOutputStream(tempFile);

            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
            outputStream.close();
            inputStream.close();

            String viewerUrl = "file:///android_asset/pdfjs/web/viewer.html";
            webView.loadUrl(viewerUrl + "?file=" + tempFile.getAbsolutePath());
            btnReadAloud.setEnabled(true);

        } catch (Exception e) {
            Log.e("PDF_LOAD", "Error: " + e.getMessage());
        }
    }

    private void injectInteractionScript() {
        String js =
                // 1. Buscamos todas las palabras de la página que el usuario está viendo realmente
                "window.getSpansOfVisiblePage = function() {" +
                        "  var pages = document.querySelectorAll('.page');" +
                        "  for (var p of pages) {" +
                        "    var rect = p.getBoundingClientRect();" +
                        "    if (rect.top >= -rect.height/2 && rect.top <= window.innerHeight/2) {" + // Página más visible
                        "      return { pageNum: p.dataset.pageNumber, spans: Array.from(p.querySelectorAll('.textLayer span')) };" +
                        "    }" +
                        "  }" +
                        "  return { pageNum: 1, spans: Array.from(document.querySelectorAll('.textLayer span')) };" +
                        "};" +

                        // 2. Función de inicio de lectura
                        "window.startReadingFrom = function(startIndex) {" +
                        "  var data = window.getSpansOfVisiblePage();" +
                        "  if (data.spans.length === 0) return;" +
                        "  window.currentReadingPage = data.pageNum;" +
                        "  var textToRead = data.spans.slice(startIndex).map(s => s.innerText).join(' ');" +
                        "  if (window.AndroidApp) { window.AndroidApp.onStartContinuousRead(textToRead, startIndex); }" +
                        "};" +

                        // 3. Resaltado (Busca el span en la página activa)
                        "window.highlightWord = function(index) {" +
                        "  var data = window.getSpansOfVisiblePage();" +
                        "  document.querySelectorAll('.reading-highlight').forEach(el => el.classList.remove('reading-highlight'));" +
                        "  var target = data.spans[index];" +
                        "  if (target) {" +
                        "    target.classList.add('reading-highlight');" +
                        "    target.scrollIntoView({behavior: 'smooth', block: 'center'});" +
                        "  }" +
                        "};" +

                        // 4. Detector de clics Global (No importa si la página se recarga)
                        "document.addEventListener('mousedown', function(e) {" +
                        "  if (e.target && e.target.tagName === 'SPAN') {" +
                        "    var data = window.getSpansOfVisiblePage();" +
                        "    var index = data.spans.indexOf(e.target);" +
                        "    if (index !== -1) { window.startReadingFrom(index); }" +
                        "  }" +
                        "}, true);" +

                        // 5. Vigilante de cambios (MutationObserver)
                        "var observer = new MutationObserver(function(mutations) {" +
                        "  document.querySelectorAll('.textLayer span').forEach(s => s.style.cursor = 'pointer');" +
                        "});" +
                        "observer.observe(document.body, { childList: true, subtree: true });" +

                        // Estilos
                        "var style = document.createElement('style');" +
                        "style.innerHTML = '.reading-highlight { background-color: #FFF176 !important; color: black !important; position: relative; z-index: 999; border-radius: 2px; }';" +
                        "document.head.appendChild(style);";

        webView.evaluateJavascript(js, null);
    }

    private List<Integer> currentWordMap = new ArrayList<>();

    public void setWordMap(List<Integer> map) {
        this.currentWordMap = map;
    }
    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(new Locale("es", "ES"));
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onDone(String utteranceId) {}
                @Override public void onError(String utteranceId) {}

                @Override
                public void onRangeStart(String utteranceId, int start, int end, int frame) {
                    runOnUiThread(() -> {
                        if (currentWordMap.isEmpty()) return;

                        int accumulatedChars = 0;
                        int targetSpanIndex = -1;

                        // Buscamos a qué span pertenece el caracter 'start'
                        for (int i = 0; i < currentWordMap.size(); i++) {
                            // El +1 es por el espacio que añadimos en el .join(' ') de JS
                            int nextLimit = accumulatedChars + currentWordMap.get(i) + 1;

                            if (start >= accumulatedChars && start < nextLimit) {
                                targetSpanIndex = i;
                                break;
                            }
                            accumulatedChars = nextLimit;
                        }

                        if (targetSpanIndex != -1) {
                            // Solo mandamos a resaltar si el índice cambió para no saturar el WebView
                            webView.evaluateJavascript("window.highlightWord(" + targetSpanIndex + ");", null);
                        }
                    });
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}