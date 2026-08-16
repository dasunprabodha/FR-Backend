package lk.cf.fr.monolith.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * Interim, pre-approval storage for the three images captured during registration. Written at
 * capture time for every attempt (not just failures) - see {@code RegistrationService}, which
 * previously had nowhere durable to put these bytes (they only lived as request-local variables).
 * A PENDING_APPROVAL record's images are read back from here by {@code RegistrationApprovalService}
 * at approve time (for the final S3 upload) and by {@code ApprovalController}'s image-preview
 * endpoint; they are left in place after approve/reject either way for audit purposes.
 */
@Service
@Slf4j
public class LocalPendingImageStorageService {

    private final Path baseDir;

    public LocalPendingImageStorageService(
            @Value("${registration.pending-images.base-dir:./data/pending-registrations}") String baseDir) {
        this.baseDir = Paths.get(baseDir);
    }

    public String save(String referenceId, String tag, byte[] bytes) {
        try {
            Path dir = baseDir.resolve(referenceId);
            Files.createDirectories(dir);
            Path file = dir.resolve(tag + ".jpg");
            Files.write(file, bytes);
            return file.toString();
        } catch (IOException e) {
            throw new ImageStorageException(
                    "Failed to save local image referenceId=" + referenceId + " tag=" + tag, e);
        }
    }

    public byte[] read(String path) {
        try {
            return Files.readAllBytes(Paths.get(path));
        } catch (IOException e) {
            throw new ImageStorageException("Failed to read local image path=" + path, e);
        }
    }

    /** Best-effort cleanup on hard registration failure - nothing to approve/reject, so nothing worth keeping. */
    public void deleteAll(String referenceId) {
        Path dir = baseDir.resolve(referenceId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("[Registration] Failed to delete {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("[Registration] Failed to clean up local images referenceId={}", referenceId, e);
        }
    }
}
