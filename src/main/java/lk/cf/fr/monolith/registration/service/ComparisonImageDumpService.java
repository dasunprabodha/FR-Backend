package lk.cf.fr.monolith.registration.service;

import lk.cf.fr.monolith.verification.model.ComparisonResult;
import lk.cf.fr.monolith.verification.model.FaceBoundingBox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Debug/analysis aid for the registration path's four pairwise face comparisons - crops each
 * source/target image down to the exact face region Rekognition CompareFaces detected/matched
 * (via {@link FaceBoundingBox} on {@link ComparisonResult}) and writes both crops to local disk,
 * so a similarity score can be sanity-checked against what was actually compared. Separate from
 * {@code LocalPendingImageStorageService} (which persists the raw captures for the approval
 * workflow) - this dumps the derived, per-comparison crop, not the original capture.
 *
 * <p>Best-effort only: a failure here (unreadable image bytes, disk error, unsupported format)
 * never affects the registration response - it's a diagnostics side channel, not part of the
 * pass/fail gate.
 */
@Service
@Slf4j
public class ComparisonImageDumpService {

    private final Path baseDir;

    public ComparisonImageDumpService(
            @Value("${registration.comparison-dump.base-dir:./data/comparison-analysis}") String baseDir) {
        this.baseDir = Paths.get(baseDir);
    }

    public void dump(String referenceId, String comparisonLabel, byte[] sourceBytes, byte[] targetBytes, ComparisonResult result) {
        try {
            Path dir = baseDir.resolve(referenceId);
            Files.createDirectories(dir);
            saveCropped(dir.resolve(comparisonLabel + "-source.jpg"), sourceBytes, result.sourceFaceBox());
            saveCropped(dir.resolve(comparisonLabel + "-target.jpg"), targetBytes, result.targetFaceBox());
            log.info("[Registration][Comparison-Dump] referenceId={} comparison={} dumped (sourceCropped={}, targetCropped={})",
                    referenceId, comparisonLabel, result.sourceFaceBox() != null, result.targetFaceBox() != null);
        } catch (Exception e) {
            log.warn("[Registration][Comparison-Dump] referenceId={} comparison={} failed to dump analysis images - continuing",
                    referenceId, comparisonLabel, e);
        }
    }

    private void saveCropped(Path file, byte[] imageBytes, FaceBoundingBox box) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) {
            // Not a raster format ImageIO can decode - dump the raw bytes as-is rather than nothing.
            Files.write(file, imageBytes);
            return;
        }

        BufferedImage toWrite = image;
        if (box != null) {
            int x = clamp(Math.round(box.left() * image.getWidth()), 0, image.getWidth() - 1);
            int y = clamp(Math.round(box.top() * image.getHeight()), 0, image.getHeight() - 1);
            int w = clamp(Math.round(box.width() * image.getWidth()), 1, image.getWidth() - x);
            int h = clamp(Math.round(box.height() * image.getHeight()), 1, image.getHeight() - y);
            toWrite = image.getSubimage(x, y, w, h);
        }
        ImageIO.write(toWrite, "jpg", file.toFile());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
