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
    private Button btnSelectPdf, btnReadAloud, btnPause, btnStop;

    // Estado de lectura
    public int currentReadingPage = 1;
    public int globalOffsetIndex = 0;
    private List<Integer> currentWordMap = new ArrayList<>();
    private int lastWordIndexInPage = 0;
    private boolean isPaused = false;
    private boolean isStoppedManually = true;

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
        btnPause = findViewById(R.id.btnPause);
        btnStop = findViewById(R.id.btnStop);

        tts = new TextToSpeech(this, this);
        setupWebView();

        btnReadAloud.setOnClickListener(v -> {
            isStoppedManually = false;
            if (isPaused) {
                // Reanuda anclado a la página guardada, ignorando el scroll actual
                webView.evaluateJavascript("window.resumeReading(" + lastWordIndexInPage + ", " + currentReadingPage + ");", null);
                isPaused = false;
            } else {
                webView.evaluateJavascript("window.startReadingFrom(0);", null);
            }
        });

        btnPause.setOnClickListener(v -> {
            isStoppedManually = true;
            if (tts.isSpeaking()) {
                tts.stop();
                isPaused = true;
                btnReadAloud.setText("Reanudar");
                updateUI(false);
            }
        });

        btnStop.setOnClickListener(v -> stopReading());

        btnSelectPdf.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("application/pdf");
            pdfPickerLauncher.launch(intent);
        });
    }

    private void stopReading() {
        isStoppedManually = true;
        tts.stop();
        isPaused = false;
        lastWordIndexInPage = 0;
        btnReadAloud.setText("Play");
        updateUI(false);
        webView.evaluateJavascript("document.querySelectorAll('.reading-highlight').forEach(el => el.classList.remove('reading-highlight'));", null);
    }

    public void updateUI(boolean reading) {
        runOnUiThread(() -> {
            btnReadAloud.setEnabled(!reading || isPaused);
            btnPause.setEnabled(reading && !isPaused);
            btnStop.setEnabled(reading || isPaused);
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
            Toast.makeText(this, "Error al cargar PDF", Toast.LENGTH_SHORT).show();
        }
    }

    private void injectInteractionScript() {
        String js =
                "window.getSpansOfPage = function(pageNum) {" +
                        "  var container = document.querySelector('.page[data-page-number=\"' + pageNum + '\"] .textLayer');" +
                        "  return container ? Array.from(container.querySelectorAll('span')) : [];" +
                        "};" +

                        "window.resumeReading = function(startIndex, savedPage) {" +
                        "  window.PDFViewerApplication.page = savedPage;" +
                        "  setTimeout(() => {" +
                        "    var spans = window.getSpansOfPage(savedPage);" +
                        "    if (spans.length === 0) { window.resumeReading(startIndex, savedPage); return; }" +
                        "    var selection = spans.slice(startIndex);" +
                        "    var textArray = selection.map(s => s.innerText);" +
                        "    window.AndroidApp.onStartContinuousRead(textArray.join(' '), textArray.map(s => s.length).join(','), startIndex, savedPage);" +
                        "  }, 600);" +
                        "};" +

                        "window.startReadingFrom = function(startIndex) {" +
                        "  var pageNum = window.PDFViewerApplication.page;" +
                        "  var spans = window.getSpansOfPage(pageNum);" +
                        "  if (spans.length === 0) { setTimeout(() => window.startReadingFrom(startIndex), 500); return; }" +
                        "  var selection = spans.slice(startIndex);" +
                        "  var textArray = selection.map(s => s.innerText);" +
                        "  window.AndroidApp.onStartContinuousRead(textArray.join(' '), textArray.map(s => s.length).join(','), startIndex, pageNum);" +
                        "};" +

                        "window.highlightWord = function(index, pageNum) {" +
                        "  var container = document.querySelector('.page[data-page-number=\"' + pageNum + '\"]');" +
                        "  if (!container) return;" +
                        "  container.querySelectorAll('.reading-highlight').forEach(el => el.classList.remove('reading-highlight'));" +
                        "  var target = container.querySelectorAll('.textLayer span')[index];" +
                        "  if (target) {" +
                        "    target.classList.add('reading-highlight');" +
                        "    var rect = target.getBoundingClientRect();" +
                        "    if (rect.top < 0 || rect.bottom > window.innerHeight) target.scrollIntoView({behavior: 'smooth', block: 'center'});" +
                        "  }" +
                        "};" +

                        "window.readNextPage = function() {" +
                        "  var current = window.PDFViewerApplication.page;" +
                        "  if (current < window.PDFViewerApplication.pagesCount) {" +
                        "    window.PDFViewerApplication.page = current + 1;" +
                        "    setTimeout(() => window.startReadingFrom(0), 1200);" +
                        "  }" +
                        "};" +

                        "document.addEventListener('mousedown', function(e) {" +
                        "  if (e.target.tagName === 'SPAN' && e.target.closest('.textLayer')) {" +
                        "    var pageEl = e.target.closest('.page');" +
                        "    var pageNum = parseInt(pageEl.dataset.pageNumber);" +
                        "    var index = Array.from(pageEl.querySelectorAll('.textLayer span')).indexOf(e.target);" +
                        "    if (index !== -1) { window.PDFViewerApplication.page = pageNum; window.startReadingFrom(index); }" +
                        "  }" +
                        "}, true);" +

                        "var style = document.createElement('style');" +
                        "style.innerHTML = '.reading-highlight { background-color: #FFF176 !important; color: black !important; border-radius: 2px; position: relative; z-index: 10; }';" +
                        "document.head.appendChild(style);";

        webView.evaluateJavascript(js, null);
    }

    // Métodos para WebAppInterface
    public void setWordLengths(List<Integer> lengths) { this.currentWordMap = lengths; }
    public void setStartWordIndex(int index) { this.globalOffsetIndex = index; }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(new Locale("es", "ES"));
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { updateUI(true); }
                @Override public void onDone(String utteranceId) {
                    if ("READ_ID".equals(utteranceId) && !isStoppedManually) {
                        runOnUiThread(() -> webView.evaluateJavascript("window.readNextPage();", null));
                    }
                }
                @Override public void onError(String utteranceId) { updateUI(false); }
                @Override public void onRangeStart(String utteranceId, int start, int end, int frame) {
                    runOnUiThread(() -> {
                        if (currentWordMap.isEmpty()) return;
                        int acc = 0;
                        for (int i = 0; i < currentWordMap.size(); i++) {
                            int limit = acc + currentWordMap.get(i) + 1;
                            if (start >= acc && start < limit) {
                                lastWordIndexInPage = i + globalOffsetIndex;
                                webView.evaluateJavascript("window.highlightWord(" + lastWordIndexInPage + "," + currentReadingPage + ")", null);
                                break;
                            }
                            acc = limit;
                        }
                    });
                }
            });
            btnReadAloud.setText("Play");
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}