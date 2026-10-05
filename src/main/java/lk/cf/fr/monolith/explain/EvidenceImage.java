package lk.cf.fr.monolith.explain;

/**
 * One image available for an attempt, as listed for the Evidence Dashboard. The bytes are fetched
 * separately by {@code key}, so the listing stays cheap.
 *
 * @param key        fixed catalogue key, e.g. {@code capture.face} or {@code cmp3.source}
 * @param group      CAPTURE (original image) | CROP (card detector output) | PAIR (comparison input)
 * @param label      what the image shows, in plain words
 * @param evidenceId for a PAIR, the evidence row it belongs to (e.g. {@code face.cmp3}); else null
 * @param role       for a PAIR, SOURCE or TARGET; else null
 */
public record EvidenceImage(String key, String group, String label, String evidenceId, String role) {
}
