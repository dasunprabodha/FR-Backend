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
public record ComparisonResult(boolean match, double similarity, String rawJson,
                                FaceBoundingBox sourceFaceBox, FaceBoundingBox targetFaceBox) {
}
