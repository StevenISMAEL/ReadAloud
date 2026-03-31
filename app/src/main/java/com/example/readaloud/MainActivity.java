package com.example.readaloud;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
    private RecyclerView rvRecent;
    private View recentContainer;

    public int currentReadingPage = 1;
    public int totalPagesInCurrentPdf = 0;
    public int globalOffsetIndex = 0;
    private List<Integer> currentWordMap = new ArrayList<>();
    private int lastWordIndexInPage = 0;
    private boolean isPaused = false;
    private boolean isStoppedManually = true;

    private String currentPdfUriStr;
    private String currentPdfTitle;

    private final ActivityResultLauncher<Intent> pdfPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    try {
                        // 1. Extraer el nombre real del archivo
                        String realName = getFileName(uri);

                        // 2. Pedir permiso persistente
                        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);

                        // 3. Cargar
                        loadPdfInWebView(uri, 1, 0, realName);
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, "Error al obtener archivo", Toast.LENGTH_SHORT).show();
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        recentContainer = findViewById(R.id.recentContainer);
        rvRecent = findViewById(R.id.rvRecent);
        btnSelectPdf = findViewById(R.id.btnSelectPdf);
        btnReadAloud = findViewById(R.id.btnReadAloud);
        btnPause = findViewById(R.id.btnPause);
        btnStop = findViewById(R.id.btnStop);

        tts = new TextToSpeech(this, this);
        setupWebView();

        rvRecent.setLayoutManager(new LinearLayoutManager(this));
        refreshRecentList();

        btnSelectPdf.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            pdfPickerLauncher.launch(intent);
        });

        btnReadAloud.setOnClickListener(v -> {
            isStoppedManually = false;
            if (isPaused) {
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

        btnStop.setOnClickListener(v -> {
            stopReading();
            webView.setVisibility(View.GONE);
            recentContainer.setVisibility(View.VISIBLE);
            refreshRecentList();
        });
    }

    // MÉTODO PARA OBTENER EL NOMBRE REAL
    @SuppressLint("Range")
    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result;
    }

    private void loadPdfInWebView(Uri uri, int page, int word, String title) {
        try {
            this.currentPdfUriStr = uri.toString();
            this.currentPdfTitle = title;
            this.currentReadingPage = page;
            this.lastWordIndexInPage = word;

            recentContainer.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);

            File tempFile = new File(getCacheDir(), "temp.pdf");
            try (InputStream is = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[1024];
                int l;
                while ((l = is.read(buffer)) > 0) fos.write(buffer, 0, l);
            }

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    injectInteractionScript();
                    if (page > 1) {
                        webView.evaluateJavascript("window.PDFViewerApplication.page = " + page, null);
                    }
                }
            });
            webView.loadUrl("file:///android_asset/pdfjs/web/viewer.html?file=" + tempFile.getAbsolutePath());
            btnReadAloud.setEnabled(true);
        } catch (Exception e) {
            Toast.makeText(this, "Error: PDF no accesible", Toast.LENGTH_LONG).show();
            stopReading();
            webView.setVisibility(View.GONE);
            recentContainer.setVisibility(View.VISIBLE);
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
                        "    var total = window.PDFViewerApplication.pagesCount;" +
                        "    window.AndroidApp.onStartContinuousRead(selection.map(s=>s.innerText).join(' '), selection.map(s=>s.innerText.length).join(','), startIndex, savedPage, total);" +
                        "  }, 600);" +
                        "};" +

                        "window.startReadingFrom = function(startIndex) {" +
                        "  var pageNum = window.PDFViewerApplication.page;" +
                        "  var spans = window.getSpansOfPage(pageNum);" +
                        "  if (spans.length === 0) { setTimeout(() => window.startReadingFrom(startIndex), 500); return; }" +
                        "  var selection = spans.slice(startIndex);" +
                        "  var total = window.PDFViewerApplication.pagesCount;" +
                        "  window.AndroidApp.onStartContinuousRead(selection.map(s=>s.innerText).join(' '), selection.map(s=>s.innerText.length).join(','), startIndex, pageNum, total);" +
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
                        "    var spans = Array.from(pageEl.querySelectorAll('span'));" +
                        "    var index = spans.indexOf(e.target);" +
                        "    if (index !== -1) { window.PDFViewerApplication.page = pageNum; window.startReadingFrom(index); }" +
                        "  }" +
                        "}, true);" +

                        "var style = document.createElement('style');" +
                        "style.innerHTML = '.reading-highlight { background-color: #FFF176 !important; color: black !important; border-radius: 4px; position: relative; z-index: 10; }';" +
                        "document.head.appendChild(style);";

        webView.evaluateJavascript(js, null);
    }

    public void setWordLengths(List<Integer> lengths) { this.currentWordMap = lengths; }
    public void setStartWordIndex(int index) { this.globalOffsetIndex = index; }

    private void stopReading() {
        isStoppedManually = true;
        if (tts != null) tts.stop();
        isPaused = false;
        lastWordIndexInPage = 0;
        btnReadAloud.setText("Play");
        updateUI(false);
    }

    public void updateUI(boolean reading) {
        runOnUiThread(() -> {
            btnReadAloud.setEnabled(!reading || isPaused);
            btnPause.setEnabled(reading && !isPaused);
            btnStop.setEnabled(reading || isPaused);
        });
    }

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
                @Override public void onError(String utteranceId) {}
                @Override public void onRangeStart(String utteranceId, int start, int end, int frame) {
                    runOnUiThread(() -> {
                        if (currentWordMap.isEmpty()) return;
                        int acc = 0;
                        for (int i = 0; i < currentWordMap.size(); i++) {
                            int limit = acc + currentWordMap.get(i) + 1;
                            if (start >= acc && start < limit) {
                                lastWordIndexInPage = i + globalOffsetIndex;
                                webView.evaluateJavascript("window.highlightWord(" + lastWordIndexInPage + "," + currentReadingPage + ")", null);
                                if (currentPdfUriStr != null) {
                                    RecentManager.savePdf(MainActivity.this, new RecentPdf(
                                            currentPdfTitle, currentPdfUriStr, currentReadingPage, totalPagesInCurrentPdf, lastWordIndexInPage
                                    ));
                                }
                                break;
                            }
                            acc = limit;
                        }
                    });
                }
            });
        }
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        webView.addJavascriptInterface(new WebAppInterface(this, tts), "AndroidApp");
    }

    private void refreshRecentList() {
        List<RecentPdf> list = RecentManager.getRecentPdfs(this);
        RecentAdapter adapter = new RecentAdapter(list, pdf -> {
            loadPdfInWebView(Uri.parse(pdf.uriString), pdf.lastPage, pdf.lastWordIndex, pdf.title);
        });
        rvRecent.setAdapter(adapter);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    private class RecentAdapter extends RecyclerView.Adapter<RecentAdapter.VH> {
        private final List<RecentPdf> items;
        private final OnPdfClickListener listener;

        RecentAdapter(List<RecentPdf> items, OnPdfClickListener listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_recent_pdf, p, false));
        }

        @Override public void onBindViewHolder(@NonNull VH holder, int p) {
            RecentPdf item = items.get(p);
            holder.title.setText(item.title);
            holder.info.setText("Página " + item.lastPage + (item.totalPages > 0 ? " de " + item.totalPages : ""));

            if (item.totalPages > 0) {
                holder.progress.setProgress((int)((item.lastPage/(float)item.totalPages)*100));
            }

            // Clic normal para abrir
            holder.itemView.setOnClickListener(v -> listener.onClick(item));

            // CLIC PARA BORRAR
            holder.btnDelete.setOnClickListener(v -> {
                RecentManager.removePdf(MainActivity.this, item.uriString);
                refreshRecentList(); // Refrescamos la lista para que desaparezca
                Toast.makeText(MainActivity.this, "Eliminado de recientes", Toast.LENGTH_SHORT).show();
            });
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView title, info;
            ProgressBar progress;
            View btnDelete; // Nueva vista

            VH(View v) {
                super(v);
                title = v.findViewById(R.id.tvTitle);
                info = v.findViewById(R.id.tvInfo);
                progress = v.findViewById(R.id.pbReadProgress);
                btnDelete = v.findViewById(R.id.btnDelete); // Vincular botón
            }
        }
    }
    interface OnPdfClickListener { void onClick(RecentPdf pdf); }
}