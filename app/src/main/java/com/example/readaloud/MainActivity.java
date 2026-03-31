package com.example.readaloud;

import android.content.Intent;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;

import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener, OnPageChangeListener {

    private PDFView pdfView;
    private HighlightView highlightView;
    private Button btnSelectPdf, btnReadAloud;

    private TextToSpeech tts;
    private Uri pdfUri;
    private PDFWordDetective detective;
    private PDDocument document;

    private int readingPage = 0;
    private int startWordIndexForCurrentSpeech = 0;
    private boolean isAnalyzing = false;

    private final ActivityResultLauncher<Intent> pdfPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    pdfUri = result.getData().getData();
                    loadPdf(pdfUri);
                    btnReadAloud.setEnabled(true);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        PDFBoxResourceLoader.init(getApplicationContext());

        pdfView = findViewById(R.id.pdfView);
        highlightView = findViewById(R.id.highlightView);
        btnSelectPdf = findViewById(R.id.btnSelectPdf);
        btnReadAloud = findViewById(R.id.btnReadAloud);



        tts = new TextToSpeech(this, this);

        btnSelectPdf.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("application/pdf");
            pdfPickerLauncher.launch(intent);
        });

        btnReadAloud.setOnClickListener(v -> {
            readingPage = pdfView.getCurrentPage();
            analyzePageAndSpeak(readingPage, 0);
        });
    }

    private void loadPdf(Uri uri) {
        pdfView.fromUri(uri)
                .defaultPage(0)
                .onPageChange(this)
                .enableAnnotationRendering(true) // Útil para PDFs con enlaces
                .enableDoubletap(true)
                .onTap(e -> {
                    // ESTA ES LA CLAVE: El visor detecta el toque y nos lo pasa
                    if (detective != null && !isAnalyzing) {
                        processTouchToRead(e.getX(), e.getY());
                    }
                    return true; // Confirmamos que procesamos el toque
                })
                .load();

        // Abrir el documento PDFBox de una vez para todo el proceso
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(pdfUri);
                document = PDDocument.load(is);
            } catch (Exception e) {
                Log.e("PDF_LOAD", "Error abriendo documento: " + e.getMessage());
            }
        });
    }

    // Se dispara cuando el usuario desliza a otra página
    @Override
    public void onPageChanged(int page, int pageCount) {
        // Ya no detenemos el TTS aquí.
        // Solo ocultamos el resaltado si el usuario está viendo otra página
        if (page != readingPage) {
            highlightView.setHighlight(null);
        } else {
            // Si regresa a la página que se está leyendo, el resaltado volverá
            // automáticamente en el siguiente 'onRangeStart'
        }

        // Seguimos preparando los datos de la página que el usuario está viendo
        preparePageData(page);
    }

    private void preparePageData(int page) {
        if (isAnalyzing || document == null) return;
        isAnalyzing = true;

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                detective = new PDFWordDetective();
                detective.setStartPage(page + 1);
                detective.setEndPage(page + 1);
                detective.getText(document);
                isAnalyzing = false;
            } catch (Exception e) {
                isAnalyzing = false;
            }
        });
    }

    private void processTouchToRead(float x, float y) {
        int targetPage = pdfView.getCurrentPage();

        // Si el detective no tiene los datos de la página que estás viendo, los extraemos RÁPIDO
        if (detective == null || readingPage != targetPage) {
            Toast.makeText(this, "Sincronizando página...", Toast.LENGTH_SHORT).show();

            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.execute(() -> {
                try {
                    PDFWordDetective tempDetective = new PDFWordDetective();
                    tempDetective.setStartPage(targetPage + 1);
                    tempDetective.setEndPage(targetPage + 1);
                    tempDetective.getText(document);

                    runOnUiThread(() -> {
                        detective = tempDetective;
                        readingPage = targetPage;
                        // Ahora que tenemos los datos, re-intentamos el salto
                        executeJump(x, y);
                    });
                } catch (Exception e) {
                    Log.e("JUMP", "Error: " + e.getMessage());
                }
            });
        } else {
            executeJump(x, y);
        }
    }

    private void executeJump(float x, float y) {
        try {
            float pdfWidth = document.getPage(pdfView.getCurrentPage()).getMediaBox().getWidth();
            float scaleFactor = (float) pdfView.getWidth() / pdfWidth;

            int wordIndex = highlightView.getWordIndexAt(
                    x, y, scaleFactor, pdfView.getZoom(),
                    pdfView.getCurrentXOffset(), pdfView.getCurrentYOffset(),
                    detective.wordCoordinates
            );

            if (wordIndex != -1) {
                startReadingFromWord(wordIndex);
            }
        } catch (Exception e) {
            Log.e("JUMP", "Error en executeJump: " + e.getMessage());
        }
    }


    private void startReadingFromWord(int wordIndex) {
        if (tts != null && detective != null) {
            tts.stop();
            startWordIndexForCurrentSpeech = wordIndex;

            StringBuilder text = new StringBuilder();
            for (int i = wordIndex; i < detective.wordCoordinates.size(); i++) {
                text.append(detective.wordCoordinates.get(i).text).append(" ");
            }

            tts.speak(text.toString(), TextToSpeech.QUEUE_FLUSH, null, "SPEECHIFY_ID");
        }
    }

    private void analyzePageAndSpeak(int page, int wordIndex) {
        if (document == null) return;
        isAnalyzing = true;
        readingPage = page;
        startWordIndexForCurrentSpeech = wordIndex;

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                // Analizar la página específica
                detective = new PDFWordDetective();
                detective.setStartPage(page + 1);
                detective.setEndPage(page + 1);
                detective.getText(document);
                isAnalyzing = false;

                startReadingFromWord(wordIndex);

            } catch (Exception e) {
                isAnalyzing = false;
                Log.e("READER", "Error analizando página: " + e.getMessage());
            }
        });
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(new Locale("es", "ES"));
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {}

                @Override
                public void onDone(String utteranceId) {
                    // Al terminar la página, saltar a la siguiente
                    runOnUiThread(() -> {
                        if (readingPage < pdfView.getPageCount() - 1) {
                            int nextPage = readingPage + 1;
                            pdfView.jumpTo(nextPage);
                            // La lectura de la siguiente página se dispara desde onPageChanged o manualmente:
                            analyzePageAndSpeak(nextPage, 0);
                        }
                    });
                }

                @Override
                public void onError(String utteranceId) {}

                @Override
                public void onRangeStart(String utteranceId, int start, int end, int frame) {
                    updateHighlight(start);
                }
            });
        }
    }

    private void updateHighlight(int charOffset) {
        if (detective == null || isAnalyzing) return;

        int currentChars = 0;
        // IMPORTANTE: El charOffset del TTS empieza en 0 para el nuevo texto enviado.
        // Por eso iteramos desde startWordIndexForCurrentSpeech.
        for (int i = startWordIndexForCurrentSpeech; i < detective.wordCoordinates.size(); i++) {
            PDFWordDetective.WordCoordinate word = detective.wordCoordinates.get(i);
            int wordLength = word.text.length() + 1;

            if (charOffset >= currentChars && charOffset < currentChars + wordLength) {
                drawHighlightOnUI(word);
                break;
            }
            currentChars += wordLength;
        }
    }

    private void drawHighlightOnUI(PDFWordDetective.WordCoordinate word) {
        runOnUiThread(() -> {
            if (pdfView.getCurrentPage() != readingPage) {
                highlightView.setHighlight(null);
                return;
            }
            try {
                float pdfWidth = document.getPage(readingPage).getMediaBox().getWidth();
                float scaleFactor = (float) pdfView.getWidth() / pdfWidth;
                float zoom = pdfView.getZoom();

                float screenX = (word.x * scaleFactor * zoom) + pdfView.getCurrentXOffset();
                float screenY = (word.y * scaleFactor * zoom) + pdfView.getCurrentYOffset();
                float screenW = word.width * scaleFactor * zoom;
                float screenH = word.height * scaleFactor * zoom;

                highlightView.setHighlight(new RectF(screenX, screenY, screenX + screenW, screenY + screenH));
            } catch (Exception e) {
                Log.e("DRAW", "Error: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        try { if (document != null) document.close(); } catch (Exception e) {}
        super.onDestroy();
    }
}