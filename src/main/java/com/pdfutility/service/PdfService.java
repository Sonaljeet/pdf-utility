package com.pdfutility.service;

import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.InputStream;

@Service
public class PdfService {

    public String extractText(MultipartFile file) throws Exception {
        try (InputStream input = file.getInputStream();
             PDDocument document = PDDocument.load(input)) {
            return new PDFTextStripper().getText(document);
        }
    }

    public String extractImageText(MultipartFile file) throws Exception {
        StringBuilder result = new StringBuilder();
        try (InputStream input = file.getInputStream();
             PDDocument document = PDDocument.load(input)) {

            PDFRenderer pdfRenderer = new PDFRenderer(document);
            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath("tessdata"); // Path to tessdata directory
            tesseract.setLanguage("eng");

            for (int page = 0; page < document.getNumberOfPages(); ++page) {
                BufferedImage image = pdfRenderer.renderImageWithDPI(page, 300);
                String ocrResult = tesseract.doOCR(image);
                result.append("Page ").append(page + 1).append(":\n").append(ocrResult).append("\n\n");
            }
        }
        return result.toString();
    }

    public String compareText(String text1, String text2) {
        if (text1.equals(text2)) return "PDFs are identical.";
        StringBuilder diff = new StringBuilder("Differences:\n");
        String[] lines1 = text1.split("\n");
        String[] lines2 = text2.split("\n");

        int max = Math.max(lines1.length, lines2.length);
        for (int i = 0; i < max; i++) {
            String l1 = i < lines1.length ? lines1[i] : "";
            String l2 = i < lines2.length ? lines2[i] : "";
            if (!l1.equals(l2)) {
                diff.append("Line ").append(i + 1).append(":\n");
                diff.append("PDF1: ").append(l1).append("\n");
                diff.append("PDF2: ").append(l2).append("\n\n");
            }
        }
        return diff.toString();
    }

    public String verifyMultipleTextPresence(String extractedText, String imageText, String userInput) {
        StringBuilder result = new StringBuilder();
        String[] terms = userInput.split(",");
        for (String term : terms) {
            String trimmed = term.trim();
            boolean inText = extractedText.toLowerCase().contains(trimmed.toLowerCase());
            boolean inOCR = imageText.toLowerCase().contains(trimmed.toLowerCase());

            if (inText && inOCR)
                result.append("\u2705 '" + trimmed + "' found in both PDF text and OCR.\n");
            else if (inText)
                result.append("\u2705 '" + trimmed + "' found in PDF text.\n");
            else if (inOCR)
                result.append("\u2705 '" + trimmed + "' found in OCR.\n");
            else
                result.append("\u274C '" + trimmed + "' not found in PDF.\n");
        }
        return result.toString();
    }
}