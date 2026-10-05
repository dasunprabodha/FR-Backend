package lk.cf.fr.monolith.image;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * Renders the first page of an uploaded PDF to PNG bytes, so a scanned NIC that arrives as a PDF
 * can enter the same pipeline as a JPEG or PNG upload.
 *
 * <p>Tellers routinely receive a scanned NIC as a PDF - it is what office scanners and
 * "scan to email" produce by default - but Rekognition's image APIs accept JPEG and PNG only.
 * Converting here means {@code DetectText} (NIC OCR), {@code CardDetectorService}'s crop and
 * {@code CompareFaces} never learn that a PDF was involved.
 *
 * <p><b>PNG, not JPEG.</b> The output feeds OCR of small printed card text, and JPEG's blocking
 * artefacts land hardest on exactly that: high-contrast text edges. A rendered page is also
 * flat-toned line art, which PNG compresses well. {@link RekognitionImageNormaliser} will
 * transcode and downscale afterwards if the result exceeds Rekognition's size limit, so nothing
 * here needs to trade quality away up front against a byte budget.
 *
 * <p><b>First page only.</b> The pipeline consumes exactly one scanned-NIC image, and for a
 * two-sided scan the front - carrying the photo and the document number - is page one. A
 * multi-page upload is rendered from page one and logged, rather than rejected: rejecting would
 * fail the common "front and back on two pages" scan for no benefit, since the back is not used.
 */
@Slf4j
public final class PdfPageRasteriser {

    /**
     * Render resolution. A NIC occupies a fraction of an A4 scan, so this is chosen for the size
     * of the card region rather than the page: at 200 DPI an A4 page is ~1654x2339, which leaves a
     * credit-card-sized region around 670x420 - comfortably enough for OCR of the document number.
     * Raising it further mostly buys bytes that {@link RekognitionImageNormaliser} then has to
     * throw away again to fit Rekognition's 5 MB inline limit.
     */
    private static final float RENDER_DPI = 200f;

    /**
     * Hard ceiling on either rendered dimension, independent of DPI. A PDF declares its own page
     * size, and nothing stops an uploaded one from declaring a vast MediaBox; without this, DPI
     * alone would decide the allocation and a hostile or simply broken file could ask for an image
     * far larger than heap. Above this size the effective DPI is reduced to fit.
     */
    private static final int MAX_DIMENSION = 4000;

    private PdfPageRasteriser() {
    }

    /**
     * @param pdfBytes bytes already confirmed to start with {@code %PDF}
     * @param partName multipart part name, used in error messages so the operator knows which
     *                 upload to replace
     * @return PNG bytes of the first page
     * @throws IllegalArgumentException if the PDF is encrypted, damaged, empty or cannot be
     *                                  rendered; mapped to HTTP 400 by {@code GlobalExceptionHandler}
     */
    public static byte[] renderFirstPageToPng(byte[] pdfBytes, String partName) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw new IllegalArgumentException("The uploaded '" + partName
                        + "' PDF has no pages. Please upload a scan that contains the card.");
            }
            if (pageCount > 1) {
                log.info("[PdfRasterise] {} has {} pages - rendering page 1 only "
                        + "(the pipeline uses a single scanned-NIC image)", partName, pageCount);
            }

            float scale = scaleFor(document.getPage(0));
            BufferedImage image = new PDFRenderer(document)
                    .renderImage(0, scale, ImageType.RGB);

            byte[] png = toPng(image, partName);
            log.info("[PdfRasterise] {} rendered page 1 of {} at scale {} -> PNG ({}x{}, {} bytes)",
                    partName, pageCount, String.format("%.2f", scale),
                    image.getWidth(), image.getHeight(), png.length);
            return png;
        } catch (InvalidPasswordException e) {
            throw new IllegalArgumentException("The uploaded '" + partName + "' PDF is password-protected. "
                    + "Please remove the password, or upload the scan as a JPEG or PNG image.", e);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // Covers a damaged/truncated PDF, an unrenderable page, and OOM-adjacent render errors.
            log.warn("[PdfRasterise] {} could not be rendered: {}", partName, e.toString());
            throw new IllegalArgumentException("The uploaded '" + partName + "' PDF could not be read. "
                    + "It may be damaged - try re-scanning it, or upload the scan as a JPEG or PNG image.", e);
        }
    }

    /**
     * Converts {@link #RENDER_DPI} to a PDFBox render scale (PDF user space is 72 units/inch),
     * then clamps it so neither rendered dimension exceeds {@link #MAX_DIMENSION}.
     */
    private static float scaleFor(PDPage page) {
        float scale = RENDER_DPI / 72f;

        PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
        // Rotation swaps the rendered axes, but the clamp only cares about the larger edge, which
        // rotation leaves unchanged - so the un-rotated box is enough here.
        float longestEdge = Math.max(box.getWidth(), box.getHeight());
        if (longestEdge <= 0) {
            throw new IllegalArgumentException("The uploaded PDF's first page has no usable size.");
        }

        float maxScale = MAX_DIMENSION / longestEdge;
        if (scale > maxScale) {
            log.info("[PdfRasterise] Page is {}x{}pt - capping render scale {} -> {} to stay within {}px",
                    Math.round(box.getWidth()), Math.round(box.getHeight()),
                    String.format("%.2f", scale), String.format("%.2f", maxScale), MAX_DIMENSION);
            scale = maxScale;
        }
        return scale;
    }

    private static byte[] toPng(BufferedImage image, String partName) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IllegalArgumentException("The uploaded '" + partName
                    + "' PDF could not be converted to an image. Please upload it as a JPEG or PNG instead.");
        }
        return out.toByteArray();
    }
}
