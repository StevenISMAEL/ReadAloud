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
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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

    // Estado de sincronización
    public int globalOffsetIndex = 0;
    public int currentReadingPage = 1;
    private List<Integer> currentWordMap = new ArrayList<>();
    private int lastHighlightedIndex = -1;

    private final ActivityResultLauncher<Intent> pdfPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    loadPdfInWebView(result.getData().getData());
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
            // Empezar lectura desde la página actual del visor
            webView.evaluateJavascript("window.startReadingFrom(0);", null);
        });
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);

        webView.addJavascriptInterface(new WebAppInterface(this, tts), "AndroidApp");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                injectInteractionScript();
            }
        });
        webView.loadUrl("file:///android_asset/pdfjs/web/viewer.html");
    }

    private void loadPdfInWebView(Uri uri) {
        try {
            File tempFile = new File(getCacheDir(), "temp.pdf");
            InputStream is = getContentResolver().openInputStream(uri);
            FileOutputStream fos = new FileOutputStream(tempFile);
            byte[] buffer = new byte[1024];
            int l;
            while ((l = is.read(buffer)) > 0) fos.write(buffer, 0, l);
            fos.close(); is.close();

            webView.loadUrl("file:///android_asset/pdfjs/web/viewer.html?file=" + tempFile.getAbsolutePath());
            btnReadAloud.setEnabled(true);
        } catch (Exception e) {
            Log.e("PDF", "Error cargando: " + e.getMessage());
        }
    }

    private void injectInteractionScript() {
        String js =
                "window.getSpansOfPage = function(pageNum) {" +
                        "  var container = document.querySelector('.page[data-page-number=\"' + pageNum + '\"] .textLayer');" +
                        "  return container ? Array.from(container.querySelectorAll('span')) : [];" +
                        "};" +

                        "window.startReadingFrom = function(startIndex) {" +
                        "  var pageNum = window.PDFViewerApplication.page;" +
                        "  var spans = window.getSpansOfPage(pageNum);" +
                        "  if (spans.length === 0) {" +
                        "    console.log('Esperando renderizado...');" +
                        "    setTimeout(() => window.startReadingFrom(startIndex), 500);" +
                        "    return;" +
                        "  }" +
                        "  var selection = spans.slice(startIndex);" +
                        "  var fullText = selection.map(s => s.innerText).join(' ');" +
                        "  var lengths = selection.map(s => s.innerText.length).join(',');" +
                        "  window.AndroidApp.onStartContinuousRead(fullText, lengths, startIndex, pageNum);" +
                        "};" +

                        "window.highlightWord = function(index, pageNum) {" +
                        "  var container = document.querySelector('.page[data-page-number=\"' + pageNum + '\"]');" +
                        "  if (!container) return;" +
                        "  container.querySelectorAll('.reading-highlight').forEach(el => el.classList.remove('reading-highlight'));" +
                        "  var spans = container.querySelectorAll('.textLayer span');" +
                        "  var target = spans[index];" +
                        "  if (target) {" +
                        "    target.classList.add('reading-highlight');" +
                        "    var rect = target.getBoundingClientRect();" +
                        "    if (rect.top < 0 || rect.bottom > window.innerHeight) target.scrollIntoView({behavior: 'smooth', block: 'center'});" +
                        "  }" +
                        "};" +

                        "window.readNextPage = function() {" +
                        "  var total = window.PDFViewerApplication.pagesCount;" +
                        "  var current = window.PDFViewerApplication.page;" +
                        "  if (current < total) {" +
                        "    window.PDFViewerApplication.page = current + 1;" +
                        "    setTimeout(() => window.startReadingFrom(0), 1000);" +
                        "  }" +
                        "};" +

                        "document.addEventListener('mousedown', function(e) {" +
                        "  if (e.target && e.target.tagName === 'SPAN' && e.target.closest('.textLayer')) {" +
                        "    var pageEl = e.target.closest('.page');" +
                        "    var pageNum = parseInt(pageEl.dataset.pageNumber);" +
                        "    var spans = window.getSpansOfPage(pageNum);" +
                        "    var index = spans.indexOf(e.target);" +
                        "    if (index !== -1) { window.PDFViewerApplication.page = pageNum; window.startReadingFrom(index); }" +
                        "  }" +
                        "}, true);" +

                        "var style = document.createElement('style');" +
                        "style.innerHTML = '.reading-highlight { background-color: #FFF176 !important; color: black !important; border-radius: 2px; position: relative; z-index: 10; }';" +
                        "document.head.appendChild(style);";

        webView.evaluateJavascript(js, null);
    }

    public void setWordLengths(List<Integer> lengths) {
        this.currentWordMap = lengths;
        this.lastHighlightedIndex = -1;
    }

    public void setStartWordIndex(int index) {
        this.globalOffsetIndex = index;
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(new Locale("es", "ES"));
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {
                    runOnUiThread(() -> btnReadAloud.setText("Pausar"));
                }

                @Override public void onDone(String utteranceId) {
                    if ("READ_ID".equals(utteranceId)) {
                        runOnUiThread(() -> webView.evaluateJavascript("window.readNextPage();", null));
                    }
                }

                @Override public void onError(String utteranceId) {}

                @Override
                public void onRangeStart(String utteranceId, int start, int end, int frame) {
                    runOnUiThread(() -> {
                        if (currentWordMap.isEmpty()) return;
                        int acc = 0;
                        for (int i = 0; i < currentWordMap.size(); i++) {
                            int limit = acc + currentWordMap.get(i) + 1;
                            if (start >= acc && start < limit) {
                                if (i != lastHighlightedIndex) {
                                    lastHighlightedIndex = i;
                                    webView.evaluateJavascript("window.highlightWord(" + (i + globalOffsetIndex) + ", " + currentReadingPage + ");", null);
                                }
                                break;
                            }
                            acc = limit;
                        }
                    });
                }
            });
            btnReadAloud.setText("Reproducir");
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}