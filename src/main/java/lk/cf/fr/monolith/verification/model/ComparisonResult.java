package lk.cf.fr.monolith.verification.model;

/**
 * Mirrors cf-fr-server Face_Recognition/dto/ComparisonResult.java.
 *
 * <p>{@code sourceFaceBox}/{@code targetFaceBox} carry the face region Rekognition actually
 * detected/matched in each image (fractional bounding box), added so registration's
 * {@code ComparisonImageDumpService} can crop and dump exactly what was compared for debugging.
 * Both are {@code null} when using the mock face-recognition service, or whenever Rekognition
 * didn't detect/match a face (e.g. no face found in the source image, or no match above the
 * similarity threshold in the target).
 */
public record ComparisonResult(boolean match, Double similarity, String rawJson,
                                FaceBoundingBox sourceFaceBox, FaceBoundingBox targetFaceBox) {

    /**
     * {@code similarity} is a nullable {@link Double}, not a primitive, and the distinction is
     * load-bearing for evaluation. A comparison in which no comparable face could be detected
     * produced <em>no measurement</em>; recording that as {@code 0.0} would place it at the bottom
     * of the score distribution as though it had been measured and found maximally dissimilar,
     * which silently corrupts ROC/DET curves, EER and any threshold sweep computed from the data.
     * Null means "not measured"; {@code 0.0} now only ever means "measured as zero".
     *
     * <p>{@code match} is unaffected - a comparison with no measurement cannot match, so the gate
     * behaves exactly as before.
     */
    public boolean hasMeasurement() {
        return similarity != null;
    }
}
