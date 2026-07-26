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
 * {@link MockFaceImageStorageService} for the MVP default. Failures are caught and logged rather
 * than propagated, matching {@code RekognitionDocumentProcessingService}'s own handling of AWS
 * errors: an interim-storage hiccup shouldn't turn an otherwise-successful face/liveness match
 * into a failed registration response.
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
        }
    }
}
