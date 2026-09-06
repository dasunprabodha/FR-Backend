package lk.cf.fr.monolith.config;

import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@ConditionalOnProperty(name = "aws.enabled", havingValue = "true")
public class AwsClientsConfig {

    @Value("${aws.access-key:}")
    private String accessKey;

    @Value("${aws.secret-key:}")
    private String secretKey;

    @Value("${aws.session-token:}")
    private String sessionToken;

    /**
     * Resolves credentials and, importantly, says so at startup.
     *
     * <p>Without this the application boots perfectly happily with {@code aws.enabled=true} and no
     * credentials anywhere, then fails on the first Rekognition call - by which point a batch
     * evaluation has already spent tens of seconds on local detector work and the failure arrives
     * as a two-thousand-character provider-chain dump. Naming the credential source during startup
     * turns that into something noticed before any work is wasted.
     */
    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        boolean hasStatic = accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();

        if (hasStatic) {
            boolean temporary = sessionToken != null && !sessionToken.isBlank();
            log.info("[AWS] Using explicit credentials from configuration (accessKeyId={}…, type={}). "
                            + "Set these in application-local.yml, never in application.yml.",
                    accessKey.substring(0, Math.min(4, accessKey.length())),
                    temporary ? "temporary STS - these expire" : "long-term IAM");
            return temporary
                    ? StaticCredentialsProvider.create(
                            AwsSessionCredentials.create(accessKey, secretKey, sessionToken))
                    : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }

        log.info("[AWS] No credentials set in configuration - falling back to the SDK default chain "
                + "(environment variables, ~/.aws/credentials, instance profile).");

        DefaultCredentialsProvider provider = DefaultCredentialsProvider.create();
        try {
            provider.resolveCredentials();
            log.info("[AWS] Default credential chain resolved successfully.");
        } catch (Exception e) {
            // Deliberately does not fail startup: the app is still useful without AWS (the mock
            // services, the approval dashboard, the evidence views over already-stored records).
            log.warn("[AWS] ******************************************************************");
            log.warn("[AWS] NO CREDENTIALS AVAILABLE. aws.enabled=true, but neither");
            log.warn("[AWS] application-local.yml nor the SDK default chain provided any.");
            log.warn("[AWS] Every Rekognition/S3 call will fail until this is fixed:");
            log.warn("[AWS]   - paste your keys into ./application-local.yml, or");
            log.warn("[AWS]   - set aws.enabled=false there to run on the mock services.");
            log.warn("[AWS] ******************************************************************");
        }
        return provider;
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
