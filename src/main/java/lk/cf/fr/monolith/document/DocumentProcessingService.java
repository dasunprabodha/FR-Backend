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
     * Runs OCR over the supplied document image and classifies it.
     *
     * <p>Returns a {@link NicOcrResult} rather than a bare {@link NicValidationOutcome}: the
     * classification is unchanged and still available as {@link NicOcrResult#outcome()}, but the
     * extracted NIC number and per-line OCR confidences now travel with it, so callers can bind
     * the document to a claimed identity instead of only asking "is this a NIC?".
     *
     * @param overrideValid MVP-only demo hook: when the mock implementation is active and this is
     *                      non-null, forces the outcome to VALID/INVALID instead of the configured
     *                      default. Ignored by the real (AWS) implementation.
     */
    NicOcrResult validateNic(byte[] imageBytes, Boolean overrideValid);
}
