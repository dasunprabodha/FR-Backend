package lk.cf.fr.monolith.verification.dto;

import lombok.Getter;
import lombok.Setter;

/** Matches the legacy request body shape: {@code { "data": { ... } } }. */
@Getter
@Setter
public class VerificationRequestEnvelope {

    private VerificationRequest data;
}
