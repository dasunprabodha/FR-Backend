package lk.cf.fr.monolith.storage;

/**
 * Persists the enrolled reference face image used later by verification's {@code CompareFaces}
 * lookup (see {@code FaceRecognitionService#compareFaces}, which reads from
 * {@code s3://<bucket>/FaceOnly/<nic>.jpg} in the real implementation).
 */
public interface FaceImageStorageService {

    void saveEnrolledFace(String nic, byte[] faceImageBytes);
}
