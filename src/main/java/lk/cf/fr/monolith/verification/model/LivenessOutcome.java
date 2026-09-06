package lk.cf.fr.monolith.verification.model;

/** Mirrors the relevant fields of cf-fr-server Face_Recognition/service/LivenessService.LivenessResult. */
public record LivenessOutcome(boolean passed, Double confidence) {
}
