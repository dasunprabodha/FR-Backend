package lk.cf.fr.monolith.recognition;

import lk.cf.fr.monolith.verification.model.ComparisonResult;
import lk.cf.fr.monolith.verification.model.FaceBoundingBox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.*;

import java.util.List;

/**
 * Real face-compare implementation - ported from
 * cf-fr-server Face_Recognition/service/FaceRecognitionService.compareFaces/compareFacesInMemory.
 * Active only when {@code aws.enabled=true}; see {@link MockFaceRecognitionService} for the MVP
 * default. Shared by both the verification and registration paths.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "aws.enabled", havingValue = "true")
public class RekognitionFaceRecognitionService implements FaceRecognitionService {

    /** Above this, a single Rekognition round trip is worth flagging as a connection problem. */
    private static final long SLOW_CALL_WARN_MS = 5_000;

    /** Inline image size in bytes, or -1 when the image is an S3 reference rather than inline bytes. */
    private static int inlineBytes(Image image) {
        return image != null && image.bytes() != null ? image.bytes().asByteArrayUnsafe().length : -1;
    }

    private static String describeBytes(int bytes) {
        if (bytes < 0) return "s3-ref";
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + "KB";
        return String.format("%.2fMB", bytes / (1024.0 * 1024.0));
    }

    /**
     * Effective upload rate, but <b>only for calls slow enough for it to mean anything</b>.
     *
     * <p>Dividing bytes by duration is a fair estimate when a call spent its life pushing image
     * data up a slow link. It is nonsense for a request the service rejected in under a second -
     * there, the figure describes a round trip that was never bandwidth-bound, and printing it
     * invites exactly the wrong conclusion. So a fast call reports no rate at all.
     */
    private static String throughputSuffix(int bytes, long elapsedMs) {
        if (bytes <= 0 || elapsedMs < SLOW_CALL_WARN_MS) {
            return "";
        }
        double kbPerSec = (bytes / 1024.0) / (elapsedMs / 1000.0);
        return String.format(" | effective upload %.1f KB/s (~%.0f kbps)", kbPerSec, kbPerSec * 8);
    }

    private final RekognitionClient rekognitionClient;
    private final String bucket;
    private final float similarityCutoff;

    public RekognitionFaceRecognitionService(RekognitionClient rekognitionClient,
                                              @Value("${aws.s3.bucket-name}") String bucket,
                                              @Value("${verification.similarity-threshold:80}") float similarityCutoff) {
        this.rekognitionClient = rekognitionClient;
        this.bucket = bucket;
        this.similarityCutoff = similarityCutoff;
    }

    @Override
    public ComparisonResult compareFaces(String nic, byte[] targetImageBytes, Double similarityOverride) {
        Image source = Image.builder()
                .s3Object(S3Object.builder().bucket(bucket).name("FaceOnly/" + nic + ".jpg").build())
                .build();
        Image target = Image.builder().bytes(SdkBytes.fromByteArray(targetImageBytes)).build();
        return compare(source, target);
    }

    @Override
    public ComparisonResult compareFacesInMemory(byte[] sourceImageBytes, byte[] targetImageBytes, Double similarityOverride) {
        Image source = Image.builder().bytes(SdkBytes.fromByteArray(sourceImageBytes)).build();
        Image target = Image.builder().bytes(SdkBytes.fromByteArray(targetImageBytes)).build();
        return compare(source, target);
    }

    /**
     * The request threshold is pinned to {@code 0}, NOT to {@link #similarityCutoff}, and the
     * accept/reject decision is applied locally instead.
     *
     * <p>This is deliberate and load-bearing. Rekognition uses {@code similarityThreshold} as a
     * server-side <em>filter</em>: any compared face scoring below it is moved into
     * {@code unmatchedFaces} and never appears in {@code faceMatches}. Passing the 80-point cutoff
     * therefore meant every sub-threshold comparison - every impostor pair, and every borderline
     * genuine pair - fell into the empty-matches branch below and was recorded as a similarity of
     * exactly {@code 0.0}. Scores clipped at the decision boundary make FAR/FRR, EER, ROC/DET
     * curves and any threshold sweep uncomputable, because the entire informative region of the
     * score distribution has been flattened to zero before it reaches the database.
     *
     * <p>Requesting all scores and thresholding here keeps the decision identical (the same
     * {@code >= similarityCutoff} test, just applied one layer later) while preserving the raw
     * score. It also sharpens the empty-matches branch: with no server-side filtering, an empty
     * {@code faceMatches} list now unambiguously means "no comparable face was detected in the
     * target image", rather than conflating that with "a face was found but scored low".
     */
    private ComparisonResult compare(Image source, Image target) {
        // Registration makes five of these back to back inside one synchronous HTTP request, so a
        // slow link shows up to the operator as an unexplained stall. The byte counts are logged
        // alongside the timing because the two are only useful together: 70 seconds means nothing
        // until you know whether it was pushing 40 KB or 3 MB, and that ratio is the difference
        // between "the network is broken" and "we are sending needlessly large images".
        int sourceBytes = inlineBytes(source);
        int targetBytes = inlineBytes(target);
        int totalBytes = Math.max(sourceBytes, 0) + Math.max(targetBytes, 0);

        long startedAt = System.currentTimeMillis();
        CompareFacesResponse response;
        try {
            response = rekognitionClient.compareFaces(CompareFacesRequest.builder()
                    .sourceImage(source)
                    .targetImage(target)
                    .similarityThreshold(0f)
                    .build());
        } catch (RuntimeException e) {
            // The failure path is the one that most needs these numbers: without them a timeout
            // says only "70000 millis elapsed", with no indication of how much data never made it.
            long failedAfterMs = System.currentTimeMillis() - startedAt;
            log.error("[CompareFaces] FAILED after {}ms | payload source={} target={} total={}{} | {}",
                    failedAfterMs, describeBytes(sourceBytes), describeBytes(targetBytes),
                    describeBytes(totalBytes), throughputSuffix(totalBytes, failedAfterMs),
                    e.getClass().getSimpleName());
            throw e;
        }
        long latencyMs = System.currentTimeMillis() - startedAt;

        if (latencyMs > SLOW_CALL_WARN_MS) {
            log.warn("[CompareFaces] SLOW: {}ms for {} (source={} target={}){} | "
                            + "registration issues five of these in sequence",
                    latencyMs, describeBytes(totalBytes), describeBytes(sourceBytes),
                    describeBytes(targetBytes), throughputSuffix(totalBytes, latencyMs));
        } else {
            log.debug("[CompareFaces] {}ms for {} (source={} target={})",
                    latencyMs, describeBytes(totalBytes), describeBytes(sourceBytes), describeBytes(targetBytes));
        }

        FaceBoundingBox sourceFaceBox = response.sourceImageFace() != null
                ? toFaceBoundingBox(response.sourceImageFace().boundingBox())
                : null;

        List<CompareFacesMatch> matches = response.faceMatches();
        if (matches == null || matches.isEmpty()) {
            // No face detected in the target at all - genuinely no score to report. Reported as a
            // null similarity rather than 0.0 so it lands as a missing value in the score
            // distribution instead of a spurious zero at the bottom of it.
            log.info("[CompareFaces] No comparable face detected in the target image -> no similarity measurement");
            return new ComparisonResult(false, null, response.toString(), sourceFaceBox, null);
        }
        // With no server-side filter, faceMatches carries every detected target face ordered by
        // similarity, so the head is still the best match.
        CompareFacesMatch bestMatch = matches.get(0);
        float similarity = bestMatch.similarity();
        boolean isMatch = similarity >= similarityCutoff;
        FaceBoundingBox targetFaceBox = bestMatch.face() != null ? toFaceBoundingBox(bestMatch.face().boundingBox()) : null;
        log.info("[CompareFaces] similarity={} cutoff={} -> match={} ({} candidate face(s) compared, {}ms)",
                similarity, similarityCutoff, isMatch, matches.size(), latencyMs);
        // Explicit widening: Java will not autobox a float straight to Double.
        return new ComparisonResult(isMatch, (double) similarity, response.toString(), sourceFaceBox, targetFaceBox);
    }

    /** Rekognition's BoundingBox fields are boxed Floats and can be null even when the box itself is present. */
    private static FaceBoundingBox toFaceBoundingBox(BoundingBox box) {
        if (box == null || box.left() == null || box.top() == null || box.width() == null || box.height() == null) {
            return null;
        }
        return new FaceBoundingBox(box.left(), box.top(), box.width(), box.height());
    }
}
