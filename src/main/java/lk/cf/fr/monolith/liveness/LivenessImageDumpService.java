package lk.cf.fr.monolith.liveness;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.rekognition.model.AuditImage;
import software.amazon.awssdk.services.rekognition.model.GetFaceLivenessSessionResultsResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Diagnostics dump of the still images AWS returns alongside a Face Liveness verdict - the
 * reference image plus however many audit images {@code auditImagesLimit} asked for in
 * {@link RekognitionLivenessService#createSession()}. Those bytes already come back inside every
 * GetFaceLivenessSessionResults response; until now the service read past them to
 * {@code confidence()} and let them be garbage-collected, so there was never anything to eyeball
 * when a liveness score looked wrong.
 *
 * <p>Keyed by liveness session id rather than referenceId, because the session id is all
 * {@link LivenessService#getResults} is handed. Both {@code registration_record} and
 * {@code transaction_record} persist {@code livenessSessionId}, so an attempt can still be traced
 * to its folder from the database.
 *
 * <p>Same contract as {@code ComparisonImageDumpService}: best-effort, write-only, never read back
 * by the application, and incapable of affecting a liveness verdict. Unlike that one it is
 * switchable, because these images are face biometrics the system otherwise does not retain at
 * all - see {@code verification.liveness-image-dump.enabled}.
 */
@Service
@Slf4j
public class LivenessImageDumpService {

    private final boolean enabled;
    private final Path baseDir;

    public LivenessImageDumpService(
            @Value("${verification.liveness-image-dump.enabled:true}") boolean enabled,
            @Value("${verification.liveness-image-dump.base-dir:./data/liveness-images}") String baseDir) {
        this.enabled = enabled;
        this.baseDir = Paths.get(baseDir);
    }

    public void dump(String sessionId, GetFaceLivenessSessionResultsResponse response) {
        if (!enabled) {
            return;
        }
        try {
            Path dir = baseDir.resolve(safeFolderName(sessionId));
            Files.createDirectories(dir);

            int written = write(dir.resolve("reference.jpg"), response.referenceImage()) ? 1 : 0;
            List<AuditImage> auditImages = response.hasAuditImages() ? response.auditImages() : List.of();
            for (int i = 0; i < auditImages.size(); i++) {
                if (write(dir.resolve("audit-" + (i + 1) + ".jpg"), auditImages.get(i))) {
                    written++;
                }
            }

            log.info("[Liveness][Image-Dump] sessionId={} wrote {} image(s) (reference={}, audit={}) to {}",
                    sessionId, written, response.referenceImage() != null, auditImages.size(), dir);
        } catch (Exception e) {
            log.warn("[Liveness][Image-Dump] sessionId={} failed to dump liveness images - continuing",
                    sessionId, e);
        }
    }

    /**
     * @return true if the image carried inline bytes and was written. An AuditImage can instead
     *         carry only an S3 reference, which happens when the session was created with an
     *         OutputConfig - this app never sets one, so in practice the bytes are always inline.
     */
    private boolean write(Path file, AuditImage image) throws IOException {
        SdkBytes bytes = image == null ? null : image.bytes();
        if (bytes == null) {
            return false;
        }
        Files.write(file, bytes.asByteArray());
        return true;
    }

    /** Session ids are AWS-generated UUIDs, but a folder name is never built from one unescaped. */
    private static String safeFolderName(String sessionId) {
        return sessionId == null || sessionId.isBlank()
                ? "unknown-session"
                : sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
