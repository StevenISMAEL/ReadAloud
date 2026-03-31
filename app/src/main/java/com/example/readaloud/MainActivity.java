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
            // Empezar a leer desde la primera palabra (índice 0)
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

            String viewerUrl = "file:///android_asset/pdfjs/web/viewer.html";
            webView.loadUrl(viewerUrl + "?file=" + tempFile.getAbsolutePath());
            btnReadAloud.setEnabled(true);

        } catch (Exception e) {
            Log.e("PDF_LOAD", "Error: " + e.getMessage());
        }
    }

    private void injectInteractionScript() {
        // Script optimizado: Delegación de eventos para que funcione siempre
        String js =
                "document.addEventListener('click', function(e) {" +
                        "  if (e.target && e.target.tagName === 'SPAN' && e.target.closest('.textLayer')) {" +
                        "    var spans = Array.from(document.querySelectorAll('.textLayer span'));" +
                        "    var index = spans.indexOf(e.target);" +
                        "    if (index !== -1) { window.startReadingFrom(index); }" +
                        "  }" +
                        "});" +

                        "window.startReadingFrom = function(startIndex) {" +
                        "  var spans = Array.from(document.querySelectorAll('.textLayer span'));" +
                        "  var textToRead = spans.slice(startIndex).map(s => s.innerText).join(' ');" +
                        "  if (textToRead.length > 0) {" +
                        "    window.AndroidApp.onStartContinuousRead(textToRead, startIndex);" +
                        "  }" +
                        "};" +

                        "window.highlightWord = function(index) {" +
                        "  var spans = document.querySelectorAll('.textLayer span');" +
                        "  document.querySelectorAll('.reading-highlight').forEach(el => el.classList.remove('reading-highlight'));" +
                        "  if (spans[index]) {" +
                        "    spans[index].classList.add('reading-highlight');" +
                        // Scroll suave para que la lectura siempre esté a la vista
                        "    spans[index].scrollIntoView({behavior: 'smooth', block: 'center'});" +
                        "  }" +
                        "};" +

                        "var style = document.createElement('style');" +
                        "style.innerHTML = '.reading-highlight { background-color: #FFF176 !important; color: black; border-radius: 3px; }';" +
                        "document.head.appendChild(style);";

        webView.evaluateJavascript(js, null);
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
                        // Enviamos el comando de resaltado al WebView
                        webView.evaluateJavascript("window.highlightWord(" + currentWordIndex + ");", null);
                        currentWordIndex++;
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