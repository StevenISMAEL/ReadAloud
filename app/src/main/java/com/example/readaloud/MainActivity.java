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

    // Variables de sincronización (Públicas para que WebAppInterface las vea)
    public int globalOffsetIndex = 0;
    private List<Integer> currentWordMap = new ArrayList<>();
    private int lastHighlightedIndex = -1;

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
            // Lee toda la página actual desde el principio (índice 0)
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

        // Conectamos el puente con el nombre "AndroidApp"
        webView.addJavascriptInterface(new WebAppInterface(this, tts), "AndroidApp");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectInteractionScript();
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

            webView.loadUrl("file:///android_asset/pdfjs/web/viewer.html?file=" + tempFile.getAbsolutePath());
            btnReadAloud.setEnabled(true);

        } catch (Exception e) {
            Log.e("PDF_LOAD", "Error: " + e.getMessage());
        }
    }

    private void injectInteractionScript() {
        String js =
                // 1. Obtener spans de la página que el usuario está viendo
                "window.getSpans = function() {" +
                        "  var pageNum = window.PDFViewerApplication.page;" +
                        "  var container = document.querySelector('.page[data-page-number=\"' + pageNum + '\"] .textLayer');" +
                        "  return container ? Array.from(container.querySelectorAll('span')) : [];" +
                        "};" +

                        // 2. Iniciar lectura y enviar mapa de longitudes a Java
                        "window.startReadingFrom = function(startIndex) {" +
                        "  var spans = window.getSpans();" +
                        "  if (spans.length === 0) return;" +
                        "  var selection = spans.slice(startIndex);" +
                        "  var fullText = selection.map(s => s.innerText).join(' ');" +
                        "  var lengths = selection.map(s => s.innerText.length).join(',');" +
                        "  if (window.AndroidApp) { window.AndroidApp.onStartContinuousRead(fullText, lengths, startIndex); }" +
                        "};" +

                        // 3. Función de resaltado con auto-scroll
                        "window.highlightWord = function(index) {" +
                        "  var spans = window.getSpans();" +
                        "  var target = spans[index];" +
                        "  if (!target) return;" +
                        "  document.querySelectorAll('.reading-highlight').forEach(el => el.classList.remove('reading-highlight'));" +
                        "  target.classList.add('reading-highlight');" +
                        "  var rect = target.getBoundingClientRect();" +
                        "  if (rect.bottom > window.innerHeight || rect.top < 0) {" +
                        "    target.scrollIntoView({behavior: 'smooth', block: 'center'});" +
                        "  }" +
                        "};" +

                        // 4. Detector de toques (mousedown es más rápido que click en móviles)
                        "document.addEventListener('mousedown', function(e) {" +
                        "  if (e.target && e.target.tagName === 'SPAN' && e.target.closest('.textLayer')) {" +
                        "    var spans = window.getSpans();" +
                        "    var index = spans.indexOf(e.target);" +
                        "    if (index !== -1) window.startReadingFrom(index);" +
                        "  }" +
                        "}, true);" +

                        // Estilos CSS
                        "var style = document.createElement('style');" +
                        "style.innerHTML = '.reading-highlight { background-color: #FFF176 !important; color: black !important; border-radius: 2px; position: relative; z-index: 10; }';" +
                        "document.head.appendChild(style);";

        webView.evaluateJavascript(js, null);
    }

    // MÉTODO QUE FALTABA: Recibe el mapa de palabras desde el puente
    public void setWordMap(List<Integer> map) {
        this.currentWordMap = map;
        this.lastHighlightedIndex = -1; // Resetear para nueva lectura
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

                        int accumulated = 0;
                        for (int i = 0; i < currentWordMap.size(); i++) {
                            // El +1 es por el espacio que une las palabras en el .join(' ')
                            int nextLimit = accumulated + currentWordMap.get(i) + 1;

                            if (start >= accumulated && start < nextLimit) {
                                if (i != lastHighlightedIndex) {
                                    lastHighlightedIndex = i;
                                    // Índice absoluto = índice relativo de lectura + punto de inicio
                                    int absoluteIndex = i + globalOffsetIndex;
                                    webView.evaluateJavascript("window.highlightWord(" + absoluteIndex + ");", null);
                                }
                                break;
                            }
                            accumulated = nextLimit;
                        }
                    });
                }
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

    // --- AÑADE ESTOS MÉTODOS AL FINAL DE TU CLASE MAINACTIVITY ---

    public void setWordLengths(List<Integer> lengths) {
        this.currentWordMap = lengths;
    }

    public void setStartWordIndex(int index) {
        this.globalOffsetIndex = index;
    }

    // Este ayuda a la sincronización fina de la palabra actual
    public void setCurrentFragmentOffset(int offset) {
        // Puedes dejarlo vacío por ahora o usarlo para depuración
        Log.d("TTS", "Offset actual: " + offset);
    }

    // Un método genérico para mostrar errores que pide tu WebAppInterface
    public void showError(String message) {
        runOnUiThread(() ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        );
    }

    // Controla si el botón debe decir "Play" o "Pause"
    public void setReadingState(boolean isReading) {
        runOnUiThread(() -> {
            if (isReading) {
                btnReadAloud.setText("Pausar");
            } else {
                btnReadAloud.setText("Reproducir");
            }
        });
    }

    // ESTE ES CRUCIAL: El puente no puede llamar a webView.evaluateJavascript directamente
// así que le creamos este "túnel" de acceso.
    public void evaluateJavascript(String script) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(script, null);
            }
        });
    }
}