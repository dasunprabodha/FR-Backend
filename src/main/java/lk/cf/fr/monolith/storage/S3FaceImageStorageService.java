package lk.cf.fr.monolith.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * Real S3 upload of the enrolled reference face - active only when {@code aws.enabled=true}; see
 * {@link MockFaceImageStorageService} for the MVP default. Failures are propagated as
 * {@link ImageStorageException} rather than swallowed, so callers can react: the manual-approval
 * path ({@code RegistrationApprovalService.approve} via {@code RegistrationFinalizationService})
 * needs to know a failure happened so it can leave the record PENDING_APPROVAL instead of marking
 * it APPROVED. The automatic-success path ({@code RegistrationService}) is the one that chooses
 * to catch-and-log instead of failing the registration response, preserving the original
 * "an interim-storage hiccup shouldn't turn an otherwise-successful match into a failed
 * registration" behavior at the call site instead of here.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "aws.enabled", havingValue = "true")
public class S3FaceImageStorageService implements FaceImageStorageService {

    private final S3Client s3Client;
    private final String bucket;

    public S3FaceImageStorageService(S3Client s3Client, @Value("${aws.s3.bucket-name}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public void saveEnrolledFace(String nic, byte[] faceImageBytes) {
        upload("FaceOnly", nic, faceImageBytes);
    }

    @Override
    public void saveFaceWithNic(String nic, byte[] selfImageBytes) {
        upload("FaceWithNIC", nic, selfImageBytes);
    }

    @Override
    public void saveNicImage(String nic, byte[] nicImageBytes) {
        upload("NICImage", nic, nicImageBytes);
    }

    private void upload(String folder, String nic, byte[] imageBytes) {
        String key = folder + "/" + nic + ".jpg";
        log.info("[S3-UPLOAD] Attempting upload nic={} bucket={} key={} sizeBytes={}",
                nic, bucket, key, imageBytes.length);
        try {
            PutObjectResponse response = s3Client.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType("image/jpeg")
                    .build(), RequestBody.fromBytes(imageBytes));
            log.info("[S3-UPLOAD] SUCCESS nic={} bucket={} key={} eTag={} versionId={}",
                    nic, bucket, key, response.eTag(), response.versionId());
        } catch (Exception e) {
            log.error("[S3-UPLOAD] FAILED nic={} bucket={} key={} reason={}",
                    nic, bucket, key, e.getMessage(), e);
            throw new ImageStorageException("S3 upload failed for nic=" + nic + " key=" + key, e);
        }
    }
}
