package lk.cf.fr.monolith.registration.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Body for {@code POST /api/approval/{id}/approve} and {@code .../reject}. {@code reviewedBy} is
 * left client-suppliable (and nullable) on purpose - there is no authentication/authorization
 * module yet, so it can't be derived from a logged-in principal; wiring auth in later only means
 * populating this from the security context instead of the request body, not changing the API
 * shape.
 */
@Getter
@Setter
public class ApprovalActionRequest {

    private String reviewedBy;
    private String remarks;
}
