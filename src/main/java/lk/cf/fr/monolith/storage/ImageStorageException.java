package lk.cf.fr.monolith.storage;

/** Thrown when a captured registration image cannot be persisted, locally or to S3. */
public class ImageStorageException extends RuntimeException {

    public ImageStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
