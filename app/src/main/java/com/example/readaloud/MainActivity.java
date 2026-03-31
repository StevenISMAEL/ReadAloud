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
    private List<Integer> currentWordLengths = new ArrayList<>();
    private boolean isReading = false;
    private boolean ttsReady = false;
    private int currentFragmentOffset = 0;

    private static final String TAG = "ReadAloud";

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
        btnReadAloud.setEnabled(false);
        btnReadAloud.setText("Cargando voz...");

        setupWebView();

        btnSelectPdf.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("application/pdf");
            pdfPickerLauncher.launch(intent);
        });

        btnReadAloud.setOnClickListener(v -> {
            if (!ttsReady) {
                Toast.makeText(this, "La voz aún no está lista", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isReading) {
                Log.d(TAG, "Iniciando lectura página por página...");
                evaluateJavaScript("window.clearAllHighlights();");
                evaluateJavaScript("window.startReadingFromCurrentPage();");
            } else {
                stopReading();
            }
        });
    }

    // Método público para que WebAppInterface pueda ejecutar JavaScript
    public void evaluateJavaScript(String script) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(script, null);
            }
        });
    }

    private void stopReading() {
        if (tts != null) tts.stop();
        isReading = false;
        btnReadAloud.setText("Leer en voz alta");
        evaluateJavaScript("window.clearAllHighlights();");
        Log.d(TAG, "Lectura detenida");
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        webView.addJavascriptInterface(new WebAppInterface(this, tts), "AndroidApp");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Página cargada: " + url);
                injectInteractionScript();
                webView.postDelayed(() -> injectInteractionScript(), 2000);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e(TAG, "Error WebView: " + description);
                Toast.makeText(MainActivity.this, "Error: " + description, Toast.LENGTH_SHORT).show();
            }
        });

        webView.loadUrl("file:///android_asset/pdfjs/web/viewer.html");
    }

    private void loadPdfInWebView(Uri uri) {
        try {
            Log.d(TAG, "Cargando PDF desde URI: " + uri.toString());
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

            String viewerUrl = "file:///android_asset/pdfjs/web/viewer.html?file=" + Uri.encode(tempFile.getAbsolutePath());
            Log.d(TAG, "Cargando URL: " + viewerUrl);
            webView.loadUrl(viewerUrl);
            btnReadAloud.setEnabled(ttsReady);
            btnReadAloud.setText("Leer en voz alta");

        } catch (Exception e) {
            Log.e(TAG, "Error al cargar PDF: " + e.getMessage(), e);
            Toast.makeText(this, "Error al cargar PDF: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void injectInteractionScript() {
        String js =
                "(function() {" +
                        "    console.log('Inyectando script mejorado...');" +
                        "    window.allSpansByPage = {};" +
                        "" +
                        "    // Recolectar spans de todas las páginas y organizarlos por página\n" +
                        "    window.collectSpansByPage = function() {\n" +
                        "        var pages = document.querySelectorAll('.page');\n" +
                        "        pages.forEach(function(page) {\n" +
                        "            var pageNum = page.getAttribute('data-page-number');\n" +
                        "            if (!pageNum) return;\n" +
                        "            var spans = page.querySelectorAll('.textLayer span');\n" +
                        "            var spansArray = [];\n" +
                        "            spans.forEach(function(span) {\n" +
                        "                if (span.innerText && span.innerText.trim().length > 0) {\n" +
                        "                    spansArray.push(span);\n" +
                        "                }\n" +
                        "            });\n" +
                        "            window.allSpansByPage[pageNum] = spansArray;\n" +
                        "        });\n" +
                        "        console.log('Spans recolectados por página: ' + Object.keys(window.allSpansByPage).length);\n" +
                        "    };\n" +
                        "" +
                        "    // Obtener texto de una página específica con longitudes de palabras\n" +
                        "    window.getPageTextWithWords = function(pageNum) {\n" +
                        "        var spans = window.allSpansByPage[pageNum];\n" +
                        "        if (!spans) return null;\n" +
                        "        var fullText = \"\";\n" +
                        "        var wordLengths = [];\n" +
                        "        spans.forEach(function(span) {\n" +
                        "            var text = span.innerText;\n" +
                        "            var words = text.split(/\\s+/);\n" +
                        "            for (var i = 0; i < words.length; i++) {\n" +
                        "                if (words[i].length > 0) {\n" +
                        "                    if (fullText.length > 0) fullText += \" \";\n" +
                        "                    fullText += words[i];\n" +
                        "                    wordLengths.push(words[i].length);\n" +
                        "                }\n" +
                        "            }\n" +
                        "            fullText += \" \";\n" +
                        "            wordLengths.push(1); // espacio entre spans\n" +
                        "        });\n" +
                        "        return { text: fullText, lengths: wordLengths, spans: spans };\n" +
                        "    };\n" +
                        "" +
                        "    // Obtener página actual visible\n" +
                        "    window.getCurrentVisiblePage = function() {\n" +
                        "        var pages = document.querySelectorAll('.page');\n" +
                        "        var viewportHeight = window.innerHeight;\n" +
                        "        for (var i = 0; i < pages.length; i++) {\n" +
                        "            var rect = pages[i].getBoundingClientRect();\n" +
                        "            if (rect.top >= -viewportHeight/2 && rect.top <= viewportHeight/2) {\n" +
                        "                return pages[i].getAttribute('data-page-number');\n" +
                        "            }\n" +
                        "        }\n" +
                        "        return pages.length > 0 ? pages[0].getAttribute('data-page-number') : null;\n" +
                        "    };\n" +
                        "" +
                        "    // Cambiar a una página específica usando API de PDF.js\n" +
                        "    window.goToPage = function(pageNum) {\n" +
                        "        if (window.PDFViewerApplication && window.PDFViewerApplication.page) {\n" +
                        "            window.PDFViewerApplication.page = parseInt(pageNum);\n" +
                        "        } else {\n" +
                        "            // Fallback: simular clic en el botón de página\n" +
                        "            var input = document.querySelector('input[data-pdfjs-page-number]');\n" +
                        "            if (input) {\n" +
                        "                input.value = pageNum;\n" +
                        "                input.dispatchEvent(new Event('change'));\n" +
                        "            }\n" +
                        "        }\n" +
                        "    };\n" +
                        "" +
                        "    // Iniciar lectura desde la página actual\n" +
                        "    window.startReadingFromCurrentPage = function() {\n" +
                        "        var currentPage = window.getCurrentVisiblePage();\n" +
                        "        if (!currentPage) {\n" +
                        "            console.log('No se pudo obtener página actual');\n" +
                        "            return;\n" +
                        "        }\n" +
                        "        window.currentReadingPage = currentPage;\n" +
                        "        var pageData = window.getPageTextWithWords(currentPage);\n" +
                        "        if (!pageData || pageData.text.length === 0) {\n" +
                        "            if (window.AndroidApp) window.AndroidApp.onError('No hay texto en la página');\n" +
                        "            return;\n" +
                        "        }\n" +
                        "        var lengthsJson = pageData.lengths.join(',');\n" +
                        "        if (window.AndroidApp) {\n" +
                        "            window.AndroidApp.onStartPageRead(currentPage, pageData.text, lengthsJson);\n" +
                        "        }\n" +
                        "    };\n" +
                        "" +
                        "    // Resaltar palabra en la página actual\n" +
                        "    window.highlightWordInPage = function(pageNum, wordIndex) {\n" +
                        "        var spans = window.allSpansByPage[pageNum];\n" +
                        "        if (!spans) return;\n" +
                        "        // Limpiar resaltados en esta página\n" +
                        "        spans.forEach(function(span) {\n" +
                        "            span.classList.remove('reading-highlight');\n" +
                        "        });\n" +
                        "        // Encontrar el span que contiene la palabra\n" +
                        "        var currentPos = 0;\n" +
                        "        for (var i = 0; i < spans.length; i++) {\n" +
                        "            var words = spans[i].innerText.split(/\\s+/);\n" +
                        "            for (var j = 0; j < words.length; j++) {\n" +
                        "                if (words[j].length > 0) {\n" +
                        "                    if (currentPos === wordIndex) {\n" +
                        "                        spans[i].classList.add('reading-highlight');\n" +
                        "                        spans[i].scrollIntoView({behavior: 'smooth', block: 'center'});\n" +
                        "                        return;\n" +
                        "                    }\n" +
                        "                    currentPos++;\n" +
                        "                }\n" +
                        "            }\n" +
                        "            currentPos++; // espacio entre spans\n" +
                        "        }\n" +
                        "    };\n" +
                        "" +
                        "    // Limpiar todos los resaltados\n" +
                        "    window.clearAllHighlights = function() {\n" +
                        "        document.querySelectorAll('.reading-highlight').forEach(function(el) {\n" +
                        "            el.classList.remove('reading-highlight');\n" +
                        "        });\n" +
                        "    };\n" +
                        "" +
                        "    // Observador para nuevos spans\n" +
                        "    var observer = new MutationObserver(function() {\n" +
                        "        window.collectSpansByPage();\n" +
                        "    });\n" +
                        "    observer.observe(document.body, { childList: true, subtree: true });\n" +
                        "" +
                        "    // Estilo de resaltado\n" +
                        "    var style = document.createElement('style');\n" +
                        "    style.innerHTML = '.reading-highlight { background-color: #FFEB3B !important; color: #000000 !important; box-shadow: 0 0 5px rgba(0,0,0,0.3); border-radius: 3px; }';\n" +
                        "    document.head.appendChild(style);\n" +
                        "" +
                        "    // Recolectar spans después de cargar\n" +
                        "    setTimeout(function() { window.collectSpansByPage(); }, 1500);\n" +
                        "" +
                        "    // Exponer función para notificar que una página terminó (será llamada desde Android)\n" +
                        "    window.onPageReadComplete = function() {\n" +
                        "        var nextPage = parseInt(window.currentReadingPage) + 1;\n" +
                        "        var totalPages = Object.keys(window.allSpansByPage).length;\n" +
                        "        if (nextPage <= totalPages) {\n" +
                        "            window.goToPage(nextPage);\n" +
                        "            setTimeout(function() {\n" +
                        "                window.currentReadingPage = nextPage;\n" +
                        "                var pageData = window.getPageTextWithWords(nextPage);\n" +
                        "                if (pageData && pageData.text.length > 0) {\n" +
                        "                    var lengthsJson = pageData.lengths.join(',');\n" +
                        "                    if (window.AndroidApp) {\n" +
                        "                        window.AndroidApp.onStartPageRead(nextPage, pageData.text, lengthsJson);\n" +
                        "                    }\n" +
                        "                } else {\n" +
                        "                    // No hay texto, pasar a siguiente\n" +
                        "                    window.onPageReadComplete();\n" +
                        "                }\n" +
                        "            }, 1000);\n" +
                        "        } else {\n" +
                        "            if (window.AndroidApp) window.AndroidApp.onReadingFinished();\n" +
                        "        }\n" +
                        "    };\n" +
                        "})();";

        webView.evaluateJavascript(js, null);
    }

    // Métodos usados por WebAppInterface
    public void setWordLengths(List<Integer> lengths) {
        this.currentWordLengths = lengths;
        Log.d(TAG, "Longitudes de palabras para página actual: " + lengths.size());
    }

    public void setReadingState(boolean reading) {
        this.isReading = reading;
        runOnUiThread(() -> {
            if (reading) btnReadAloud.setText("Detener lectura");
            else btnReadAloud.setText("Leer en voz alta");
        });
    }

    public void showError(String error) {
        runOnUiThread(() -> {
            Toast.makeText(this, "Error: " + error, Toast.LENGTH_LONG).show();
            Log.e(TAG, "Error: " + error);
        });
    }

    public void setCurrentFragmentOffset(int offset) {
        this.currentFragmentOffset = offset;
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(new Locale("es", "ES"));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = tts.setLanguage(new Locale("es"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                    Log.w(TAG, "Usando idioma por defecto: " + tts.getLanguage());
                }
            }
            tts.setSpeechRate(0.9f);
            tts.setPitch(1.0f);
            ttsReady = true;
            runOnUiThread(() -> {
                btnReadAloud.setEnabled(true);
                btnReadAloud.setText("Leer en voz alta");
            });
            Log.d(TAG, "TTS listo. Idioma: " + tts.getLanguage());

            tts.speak("Voz lista", TextToSpeech.QUEUE_FLUSH, null, "test");

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    if (!utteranceId.equals("test")) {
                        runOnUiThread(() -> setReadingState(true));
                    }
                }

                @Override
                public void onDone(String utteranceId) {
                    if (utteranceId.equals("test")) return;
                }

                @Override
                public void onError(String utteranceId) {
                    Log.e(TAG, "Error en utterance: " + utteranceId);
                    runOnUiThread(() -> {
                        setReadingState(false);
                        showError("Error en reproducción de voz");
                    });
                }

                @Override
                public void onRangeStart(String utteranceId, int start, int end, int frame) {
                    if (!utteranceId.startsWith("PAGE_")) return;
                    if (currentWordLengths.isEmpty()) return;

                    int absoluteStart = currentFragmentOffset + start;
                    int accumulatedChars = 0;
                    int targetWordIndex = -1;
                    for (int i = 0; i < currentWordLengths.size(); i++) {
                        int wordLen = currentWordLengths.get(i);
                        int nextLimit = accumulatedChars + wordLen;
                        if (absoluteStart >= accumulatedChars && absoluteStart < nextLimit) {
                            targetWordIndex = i;
                            break;
                        }
                        accumulatedChars = nextLimit + 1;
                    }

                    if (targetWordIndex != -1) {
                        String pageNum = utteranceId.split("_")[1];
                        evaluateJavaScript("window.highlightWordInPage(" + pageNum + ", " + targetWordIndex + ");");
                    }
                }
            });
        } else {
            Log.e(TAG, "Fallo al inicializar TTS. Status: " + status);
            runOnUiThread(() -> {
                btnReadAloud.setEnabled(false);
                btnReadAloud.setText("Voz no disponible");
                Toast.makeText(this, "No se pudo inicializar la voz", Toast.LENGTH_LONG).show();
            });
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}