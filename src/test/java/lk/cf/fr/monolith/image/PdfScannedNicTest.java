package lk.cf.fr.monolith.image;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the PDF branch of the scannedNIC upload: a teller's scanner produces PDFs by default, so
 * a PDF must reach the OCR/face pipeline as an ordinary image instead of being refused.
 */
class PdfScannedNicTest {

    // ---------------------------------------------------------------- helpers

    /** A PDF whose pages each carry a distinctly coloured block, so page order is checkable. */
    private static byte[] pdfWith(PDRectangle size, Color... pageColours) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Color colour : pageColours) {
                PDPage page = new PDPage(size);
                doc.addPage(page);

                BufferedImage block = new BufferedImage(200, 120, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = block.createGraphics();
                g.setColor(colour);
                g.fillRect(0, 0, 200, 120);
                g.dispose();

                PDImageXObject image = LosslessFactory.createFromImage(doc, block);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.drawImage(image, 40, size.getHeight() - 200, 300, 180);
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static BufferedImage decode(byte[] bytes) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(img, "output should be a decodable image");
        return img;
    }

    /** Average colour of the region the test PDF paints its block into. */
    private static Color sampleBlock(BufferedImage img) {
        return new Color(img.getRGB((int) (img.getWidth() * 0.25), (int) (img.getHeight() * 0.10)));
    }

    private static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
    }

    // ---------------------------------------------------------------- tests

    @Nested
    @DisplayName("normalise() accepts a PDF upload")
    class AcceptsPdf {

        @Test
        void rendersAnA4PdfToAnImageRekognitionCanRead() throws Exception {
            byte[] pdf = pdfWith(PDRectangle.A4, Color.RED);

            byte[] out = RekognitionImageNormaliser.normalise(pdf, "scannedNIC");

            assertNotNull(out);
            assertTrue(isPng(out) || isJpeg(out), "Rekognition accepts JPEG/PNG only");
            BufferedImage img = decode(out);
            // A4 at 200 DPI is ~1654x2339; assert the ballpark, not the exact rounding.
            assertTrue(img.getWidth() > 1500 && img.getWidth() < 1800,
                    "unexpected width for A4 @200dpi: " + img.getWidth());
            assertTrue(img.getHeight() > 2200 && img.getHeight() < 2500,
                    "unexpected height for A4 @200dpi: " + img.getHeight());
        }

        @Test
        void keepsEnoughResolutionForOcrOfTheCardRegion() throws Exception {
            byte[] out = RekognitionImageNormaliser.normalise(pdfWith(PDRectangle.A4, Color.RED), "scannedNIC");
            BufferedImage img = decode(out);

            // A NIC is ~86mm wide on a 210mm page, so it lands on ~41% of the page width. OCR of
            // the document number needs that region to stay well above card-at-72dpi (~244px).
            int cardRegionWidth = (int) (img.getWidth() * 0.41);
            assertTrue(cardRegionWidth > 600,
                    "card region would be only " + cardRegionWidth + "px wide - too coarse for OCR");
        }

        @Test
        void rendersTheFirstPageOfAMultiPageScan() throws Exception {
            // Front/back scans arrive as two pages; the front (page 1) is the one with the photo.
            byte[] pdf = pdfWith(PDRectangle.A4, Color.RED, Color.BLUE);

            BufferedImage img = decode(RekognitionImageNormaliser.normalise(pdf, "scannedNIC"));

            Color sampled = sampleBlock(img);
            assertTrue(sampled.getRed() > 150 && sampled.getBlue() < 100,
                    "expected page 1's red block, got " + sampled);
        }
    }

    @Nested
    @DisplayName("normalise() rejects unusable PDFs with an actionable message")
    class RejectsBadPdf {

        @Test
        void rejectsADamagedPdf() {
            byte[] notReallyAPdf = "%PDF-1.7\nthis is not a real pdf body".getBytes(StandardCharsets.US_ASCII);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> RekognitionImageNormaliser.normalise(notReallyAPdf, "scannedNIC"));

            assertTrue(e.getMessage().contains("scannedNIC"), "message should name the part: " + e.getMessage());
            assertTrue(e.getMessage().toLowerCase().contains("damaged")
                            || e.getMessage().toLowerCase().contains("could not be read"),
                    "message should be actionable: " + e.getMessage());
        }

        @Test
        void rejectsAPdfWithNoPages() throws Exception {
            try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out);
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> RekognitionImageNormaliser.normalise(out.toByteArray(), "scannedNIC"));
                assertTrue(e.getMessage().contains("no pages"), e.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("render sizing is bounded")
    class Bounded {

        @Test
        void clampsAnAbsurdlyLargePageInsteadOfAllocatingForIt() throws Exception {
            // 200 DPI on a 14400pt page would be 40000px on the long edge - ~4.8 GB of pixels.
            byte[] pdf = pdfWith(new PDRectangle(14400f, 14400f), Color.RED);

            BufferedImage img = decode(RekognitionImageNormaliser.normalise(pdf, "scannedNIC"));

            assertTrue(img.getWidth() <= 4000 && img.getHeight() <= 4000,
                    "render should be capped, got " + img.getWidth() + "x" + img.getHeight());
        }
    }

    @Nested
    @DisplayName("non-PDF handling is unchanged")
    class NonPdfUnchanged {

        @Test
        void passesJpegThroughByteForByte() throws Exception {
            BufferedImage src = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            ImageIO.write(src, "jpg", jpeg);
            byte[] original = jpeg.toByteArray();

            assertSame(original, RekognitionImageNormaliser.normalise(original, "scannedNIC"),
                    "JPEG must not be re-encoded - the frozen baseline was measured on these bytes");
        }

        @Test
        void stillReturnsNullForNoUpload() {
            assertNull(RekognitionImageNormaliser.normalise(null, "scannedNIC"));
            assertNull(RekognitionImageNormaliser.normalise(new byte[0], "scannedNIC"));
        }
    }
}
