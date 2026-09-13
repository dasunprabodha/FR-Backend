package lk.cf.fr.monolith.image;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_COLOR;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imdecode;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMWRITE_JPEG_QUALITY;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imencode;
import static org.bytedeco.opencv.global.opencv_imgproc.INTER_AREA;
import static org.bytedeco.opencv.global.opencv_imgproc.resize;

/**
 * Guarantees that bytes handed to AWS Rekognition are in a format it actually accepts.
 *
 * <p>Rekognition's image APIs (DetectText, CompareFaces, DetectFaces) accept <b>JPEG and PNG
 * only</b>, and reject anything else with a 400 {@code InvalidImageFormatException} whose message
 * ("Request has invalid image format") names neither the offending part nor the format that was
 * actually sent. The uploaded {@code scannedNIC} part previously travelled from the multipart
 * request straight into {@code DetectText} as raw bytes, so any other image type the browser was
 * willing to hand over - HEIC/HEIF from an iPhone, WebP, BMP, TIFF, GIF, or a PDF dropped onto the
 * upload zone, which bypasses the file picker's {@code accept} filter entirely - produced that
 * opaque 400 deep inside the pipeline rather than a usable error at the boundary.
 *
 * <p><b>JPEG and PNG pass through byte-for-byte untouched.</b> That is deliberate: re-encoding
 * every upload would quietly alter the input the frozen registration baseline was measured
 * against, and JPEG is lossy, so a needless round-trip would cost OCR and face-comparison accuracy
 * on exactly the inputs that already work. Transcoding happens only for inputs that Rekognition
 * would otherwise refuse outright.
 *
 * <p>Formats OpenCV can decode (WebP, BMP, TIFF, GIF, ...) are transcoded to JPEG. HEIC/HEIF is
 * not among them - the OpenCV build bundled with javacv-platform is compiled without libheif - so
 * it is rejected with a message that says so, instead of failing obscurely one layer down.
 */
@Slf4j
public final class RekognitionImageNormaliser {

    /**
     * Rekognition's documented ceiling for inline image bytes. Exceeding it raises a separate
     * {@code ImageTooLargeException}, so oversized input is downscaled here rather than left to
     * fail for a second, unrelated-looking reason after the format problem is solved.
     */
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    /** JPEG quality for transcoded/downscaled output - high enough to keep small printed NIC text legible. */
    private static final int JPEG_QUALITY = 92;

    private RekognitionImageNormaliser() {
    }

    /**
     * @param imageBytes the uploaded bytes, or {@code null} when no file was supplied
     * @param partName   the multipart part name, used in the error message so the operator knows
     *                   which upload to replace
     * @return JPEG or PNG bytes safe to send to Rekognition, or {@code null} if {@code imageBytes} was null
     * @throws IllegalArgumentException if the bytes are not a decodable image; mapped to HTTP 400
     *                                  by {@code GlobalExceptionHandler}
     */
    public static byte[] normalise(byte[] imageBytes, String partName) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }

        ImageSignature signature = ImageSignature.sniff(imageBytes);

        if (signature.acceptedByRekognition()) {
            if (imageBytes.length <= MAX_BYTES) {
                log.debug("[ImageNormalise] {} is {} ({} bytes) - passing through unchanged",
                        partName, signature, imageBytes.length);
                return imageBytes;
            }
            return shrinkToLimit(imageBytes, partName, signature);
        }

        if (!signature.decodableByOpenCv()) {
            log.warn("[ImageNormalise] {} is {} ({} bytes) - cannot be converted, rejecting",
                    partName, signature, imageBytes.length);
            throw new IllegalArgumentException(rejectionMessage(partName, signature));
        }

        log.info("[ImageNormalise] {} is {} ({} bytes) - Rekognition accepts JPEG/PNG only, transcoding to JPEG",
                partName, signature, imageBytes.length);

        byte[] jpeg = transcodeToJpeg(imageBytes, partName, signature);
        return jpeg.length <= MAX_BYTES ? jpeg : shrinkToLimit(jpeg, partName, ImageSignature.JPEG);
    }

    private static String rejectionMessage(String partName, ImageSignature signature) {
        String base = "The uploaded '" + partName + "' file is " + signature.description() + ". ";
        return switch (signature) {
            case HEIF -> base + "iPhone HEIC/HEIF photos are not supported - export or re-save the "
                    + "image as JPEG or PNG and upload it again.";
            case PDF -> base + "Please upload a photo or scan of the card as a JPEG or PNG image, not a PDF.";
            case GIF -> base + "GIF is not supported - re-save the image as JPEG or PNG and upload it again.";
            default -> base + "Please upload a JPEG or PNG image.";
        };
    }

    private static byte[] transcodeToJpeg(byte[] imageBytes, String partName, ImageSignature signature) {
        Mat decoded = decode(imageBytes);
        try {
            if (decoded == null || decoded.empty()) {
                throw new IllegalArgumentException(rejectionMessage(partName, signature));
            }
            byte[] out = encodeJpeg(decoded);
            log.info("[ImageNormalise] {} transcoded {} -> JPEG ({} bytes -> {} bytes, {}x{})",
                    partName, signature, imageBytes.length, out.length, decoded.cols(), decoded.rows());
            return out;
        } finally {
            release(decoded);
        }
    }

    /**
     * Re-encodes at {@link #JPEG_QUALITY}, then halves the image repeatedly for as long as the
     * result still exceeds {@link #MAX_BYTES}. The re-encode alone is often enough - a
     * camera-original JPEG is usually written at a higher quality than 92 - so pixels are only
     * given up when re-compression has already failed to make room. Only ever reached by input
     * Rekognition would have rejected as too large, so it can turn a hard failure into a usable
     * image but can never degrade one that works today.
     */
    private static byte[] shrinkToLimit(byte[] imageBytes, String partName, ImageSignature signature) {
        Mat decoded = decode(imageBytes);
        try {
            if (decoded == null || decoded.empty()) {
                throw new IllegalArgumentException(rejectionMessage(partName, signature));
            }

            Mat current = decoded;
            byte[] out = encodeJpeg(current);

            while (out.length > MAX_BYTES && current.cols() > 640 && current.rows() > 640) {
                Mat smaller = new Mat();
                resize(current, smaller, new Size(current.cols() / 2, current.rows() / 2), 0, 0, INTER_AREA);
                if (current != decoded) {
                    release(current);
                }
                current = smaller;
                out = encodeJpeg(current);
            }

            boolean resized = current.cols() != decoded.cols();
            log.info("[ImageNormalise] {} exceeded Rekognition's {} byte limit - {} ({} bytes -> {} bytes, {}x{}{})",
                    partName, MAX_BYTES,
                    resized ? "downscaled and re-encoded" : "re-encoded at quality " + JPEG_QUALITY,
                    imageBytes.length, out.length, current.cols(), current.rows(),
                    resized ? ", was " + decoded.cols() + "x" + decoded.rows() : " unchanged");

            if (current != decoded) {
                release(current);
            }
            return out;
        } finally {
            release(decoded);
        }
    }

    private static Mat decode(byte[] imageBytes) {
        try (BytePointer bp = new BytePointer(imageBytes)) {
            Mat buf = new Mat(1, imageBytes.length, CV_8UC1, bp);
            Mat img = imdecode(buf, IMREAD_COLOR);
            buf.release();
            return img;
        }
    }

    private static byte[] encodeJpeg(Mat image) {
        try (BytePointer buf = new BytePointer()) {
            imencode(".jpg", image, buf, new IntPointer(IMWRITE_JPEG_QUALITY, JPEG_QUALITY));
            int len = (int) (buf.limit() > 0 ? buf.limit() : buf.capacity());
            byte[] out = new byte[len];
            buf.position(0).get(out);
            return out;
        }
    }

    private static void release(Mat mat) {
        if (mat != null && !mat.isNull()) {
            mat.release();
        }
    }

    /**
     * Container-format identification by magic bytes rather than by the client-supplied filename
     * or {@code Content-Type}, neither of which is trustworthy: a browser reports HEIC as
     * {@code image/heic} on some platforms and as an empty string on others, and a renamed
     * {@code .jpg} carries a JPEG name over non-JPEG bytes.
     */
    enum ImageSignature {
        JPEG("a JPEG image", true, true),
        PNG("a PNG image", true, true),
        WEBP("a WebP image", false, true),
        BMP("a BMP image", false, true),
        GIF("a GIF image", false, false),
        TIFF("a TIFF image", false, true),
        HEIF("an HEIC/HEIF image", false, false),
        PDF("a PDF document", false, false),
        UNKNOWN("not a recognised image format", false, true);

        private final String description;
        private final boolean acceptedByRekognition;
        private final boolean decodableByOpenCv;

        ImageSignature(String description, boolean acceptedByRekognition, boolean decodableByOpenCv) {
            this.description = description;
            this.acceptedByRekognition = acceptedByRekognition;
            this.decodableByOpenCv = decodableByOpenCv;
        }

        String description() {
            return description;
        }

        boolean acceptedByRekognition() {
            return acceptedByRekognition;
        }

        /**
         * UNKNOWN is optimistically treated as decodable so an unrecognised-but-valid container
         * still gets a real decode attempt; the attempt failing produces the same clear rejection.
         */
        boolean decodableByOpenCv() {
            return decodableByOpenCv;
        }

        static ImageSignature sniff(byte[] b) {
            if (startsWith(b, 0xFF, 0xD8, 0xFF)) {
                return JPEG;
            }
            if (startsWith(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
                return PNG;
            }
            if (startsWith(b, 0x25, 0x50, 0x44, 0x46)) { // %PDF
                return PDF;
            }
            if (startsWith(b, 0x42, 0x4D)) { // BM
                return BMP;
            }
            if (startsWith(b, 0x47, 0x49, 0x46, 0x38)) { // GIF8
                return GIF;
            }
            if (startsWith(b, 0x49, 0x49, 0x2A, 0x00) || startsWith(b, 0x4D, 0x4D, 0x00, 0x2A)) {
                return TIFF;
            }
            if (startsWith(b, 0x52, 0x49, 0x46, 0x46) && matchesAt(b, 8, 0x57, 0x45, 0x42, 0x50)) { // RIFF....WEBP
                return WEBP;
            }
            // ISO-BMFF: a 4-byte box length, then "ftyp", then a brand. HEIC/HEIF/AVIF all sit in
            // this family; the brand at offset 8 distinguishes them from MP4 and friends.
            if (matchesAt(b, 4, 0x66, 0x74, 0x79, 0x70) && b.length >= 12) {
                String brand = new String(b, 8, 4, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
                if (brand.startsWith("hei") || brand.startsWith("hev") || brand.startsWith("mif")
                        || brand.startsWith("msf") || brand.startsWith("avi")) {
                    return HEIF;
                }
            }
            return UNKNOWN;
        }

        private static boolean startsWith(byte[] b, int... expected) {
            return matchesAt(b, 0, expected);
        }

        private static boolean matchesAt(byte[] b, int offset, int... expected) {
            if (b.length < offset + expected.length) {
                return false;
            }
            for (int i = 0; i < expected.length; i++) {
                if ((b[offset + i] & 0xFF) != expected[i]) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return description;
        }
    }
}
