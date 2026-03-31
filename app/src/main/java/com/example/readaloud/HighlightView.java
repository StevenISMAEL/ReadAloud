package com.example.readaloud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

public class HighlightView extends View {
    private Paint paint;
    private RectF highlightRect;

    public HighlightView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint = new Paint();
        paint.setColor(Color.parseColor("#40FFFF00")); // Amarillo con 25% de transparencia
        paint.setStyle(Paint.Style.FILL);
    }

    // Método para decirle al lienzo dónde dibujar el cuadro
    public void setHighlight(RectF rect) {
        this.highlightRect = rect;
        invalidate(); // Obliga a la vista a redibujarse
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (highlightRect != null) {
            canvas.drawRect(highlightRect, paint);
        }
    }
    public int getWordIndexAt(float touchX, float touchY, float scaleFactor, float zoom, float xOffset, float yOffset, List<PDFWordDetective.WordCoordinate> coordinates) {
        if (coordinates == null || coordinates.isEmpty()) return -1;

        // 1. Convertir toque a coordenadas de puntos de PDF
        float pdfX = (touchX - xOffset) / (scaleFactor * zoom);
        float pdfY = (touchY - yOffset) / (scaleFactor * zoom);

        int closestIndex = -1;
        float minDistance = Float.MAX_VALUE;

        for (int i = 0; i < coordinates.size(); i++) {
            PDFWordDetective.WordCoordinate word = coordinates.get(i);

            // Creamos un área de toque expandida para cada palabra (un "colchón" de 5 puntos)
            float padding = 5.0f;
            boolean isInsideX = pdfX >= (word.x - padding) && pdfX <= (word.x + word.width + padding);
            boolean isInsideY = pdfY >= (word.y - padding) && pdfY <= (word.y + word.height + padding);

            if (isInsideX && isInsideY) {
                // Si el toque cae dentro (o muy cerca), calculamos la distancia al centro de la palabra
                float centerX = word.x + (word.width / 2);
                float centerY = word.y + (word.height / 2);
                float distance = (float) Math.sqrt(Math.pow(pdfX - centerX, 2) + Math.pow(pdfY - centerY, 2));

                if (distance < minDistance) {
                    minDistance = distance;
                    closestIndex = i;
                }
            }
        }
        return closestIndex;
    }
}