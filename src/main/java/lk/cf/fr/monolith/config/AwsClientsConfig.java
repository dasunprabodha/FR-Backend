package lk.cf.fr.monolith.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS Rekognition/S3 clients - ported from cf-fr-server Face_Recognition/configuration/AwsClientsConfig.java.
 *
 * <p>Only created when {@code aws.enabled=true} (default: false for this MVP - see
 * IMPLEMENTATION_PROGRESS.md "Blockers" for why AWS Rekognition/S3 are not wired up by default).
 * Shared by both the verification and registration paths (both need Rekognition CompareFaces and
 * Face Liveness; registration additionally uses Rekognition DetectText for NIC/document OCR).
 * Resolves architecture doc open question §28 Q3 (two divergent credential strategies existed in
 * the unresolved merge conflict): this monolith always prefers explicit static credentials from
 * environment variables when present, and otherwise falls back to the AWS SDK default credential
 * chain (never a hardcoded key - the legacy application.yml had a real AWS key committed in
 * plaintext, which must NOT be carried forward).
 */
@Configuration
@ConditionalOnProperty(name = "aws.enabled", havingValue = "true")
public class AwsClientsConfig {

    @Value("${aws.access-key:}")
    private String accessKey;

    @Value("${aws.secret-key:}")
    private String secretKey;

    @Value("${aws.session-token:}")
    private String sessionToken;

    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            if (sessionToken != null && !sessionToken.isBlank()) {
                return StaticCredentialsProvider.create(
                        AwsSessionCredentials.create(accessKey, secretKey, sessionToken));
            }
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.create();
    }

    @Bean
    public RekognitionClient rekognitionClient(@Value("${aws.region}") String region, AwsCredentialsProvider creds) {
        return RekognitionClient.builder().region(Region.of(region)).credentialsProvider(creds).build();
    }

    @Bean
    public S3Client s3Client(@Value("${aws.region}") String region, AwsCredentialsProvider creds) {
        return S3Client.builder().region(Region.of(region)).credentialsProvider(creds).build();
    }
}
