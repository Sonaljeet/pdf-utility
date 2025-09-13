package com.pdfUtility.service;

import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Image;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import jakarta.annotation.PostConstruct;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import net.sourceforge.tess4j.util.LoadLibs;
import nu.pattern.OpenCV;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PdfTextService {

    @PostConstruct
    public void initOpenCv() {
        // Load the bundled OpenCV native libraries (openpnp/opencv)
        OpenCV.loadLocally();
    }

    // ---------------- TEXT EXTRACTION / OCR ----------------

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
            tesseract.setDatapath(LoadLibs.extractTessResources("tessdata").getAbsolutePath());
            tesseract.setLanguage("eng");
            tesseract.setPageSegMode(6);

            for (int page = 0; page < document.getNumberOfPages(); ++page) {
                BufferedImage image = pdfRenderer.renderImageWithDPI(page, 300);
                BufferedImage gray = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
                Graphics2D g = gray.createGraphics();
                g.drawImage(image, 0, 0, null);
                g.dispose();

                String ocrResult = tesseract.doOCR(gray);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "png", baos);
                String base64Image = Base64.getEncoder().encodeToString(baos.toByteArray());

                result.append("<h3>Page ").append(page + 1).append("</h3>");
                result.append("<img src='data:image/png;base64,").append(base64Image)
                        .append("' style='max-width: 100%; height: auto; border:1px solid #ccc;'>\n");
                result.append("<pre style='background:#f9f9f9;border:1px solid #ccc;padding:10px;'>")
                        .append(escapeHtml(ocrResult)).append("</pre>\n");

                PDPage pdPage = document.getPage(page);
                PDResources pdResources = pdPage.getResources();
                for (COSName xObjectName : pdResources.getXObjectNames()) {
                    PDXObject xObject = pdResources.getXObject(xObjectName);
                    if (xObject instanceof PDImageXObject) {
                        result.append("<p><strong>Detected image object on page ").append(page + 1).append("</strong></p>\n");
                    }
                }
            }
        }
        return result.toString();
    }

    // simple HTML escape for OCR output
    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------- TEXT COMPARISON ----------------

    public String compareText(String text1, String text2, String ignorePatternsText) {
        List<String> ignorePatterns = parseIgnorePatterns(ignorePatternsText);

        for (String pattern : ignorePatterns) {
            text1 = text1.replaceAll(pattern, "");
            text2 = text2.replaceAll(pattern, "");
        }

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

    // ---------------- OCR WORD-LEVEL COMPARISON (optional) ----------------

    public void compareOcrFromTwoPdfs(MultipartFile pdf1, MultipartFile pdf2) throws Exception {
        try (PDDocument doc1 = PDDocument.load(pdf1.getInputStream());
             PDDocument doc2 = PDDocument.load(pdf2.getInputStream())) {

            int pageCount = Math.min(doc1.getNumberOfPages(), doc2.getNumberOfPages());

            PDFRenderer renderer1 = new PDFRenderer(doc1);
            PDFRenderer renderer2 = new PDFRenderer(doc2);

            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath(LoadLibs.extractTessResources("tessdata").getAbsolutePath());
            tesseract.setLanguage("eng");
            tesseract.setPageSegMode(ITessAPI.TessPageSegMode.PSM_AUTO);

            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                BufferedImage image1 = toGrayscale(renderer1.renderImageWithDPI(pageIndex, 300));
                BufferedImage image2 = toGrayscale(renderer2.renderImageWithDPI(pageIndex, 300));

                List<Word> words1 = tesseract.getWords(image1, ITessAPI.TessPageIteratorLevel.RIL_WORD);
                List<Word> words2 = tesseract.getWords(image2, ITessAPI.TessPageIteratorLevel.RIL_WORD);

                Set<String> normalizedWords2 = normalizedWord(words2);
                // Create image to draw over PDF1
                createImageForMismatch(image1, words1, normalizedWords2, pageIndex, "PDF1");
                // Create image to draw over PDF2
                Set<String> normalizedWords1 = normalizedWord(words1);
                createImageForMismatch(image2, words2, normalizedWords1, pageIndex, "PDF2");
                mergeImage("ocr_diff_page_PDF1_" + (pageIndex + 1) + ".png", "ocr_diff_page_PDF2_" + (pageIndex + 1) + ".png", pageIndex);
            }

            // After OCR diffs, also run visual image diff (OpenCV)
            // (This will produce a highlighted diff PDF and return bytes if you choose to call that method)
            byte[] highlightedPdf = comparePdfImagesWithOpenCV(pdf1, pdf2, 0.95);

            String downloadsPath = System.getProperty("user.home") + File.separator + "Downloads";
            File downloadsDir = new File(downloadsPath);

            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs();
            }

            File outputFile = new File(downloadsDir, "highlighted_diff.pdf");

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(highlightedPdf);
                System.out.println("✅ Highlighted PDF saved at: " + outputFile.getAbsolutePath());
            }


        }
    }

    private BufferedImage toGrayscale(BufferedImage original) {
        BufferedImage gray = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(original, 0, 0, null);
        g.dispose();
        return gray;
    }

    private Set<String> normalizedWord(List<Word> words) {
        return words.stream()
                .map(w -> normalizeText(w.getText()))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private void createImageForMismatch(BufferedImage image, List<Word> words, Set<String> normalizedWords, int pageIndex, String filePartialName) throws IOException {
        BufferedImage output = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.setColor(Color.RED);
        g.setStroke(new BasicStroke(2));

        boolean anyMark = false;
        for (Word word : words) {
            String ocrWord1 = normalizeText(word.getText());
            if (ocrWord1.isEmpty()) continue;

            boolean matchFound = normalizedWords.stream()
                    .anyMatch(w2 -> similarity(w2, ocrWord1) >= 0.85);

            if (!matchFound) {
                Rectangle rect = word.getBoundingBox();
                // Note: bounding box coordinates from Tesseract are usually top-left origin;
                // adjust if necessary depending on output orientation.
                g.drawRect(rect.x, rect.y, rect.width, rect.height);
                anyMark = true;
            }
        }
        if (anyMark) {
            String fileName = "ocr_diff_page_" + filePartialName + "_" + (pageIndex + 1) + ".png";
            ImageIO.write(output, "png", new File(fileName));
        }
        g.dispose();
    }

    private String normalizeText(String input) {
        if (input == null) return "";
        return input
                .replaceAll("[^\\p{L}\\p{Nd}]", "")  // keep only letters and digits
                .toLowerCase()
                .trim();
    }

    private double similarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        int maxLength = Math.max(s1.length(), s2.length());
        if (maxLength == 0) return 1.0;
        int distance = levenshteinDistance(s1, s2);
        return (1.0 - (double) distance / maxLength);
    }

    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) {
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) dp[i][j] = j;
                else if (j == 0) dp[i][j] = i;
                else dp[i][j] = s1.charAt(i - 1) == s2.charAt(j - 1)
                            ? dp[i - 1][j - 1]
                            : 1 + Math.min(Math.min(dp[i - 1][j], dp[i][j - 1]), dp[i - 1][j - 1]);
            }
        }
        return dp[s1.length()][s2.length()];
    }

    private void mergeImage(String imageFile1, String imageFile2, int pageIndex) throws IOException {
        File file1 = new File(imageFile1);
        File file2 = new File(imageFile2);
        if (file1.exists() && file2.exists()) {
            BufferedImage image1 = ImageIO.read(file1);
            BufferedImage image2 = ImageIO.read(file2);
            // Calculate dimensions for the combined image
            int width = image1.getWidth() + image2.getWidth();
            int height = Math.max(image1.getHeight(), image2.getHeight());

            // Create a new image with the calculated dimensions and type
            BufferedImage mergedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

            // Draw both images side-by-side
            Graphics2D g2d = mergedImage.createGraphics();
            g2d.drawImage(image1, 0, 0, null); // Draw first image at (0, 0)
            g2d.drawImage(image2, image1.getWidth(), 0, null); // Draw second image next to the first
            g2d.dispose();

            // Save the merged image
            ImageIO.write(mergedImage, "png", new File("diff_page_" + (pageIndex + 1) + ".png"));
            // cleanup temp files
            file1.delete();
            file2.delete();
        }
    }

    private String encodeImageToBase64(BufferedImage image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private String generateLineDiffs(String text1, String text2) {
        String[] lines1 = text1.split("\n");
        String[] lines2 = text2.split("\n");
        int max = Math.max(lines1.length, lines2.length);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < max; i++) {
            String l1 = i < lines1.length ? lines1[i] : "";
            String l2 = i < lines2.length ? lines2[i] : "";
            if (!l1.equals(l2)) {
                result.append("❌ Line ").append(i + 1).append(":\n")
                        .append("PDF1: ").append(l1).append("\n")
                        .append("PDF2: ").append(l2).append("\n\n");
            }
        }
        return result.toString();
    }

    public String verifyMultipleTextPresence(String extractedText, String imageText, String userInput, String ignorePatternsText) {
        List<String> ignorePatterns = parseIgnorePatterns(ignorePatternsText);

        for (String pattern : ignorePatterns) {
            extractedText = extractedText.replaceAll(pattern, "");
            imageText = imageText.replaceAll(pattern, "");
        }

        StringBuilder result = new StringBuilder();
        String[] terms = userInput.split(",");
        for (String term : terms) {
            String trimmed = term.trim();
            boolean inText = extractedText.toLowerCase().contains(trimmed.toLowerCase());
            boolean inOCR = imageText.toLowerCase().contains(trimmed.toLowerCase());

            if (inText && inOCR)
                result.append("✅ '").append(trimmed).append("' found in both PDF text and OCR.\n");
            else if (inText)
                result.append("✅ '").append(trimmed).append("' found in PDF text.\n");
            else if (inOCR)
                result.append("✅ '").append(trimmed).append("' found in OCR.\n");
            else
                result.append("❌ '").append(trimmed).append("' not found in PDF.\n");
        }
        return result.toString();
    }

    private List<String> parseIgnorePatterns(String ignorePatternsText) {
        List<String> patterns = new ArrayList<>();
        if (ignorePatternsText != null && !ignorePatternsText.trim().isEmpty()) {
            String[] parts = ignorePatternsText.split("[,\\r?\\n]+");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    patterns.add(trimmed);
                }
            }
        }
        return patterns;
    }

    // ---------------- EXPORT PDF ----------------

    public byte[] generateSideBySideComparisonPdf(MultipartFile file1, MultipartFile file2, String ignorePatterns) throws Exception {
        String text1 = extractText(file1);
        String text2 = extractText(file2);
        List<String> patterns = parseIgnorePatterns(ignorePatterns);

        for (String pattern : patterns) {
            text1 = text1.replaceAll(pattern, "");
            text2 = text2.replaceAll(pattern, "");
        }

        String[] lines1 = text1.split("\n");
        String[] lines2 = text2.split("\n");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, baos);
        document.open();

        PdfPTable table = new PdfPTable(2);
        table.setWidths(new int[]{1, 1});

        for (int i = 0; i < Math.max(lines1.length, lines2.length); i++) {
            String l1 = i < lines1.length ? lines1[i] : "";
            String l2 = i < lines2.length ? lines2[i] : "";

            PdfPCell cell1 = new PdfPCell(new Paragraph(l1));
            PdfPCell cell2 = new PdfPCell(new Paragraph(l2));

            table.addCell(cell1);
            table.addCell(cell2);
        }

        document.add(new Paragraph("Side-by-Side PDF Comparison"));
        document.add(table);
        document.close();

        return baos.toByteArray();
    }

    public byte[] generateComparisonPdf(String comparisonText) {
        Document document = new Document();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter.getInstance(document, out);
            document.open();
            document.add(new Paragraph("Comparison Result:"));
            document.add(new Paragraph(comparisonText));
            document.close();
        } catch (DocumentException e) {
            throw new RuntimeException("Error generating PDF", e);
        }
        return out.toByteArray();
    }

    // ---------------- OPENCV VISUAL DIFF ----------------

    /**
     * Compare two PDFs visually (graphs, charts, pictures) using OpenCV and generate highlighted diff PDF.
     */
    /**
     * Compare two PDFs visually (graphs, charts, pictures) using OpenCV
     * and generate highlighted diff PDF + SSIM score + threshold check.
     */
    /**
     * Compare two PDFs visually (graphs, charts, pictures) using region-wise SSIM
     * and generate a highlighted diff PDF.
     *
     * @param file1 first PDF
     * @param file2 second PDF
     * @param ssimThreshold threshold below which regions are considered "different"
     * @return PDF with highlighted differences
     */
    public byte[] comparePdfImagesWithOpenCV(MultipartFile file1, MultipartFile file2, double ssimThreshold) throws Exception {
        try (PDDocument doc1 = PDDocument.load(file1.getInputStream());
             PDDocument doc2 = PDDocument.load(file2.getInputStream())) {

            PDFRenderer renderer1 = new PDFRenderer(doc1);
            PDFRenderer renderer2 = new PDFRenderer(doc2);

            int pageCount = Math.max(doc1.getNumberOfPages(), doc2.getNumberOfPages());

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document outputDoc = new Document();
            PdfWriter.getInstance(outputDoc, baos);
            outputDoc.open();

            for (int i = 0; i < pageCount; i++) {
                BufferedImage img1 = i < doc1.getNumberOfPages() ? renderer1.renderImageWithDPI(i, 200) : null;
                BufferedImage img2 = i < doc2.getNumberOfPages() ? renderer2.renderImageWithDPI(i, 200) : null;

                if (img1 == null || img2 == null) continue;

                // Resize to same size
                if (img1.getWidth() != img2.getWidth() || img1.getHeight() != img2.getHeight()) {
                    BufferedImage resized = new BufferedImage(img1.getWidth(), img1.getHeight(), img2.getType());
                    Graphics2D g = resized.createGraphics();
                    g.drawImage(img2, 0, 0, img1.getWidth(), img1.getHeight(), null);
                    g.dispose();
                    img2 = resized;
                }

                // Convert to OpenCV Mat
                Mat mat1 = bufferedImageToMat(img1);
                Mat mat2 = bufferedImageToMat(img2);

                // Convert to grayscale
                Mat gray1 = new Mat();
                Mat gray2 = new Mat();
                Imgproc.cvtColor(mat1, gray1, Imgproc.COLOR_BGR2GRAY);
                Imgproc.cvtColor(mat2, gray2, Imgproc.COLOR_BGR2GRAY);

                // Sliding window region-wise SSIM
                int windowSize = 50; // region size (adjust for sensitivity)
                BufferedImage highlighted = deepCopy(img1);
                Graphics2D g2 = highlighted.createGraphics();
                g2.setColor(Color.RED);
                g2.setStroke(new BasicStroke(3));

                for (int y = 0; y < gray1.rows(); y += windowSize) {
                    for (int x = 0; x < gray1.cols(); x += windowSize) {
                        int w = Math.min(windowSize, gray1.cols() - x);
                        int h = Math.min(windowSize, gray1.rows() - y);

                        Rect roi = new Rect(x, y, w, h);
                        Mat sub1 = new Mat(gray1, roi);
                        Mat sub2 = new Mat(gray2, roi);

                        double score = calculateSSIM(sub1, sub2);

                        if (score < ssimThreshold) {
                            g2.drawRect(x, y, w, h);
                        }
                    }
                }

                g2.dispose();

                // Add page result to PDF
                ByteArrayOutputStream pageBaos = new ByteArrayOutputStream();
                ImageIO.write(highlighted, "png", pageBaos);
                Image itextImg = Image.getInstance(pageBaos.toByteArray());
                itextImg.scaleToFit(500, 700);
                outputDoc.add(new Paragraph("Page " + (i + 1) + " Graph/Image Comparison"));
                outputDoc.add(itextImg);
                outputDoc.newPage();
            }

            outputDoc.close();
            return baos.toByteArray();
        }
    }

    /**
     * Calculate SSIM (Structural Similarity Index) between two grayscale regions.
     */
    private double calculateSSIM(Mat img1, Mat img2) {
        Mat img1f = new Mat();
        Mat img2f = new Mat();
        img1.convertTo(img1f, CvType.CV_32F);
        img2.convertTo(img2f, CvType.CV_32F);

        // Compute absolute difference
        Mat diff = new Mat();
        Core.absdiff(img1f, img2f, diff);

        // Compute mean difference
        Scalar meanScalar = Core.mean(diff);
        double meanDiff = meanScalar.val[0] / 255.0;

        // Return "SSIM-like" similarity (1 = identical, 0 = completely different)
        return 1.0 - meanDiff;
    }

    private BufferedImage deepCopy(BufferedImage bi) {
        ColorModel cm = bi.getColorModel();
        boolean isAlphaPremultiplied = cm.isAlphaPremultiplied();
        WritableRaster raster = bi.copyData(null);
        return new BufferedImage(cm, raster, isAlphaPremultiplied, null);
    }



    // Utility: convert BufferedImage -> Mat using Imgcodecs.imdecode
    private Mat bufferedImageToMat(BufferedImage bi) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(bi, "png", baos);
        byte[] bytes = baos.toByteArray();
        MatOfByte mob = new MatOfByte(bytes);
        return Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_COLOR);
    }

    // Utility: convert Mat -> BufferedImage
    private BufferedImage matToBufferedImage(Mat mat) throws IOException {
        MatOfByte mob = new MatOfByte();
        Imgcodecs.imencode(".png", mat, mob);
        byte[] byteArray = mob.toArray();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(byteArray)) {
            return ImageIO.read(bais);
        }
    }

    // Helper: create mat for img2 ensuring same channels as mat1
    private Mat bufferedimageToMatKeepingChannels(Mat mat1, BufferedImage bi2) throws IOException {
        Mat m2 = bufferedImageToMat(bi2);
        if (m2.channels() != mat1.channels()) {
            if (m2.channels() == 1 && mat1.channels() == 3) {
                Imgproc.cvtColor(m2, m2, Imgproc.COLOR_GRAY2BGR);
            } else if (m2.channels() == 3 && mat1.channels() == 1) {
                Imgproc.cvtColor(m2, m2, Imgproc.COLOR_BGR2GRAY);
                Imgproc.cvtColor(m2, m2, Imgproc.COLOR_GRAY2BGR);
            }
        }
        return m2;
    }

}
