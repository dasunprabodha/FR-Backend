package lk.cf.fr.monolith.recognition;

import lk.cf.fr.monolith.verification.model.ComparisonResult;

/**
 * Face-compare capability. Mirrors cf-fr-server Face_Recognition/service/FaceRecognitionService.compareFaces -
 * see VERIFICATION_PATH_ARCHITECTURE.md section 3.4/§19: this is a thin AWS Rekognition
 * CompareFaces wrapper, not a local embedding model.
 */
public interface FaceRecognitionService {

    /**
     * @param nic                 identifies the enrolled reference image (source), stored at
     *                            {@code s3://<bucket>/FaceOnly/<nic>.jpg} in the real implementation.
     * @param targetImageBytes    the image just captured from the device (target).
     * @param similarityOverride  MVP-only demo hook: when the mock implementation is active, forces
     *                            the returned similarity score instead of the configured default.
     *                            Ignored by the real (AWS) implementation.
     */
    ComparisonResult compareFaces(String nic, byte[] targetImageBytes, Double similarityOverride);

    /**
     * Bytes-vs-bytes comparison, used by the registration path's four pairwise comparisons
     * (device-NIC-vs-face, device-NIC-vs-self, face-vs-self, scanned-NIC-vs-face) - mirrors
     * cf-fr-server Face_Recognition/service/FaceRecognitionService.compareFacesInMemory. Unlike
     * {@link #compareFaces}, neither image is fetched from S3/an enrolled reference - both are
     * supplied directly (captured-device images or the optional uploaded scanned-NIC file).
     *
     * @param similarityOverride MVP-only demo hook, same purpose as in {@link #compareFaces}.
     */
    ComparisonResult compareFacesInMemory(byte[] sourceImageBytes, byte[] targetImageBytes, Double similarityOverride);
}
