package lk.cf.fr.monolith.storage;

/**
 * Persists the registration-captured images to their respective S3 folders. {@link #saveEnrolledFace}
 * is the one read back later by verification's {@code CompareFaces} lookup (see
 * {@code FaceRecognitionService#compareFaces}, which reads from {@code s3://<bucket>/FaceOnly/<nic>.jpg}
 * in the real implementation); {@link #saveFaceWithNic} and {@link #saveNicImage} are archival only.
 */
public interface FaceImageStorageService {

    /** Uploads to {@code FaceOnly/<nic>.jpg}. */
    void saveEnrolledFace(String nic, byte[] faceImageBytes);

    /** Uploads the self-with-NIC capture to {@code FaceWithNIC/<nic>.jpg}. */
    void saveFaceWithNic(String nic, byte[] selfImageBytes);

    /** Uploads the device-captured NIC photo to {@code NICImage/<nic>.jpg}. */
    void saveNicImage(String nic, byte[] nicImageBytes);
}
