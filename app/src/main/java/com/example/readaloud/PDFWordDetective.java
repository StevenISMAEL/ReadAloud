package com.example.readaloud;

import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Esta clase es nuestro "detective". Hereda de PDFTextStripper para
 * capturar las coordenadas (X, Y) de cada palabra mientras las lee.
 */
public class PDFWordDetective extends PDFTextStripper {

    // Una lista que guardará cada palabra con su posición exacta
    public List<WordCoordinate> wordCoordinates = new ArrayList<>();

    public PDFWordDetective() throws IOException {
        super();
    }

    /**
     * Este método se dispara automáticamente por cada letra del PDF.
     * Nosotros lo usamos para agrupar letras en palabras y guardar sus coordenadas.
     */
    @Override
    protected void writeString(String string, List<TextPosition> textPositions) throws IOException {
        if (!textPositions.isEmpty()) {
            TextPosition firstChar = textPositions.get(0);
            TextPosition lastChar = textPositions.get(textPositions.size() - 1);

            // Usamos las coordenadas ajustadas a la página (Adj)
            float x = firstChar.getXDirAdj();
            float y = firstChar.getYDirAdj() - firstChar.getHeightDir();
            float w = (lastChar.getXDirAdj() + lastChar.getWidthDirAdj()) - x;
            float h = firstChar.getHeightDir();

            wordCoordinates.add(new WordCoordinate(string, x, y, w, h));
        }
    }

    // Clase interna para representar una palabra en el espacio
    public static class WordCoordinate {
        public String text;
        public float x, y, width, height;

        public WordCoordinate(String text, float x, float y, float width, float height) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }
}