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
            byte[] highlightedPdf = comparePdfImagesWithOpenCV(pdf1, pdf2, 0.5);

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
    public byte[] comparePdfImagesWithOpenCV(MultipartFile file1, MultipartFile file2, double threshold) throws Exception {
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

                // Convert to OpenCV Mat
                Mat mat1 = bufferedImageToMat(img1);
                Mat mat2 = bufferedImageToMat(img2);

                // Resize to same size
                Imgproc.resize(mat2, mat2, mat1.size());

                // --- Step 1: SSIM Score ---
                double ssimScore = calculateSSIM(mat1, mat2);
                boolean different = ssimScore < threshold;

                System.out.println("Page " + (i + 1) + " SSIM score: " + ssimScore +
                        " | Different: " + different);

                // --- Step 2: Absolute diff for bounding box visualization ---
                Mat diff = new Mat();
                Core.absdiff(mat1, mat2, diff);
                Imgproc.cvtColor(diff, diff, Imgproc.COLOR_BGR2GRAY);
                Imgproc.threshold(diff, diff, 30, 255, Imgproc.THRESH_BINARY);

                // Find contours (difference regions)
                List<MatOfPoint> contours = new ArrayList<>();
                Imgproc.findContours(diff, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

                // Draw bounding boxes on first image if marked different
                if (different) {
                    for (MatOfPoint contour : contours) {
                        Rect rect = Imgproc.boundingRect(contour);
                        Imgproc.rectangle(mat1, rect, new Scalar(0, 0, 255), 3);
                    }
                }

                // Convert back to BufferedImage
                BufferedImage highlighted = matToBufferedImage(mat1);

                // Add to output PDF
                ByteArrayOutputStream pageBaos = new ByteArrayOutputStream();
                ImageIO.write(highlighted, "png", pageBaos);
                Image itextImg = Image.getInstance(pageBaos.toByteArray());
                itextImg.scaleToFit(500, 700);

                outputDoc.add(new Paragraph("Page " + (i + 1) + " Graph/Image Comparison"));
                outputDoc.add(new Paragraph("SSIM Score: " + String.format("%.4f", ssimScore) +
                        " | Threshold: " + threshold +
                        " | Different: " + (different ? "YES ❌" : "NO ✅")));
                outputDoc.add(itextImg);
                outputDoc.newPage();
            }

            outputDoc.close();
            return baos.toByteArray();
        }
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

    /**
     * Compute Structural Similarity Index (SSIM) between two images (grayscale).
     * Returns value in [0,1] where 1.0 = identical, lower = more different.
     */
    private double calculateSSIM(Mat img1, Mat img2) {
        // Convert to grayscale Mats
        Mat gray1 = new Mat();
        Mat gray2 = new Mat();
        Imgproc.cvtColor(img1, gray1, Imgproc.COLOR_BGR2GRAY);
        Imgproc.cvtColor(img2, gray2, Imgproc.COLOR_BGR2GRAY);

        // Ensure same size
        if (!gray1.size().equals(gray2.size())) {
            Imgproc.resize(gray2, gray2, gray1.size());
        }

        // Convert to float32
        Mat I1 = new Mat();
        Mat I2 = new Mat();
        gray1.convertTo(I1, CvType.CV_32F);
        gray2.convertTo(I2, CvType.CV_32F);

        // Gaussian blur (to obtain local means)
        Mat mu1 = new Mat();
        Mat mu2 = new Mat();
        Size winSize = new Size(11, 11);
        double sigma = 1.5;
        Imgproc.GaussianBlur(I1, mu1, winSize, sigma);
        Imgproc.GaussianBlur(I2, mu2, winSize, sigma);

        // mu^2, mu1*mu2
        Mat mu1_sq = new Mat();
        Mat mu2_sq = new Mat();
        Mat mu1_mu2 = new Mat();
        Core.multiply(mu1, mu1, mu1_sq);
        Core.multiply(mu2, mu2, mu2_sq);
        Core.multiply(mu1, mu2, mu1_mu2);

        // sigma^2, sigma12
        Mat sigma1_sq = new Mat();
        Mat sigma2_sq = new Mat();
        Mat sigma12 = new Mat();

        Mat temp1 = new Mat();
        Mat temp2 = new Mat();

        Imgproc.GaussianBlur(I1.mul(I1), temp1, winSize, sigma);
        Core.subtract(temp1, mu1_sq, sigma1_sq);

        Imgproc.GaussianBlur(I2.mul(I2), temp2, winSize, sigma);
        Core.subtract(temp2, mu2_sq, sigma2_sq);

        Imgproc.GaussianBlur(I1.mul(I2), temp1, winSize, sigma);
        Core.subtract(temp1, mu1_mu2, sigma12);

        // Constants for SSIM (stabilize the division)
        // Common choice: (K1=0.01, K2=0.03) with L = 255 for 8-bit images
        double C1 = Math.pow(0.01 * 255, 2); // ~6.5025
        double C2 = Math.pow(0.03 * 255, 2); // ~58.5225

        // numerator = (2*mu1_mu2 + C1) * (2*sigma12 + C2)
        Mat t1 = new Mat();
        Mat t2 = new Mat();
        Mat numerator = new Mat();

        Core.multiply(mu1_mu2, new Scalar(2.0), t1);
        Core.add(t1, new Scalar(C1), t1);            // t1 = 2*mu1_mu2 + C1

        Core.multiply(sigma12, new Scalar(2.0), t2);
        Core.add(t2, new Scalar(C2), t2);            // t2 = 2*sigma12 + C2

        Core.multiply(t1, t2, numerator);            // numerator = t1 * t2

        // denominator = (mu1_sq + mu2_sq + C1) * (sigma1_sq + sigma2_sq + C2)
        Mat denom1 = new Mat();
        Mat denom2 = new Mat();
        Mat denominator = new Mat();

        Core.add(mu1_sq, mu2_sq, denom1);
        Core.add(denom1, new Scalar(C1), denom1);

        Core.add(sigma1_sq, sigma2_sq, denom2);
        Core.add(denom2, new Scalar(C2), denom2);

        Core.multiply(denom1, denom2, denominator);

        // SSIM map = numerator / denominator
        Mat ssim_map = new Mat();
        Core.divide(numerator, denominator, ssim_map);

        // mean SSIM over image
        Scalar mssim = Core.mean(ssim_map);
        double score = mssim.val[0];

        // Release mats to avoid native memory leak
        gray1.release();
        gray2.release();
        I1.release();
        I2.release();
        mu1.release();
        mu2.release();
        mu1_sq.release();
        mu2_sq.release();
        mu1_mu2.release();
        sigma1_sq.release();
        sigma2_sq.release();
        sigma12.release();
        temp1.release();
        temp2.release();
        t1.release();
        t2.release();
        numerator.release();
        denom1.release();
        denom2.release();
        denominator.release();
        ssim_map.release();

        // Clamp to [0,1] just in case of numerical issues
        if (Double.isNaN(score) || score < 0) score = 0.0;
        if (score > 1.0) score = 1.0;
        return score;
    }

}
