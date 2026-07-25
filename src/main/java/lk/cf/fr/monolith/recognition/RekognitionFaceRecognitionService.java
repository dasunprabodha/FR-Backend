package lk.cf.fr.monolith.recognition;

import lk.cf.fr.monolith.verification.model.ComparisonResult;
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

    private ComparisonResult compare(Image source, Image target) {
        CompareFacesResponse response = rekognitionClient.compareFaces(CompareFacesRequest.builder()
                .sourceImage(source)
                .targetImage(target)
                .similarityThreshold(similarityCutoff)
                .build());

        List<CompareFacesMatch> matches = response.faceMatches();
        if (matches == null || matches.isEmpty()) {
            return new ComparisonResult(false, 0.0, response.toString());
        }
        float similarity = matches.get(0).similarity();
        boolean isMatch = similarity >= similarityCutoff;
        return new ComparisonResult(isMatch, similarity, response.toString());
    }
}
