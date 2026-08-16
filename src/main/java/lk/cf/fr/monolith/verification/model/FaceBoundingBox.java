package lk.cf.fr.monolith.verification.model;

/**
 * The face region AWS Rekognition CompareFaces actually detected/matched within an image, as
 * fractions of the image's width/height (each in [0,1]) - mirrors Rekognition's own
 * {@code BoundingBox} shape. Used to crop the compared face region out of the original image for
 * debugging/analysis dumps (see {@code ComparisonImageDumpService}); {@code null} on
 * {@link ComparisonResult} when no face was detected (e.g. the mock face-recognition service, or
 * a genuine "no face found" result from Rekognition).
 */
public record FaceBoundingBox(float left, float top, float width, float height) {
}
