package lk.cf.fr.monolith.verification.model;

/** Mirrors cf-fr-server Face_Recognition/dto/ComparisonResult.java. */
public record ComparisonResult(boolean match, double similarity, String rawJson) {
}
