package lk.cf.fr.monolith.registration.model;

/**
 * Capture mode for one registration attempt, forwarded to the device inside the
 * {@code open-camera} command's {@code data.mode} field (see
 * {@code DeviceCommunicationService#requestCapture}). {@code Auto} keeps the device's existing
 * automatic capture behavior; {@code Manual} makes it show its manual capture button in each of
 * the three registration steps (nicImage/faceImage/selfImage).
 *
 * <p>Constant names are intentionally mixed-case rather than the usual Java SCREAMING_CASE: the
 * wire value the device reads is exactly {@code "Auto"} / {@code "Manual"}, and keeping
 * {@code name()} equal to the wire value removes any chance of the two drifting apart.
 *
 * <p>Registration-only. The verification path never sends this field - see §11 of the change
 * brief and {@code DeviceCommunicationService}'s 8-arg {@code requestCapture} overload.
 */
public enum RegistrationMode {

    Auto,
    Manual;

    /** Used when a client omits {@code mode} entirely, preserving the pre-existing contract. */
    public static final RegistrationMode DEFAULT = Auto;

    /**
     * Maps the raw {@code mode} string off the wire onto this enum.
     *
     * <p>Absent/blank falls back to {@link #DEFAULT} so registration clients written against the
     * older contract keep working unchanged. Anything else must match a constant <em>exactly</em>
     * (case-sensitive) - {@code "auto"}, {@code "MANUAL"}, {@code "Random"} are all rejected
     * rather than silently coerced, so a typo can never quietly downgrade a Manual registration
     * into an Auto one.
     *
     * @throws IllegalArgumentException on any non-blank value that is not {@code Auto}/{@code Manual};
     *         {@code GlobalExceptionHandler} turns this into a 400 with the message below.
     */
    public static RegistrationMode fromRequestValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return DEFAULT;
        }
        for (RegistrationMode mode : values()) {
            if (mode.name().equals(rawValue)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "The field 'mode' must be exactly 'Auto' or 'Manual' (case-sensitive), but was '" + rawValue + "'.");
    }
}
