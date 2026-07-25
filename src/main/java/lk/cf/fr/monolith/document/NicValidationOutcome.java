package lk.cf.fr.monolith.document;

/**
 * Mirrors the three-way result of cf-fr-server
 * Face_Recognition/utils/NICValidationUtils.containsNationalIdentityCard (which returns
 * "Valid"/"Invalid"/"Unknown" strings) as a proper enum.
 */
public enum NicValidationOutcome {
    VALID,
    INVALID,
    UNKNOWN
}
