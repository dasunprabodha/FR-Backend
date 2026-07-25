package lk.cf.fr.monolith.document;

/**
 * NIC/document OCR validation capability - mirrors cf-fr-server
 * Face_Recognition/utils/NICValidationUtils.containsNationalIdentityCard. See
 * REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md §3.4/§18.
 *
 * <p><b>Deliberately excludes the legacy ONNX-based {@code CardDetectorService} card-region
 * cropping step</b> (a ~1300-line component requiring onnxruntime + javacv/opencv native
 * dependencies) - see REGISTRATION_IMPLEMENTATION_PROGRESS.md "Blockers" for the full rationale.
 * OCR here runs directly against the full captured photo; AWS Rekognition's {@code DetectText}
 * already performs its own text-region detection internally, so cropping was an accuracy
 * optimization, not a correctness requirement, for the OCR decision itself.
 */
public interface DocumentProcessingService {

    /**
     * @param overrideValid MVP-only demo hook: when the mock implementation is active and this is
     *                      non-null, forces the outcome to VALID/INVALID instead of the configured
     *                      default. Ignored by the real (AWS) implementation.
     */
    NicValidationOutcome validateNic(byte[] imageBytes, Boolean overrideValid);
}
