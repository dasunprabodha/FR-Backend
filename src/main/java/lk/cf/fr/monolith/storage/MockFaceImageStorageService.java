package lk.cf.fr.monolith.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * MVP default - no external S3/file-storage service configured; see {@link S3FaceImageStorageService}
 * for the {@code aws.enabled=true} implementation.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "aws.enabled", havingValue = "false", matchIfMissing = true)
public class MockFaceImageStorageService implements FaceImageStorageService {

    @Override
    public void saveEnrolledFace(String nic, byte[] faceImageBytes) {
        log.info("[S3-UPLOAD] SKIPPED (mock mode, aws.enabled=false) nic={} sizeBytes={} - "
                + "no real upload happened, no external S3/file-storage service configured for this MVP.",
                nic, faceImageBytes.length);
    }
}
