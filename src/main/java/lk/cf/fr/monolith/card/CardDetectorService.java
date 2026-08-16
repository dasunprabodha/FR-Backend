package lk.cf.fr.monolith.card;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lk.cf.fr.monolith.storage.LocalPendingImageStorageService;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_imgproc.CLAHE;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * Local ID-card region detector (YOLOv8 ONNX, single class "id_card") - ported from the legacy
 * cf-fr-server {@code CardDetectorService}. Previously deliberately excluded from this MVP for its
 * native-dependency weight (see REGISTRATION_IMPLEMENTATION_PROGRESS.md Blocker #1); reintroduced
 * to fix a real accuracy problem: the device-captured "nicImage" sometimes frames the user's live
 * face more prominently than the physical card, so AWS Rekognition ends up comparing against the
 * user's live face instead of the small printed photo on the card. Cropping down to just the
 * detected card region before handing the image to Rekognition fixes that.
 *
 * <p>Adapted from the legacy source in two ways only - the detection/scoring algorithm itself
 * (multi-strategy inference, YOLO output decoding, NMS, letterboxing, the NIC-specific
 * aspect-ratio/area sanity checks, the contour-based fallback) is otherwise unchanged:
 * <ul>
 *   <li>The legacy {@code NICValidationUtils.dumpFacesToLocal} call (a class that doesn't exist in
 *   this codebase) is replaced with {@link LocalPendingImageStorageService#save}, which already
 *   exists here for the same "persist a diagnostic/audit image locally" purpose.</li>
 *   <li>Multi-crop dump tags are suffixed with a per-detection index so concurrent crops from the
 *   same capture never collide on the same local file (the legacy dump call tolerated collisions
 *   since it appended its own unique suffix internally; ours does not).</li>
 * </ul>
 */
@Slf4j
@Service
public class CardDetectorService {

    private final LocalPendingImageStorageService localPendingImageStorageService;
    private final String modelPath;

    private static final int INPUT_SIZE = 640;

    // Trained model has one class only: class 0 = id_card
    private static final int NUM_CLASSES = 1;
    private static final int CARD_CLASS = 0;

    // For debug/logging, keep this low first.
    // Later, after confirming logs, use 0.35f or 0.45f for stricter production cropping.
    private static final float CONF_THRESH = 0.35f;

    private static final float IOU_THRESH = 0.45f;

    // Relaxed because cards can be tilted, rotated, scanned, or perspective-distorted.
    private static final float MIN_ASPECT = 0.45f;
    private static final float MAX_ASPECT = 5.00f;

    // Reject tiny detections.
    private static final float MIN_WIDTH_FRAC = 0.03f;

    private OrtEnvironment env;
    private OrtSession session;
    private String inputName;
    private boolean available = false;

    public CardDetectorService(LocalPendingImageStorageService localPendingImageStorageService,
                                @Value("${fr.carddetector.path:/models/card_model_2.onnx}") String modelPath) {
        this.localPendingImageStorageService = localPendingImageStorageService;
        this.modelPath = modelPath;
    }

    @PostConstruct
    public void init() {
        log.info("CardDetectorService.init() starting... model={}", modelPath);

        try {
            java.net.URL res = getClass().getResource(modelPath);

            if (res == null) {
                log.error("CardDetectorService: model not found at {}", modelPath);
                available = false;
                return;
            }

            log.info("CardDetectorService: getResource({}) -> {}", modelPath, res);

            byte[] modelBytes;
            try (InputStream is = res.openStream()) {
                modelBytes = is.readAllBytes();
            }

            log.info(
                    "CardDetectorService: model bytes read: {} bytes ({} KB)",
                    modelBytes.length,
                    modelBytes.length / 1024
            );

            env = OrtEnvironment.getEnvironment();

            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

            session = env.createSession(modelBytes, options);
            inputName = session.getInputNames().iterator().next();

            available = true;

            log.info(
                    "CardDetectorService READY. inputName={} inputs={} outputs={}",
                    inputName,
                    session.getInputInfo().keySet(),
                    session.getOutputInfo().keySet()
            );
        } catch (Exception e) {
            available = false;
            log.error("CardDetectorService: init failed: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void destroy() {
        try {
            if (session != null) {
                session.close();
                session = null;
            }
        } catch (Exception e) {
            log.warn("CardDetectorService: session close failed: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Best single card crop. Returns {@code null} (never throws to the caller) if no card was
     * detected - callers should fall back to the original uncropped image in that case.
     */
    public byte[] cropCard(byte[] imageBytes, String tag, String referenceId) {
        try {
            List<DetectionResult> dets = detectMultiStrategy(imageBytes);

            if (dets.isEmpty()) {
                log.warn("[{}] cropCard: no card detected", tag);
                return null;
            }

            dets.sort(Comparator.comparingDouble((DetectionResult d) -> d.score).reversed());

            DetectionResult best = dets.get(0);

            log.info(
                    "[{}] cropCard: best score={} box=({},{})->({},{}) size={}x{}",
                    tag,
                    f(best.score),
                    best.x1,
                    best.y1,
                    best.x2,
                    best.y2,
                    best.width(),
                    best.height()
            );

            return cropRegion(imageBytes, best, tag, referenceId);
        } catch (Exception e) {
            log.error("[{}] cropCard failed: {}", tag, e.getMessage(), e);
            return null;
        }
    }

    /**
     * All card crops. Useful for ScannedNIC/DeviceNIC/any image where multiple card-like regions
     * may exist.
     */
    public List<byte[]> cropAllCards(byte[] imageBytes, String tag, String referenceId) throws Exception {
        log.info("[{}] cropAllCards: started", tag);

        if (imageBytes == null) {
            log.info("[{}] Image Bytes are Null", tag);
            return null;
        }

        List<byte[]> crops = new ArrayList<>();

        List<DetectionResult> dets = detectMultiStrategy(imageBytes);

        if (dets.isEmpty()) {
            log.warn("[{}] cropAllCards: no detections from original multi-strategy pass", tag);
        } else {
            int idx = 0;
            for (DetectionResult d : dets) {
                byte[] crop = cropRegion(imageBytes, d, tag + "-" + idx, referenceId);
                idx++;
                if (crop != null) {
                    crops.add(crop);
                }
            }
        }

        if (crops.isEmpty()) {
            log.warn("[{}] cropAllCards: normal crops empty, trying enhanced image", tag);

            try {
                byte[] enhancedImageBytes = enhanceImage(imageBytes);
                List<DetectionResult> enhancedDets = detectMultiStrategy(enhancedImageBytes);

                if (enhancedDets.isEmpty()) {
                    log.warn("[{}] cropAllCards: enhanced detections empty", tag);
                } else {
                    int idx = 0;
                    for (DetectionResult d : enhancedDets) {
                        byte[] crop = cropRegion(enhancedImageBytes, d, tag + "-Enhanced-" + idx, referenceId);
                        idx++;
                        if (crop != null) {
                            crops.add(crop);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[{}] cropAllCards: enhanced pass failed: {}", tag, e.getMessage());
            }
        }

        if (crops.isEmpty()) {
            log.warn("[{}] cropAllCards: ONNX crops empty, using contour fallback", tag);
            crops.addAll(detectAndCropNICContour(imageBytes, tag, referenceId));
        }

        log.info("[{}] cropAllCards: returning {} crop(s)", tag, crops.size());

        return crops;
    }

    private List<DetectionResult> detectMultiStrategy(byte[] imageBytes) throws Exception {
        if (!available || session == null) {
            log.warn("detectMultiStrategy: detector unavailable");
            return Collections.emptyList();
        }

        Mat orig = null;

        try {
            orig = decodeImage(imageBytes);

            if (orig == null || orig.empty()) {
                log.warn("detectMultiStrategy: cannot decode image");
                return Collections.emptyList();
            }

            int origW = orig.cols();
            int origH = orig.rows();

            log.info("detectMultiStrategy: original size={}x{}", origW, origH);

            List<DetectionResult> allDets = new ArrayList<>();

            allDets.addAll(runInferenceOnMat(orig, origW, origH, "original"));

            try {
                Mat clahe = applyClahe(orig);
                allDets.addAll(runInferenceOnMat(clahe, origW, origH, "clahe"));
                clahe.release();
            } catch (Exception e) {
                log.warn("detectMultiStrategy: clahe variant failed: {}", e.getMessage());
            }

            try {
                Mat sharp = sharpen(orig);
                allDets.addAll(runInferenceOnMat(sharp, origW, origH, "sharpen"));
                sharp.release();
            } catch (Exception e) {
                log.warn("detectMultiStrategy: sharpen variant failed: {}", e.getMessage());
            }

            try {
                Mat down = new Mat();
                resize(orig, down, new Size(
                        Math.max(1, (int) (origW * 0.75)),
                        Math.max(1, (int) (origH * 0.75))
                ));
                allDets.addAll(runInferenceOnMat(down, origW, origH, "scale0.75"));
                down.release();
            } catch (Exception e) {
                log.warn("detectMultiStrategy: scale0.75 variant failed: {}", e.getMessage());
            }

            try {
                Mat up = new Mat();
                resize(orig, up, new Size(
                        Math.max(1, (int) (origW * 1.5)),
                        Math.max(1, (int) (origH * 1.5))
                ));
                allDets.addAll(runInferenceOnMat(up, origW, origH, "scale1.5"));
                up.release();
            } catch (Exception e) {
                log.warn("detectMultiStrategy: scale1.5 variant failed: {}", e.getMessage());
            }

            log.info("detectMultiStrategy: total detections before NMS={}", allDets.size());

            List<DetectionResult> finalDets = nms(allDets, IOU_THRESH);

            log.info("detectMultiStrategy: detections after NMS={}", finalDets.size());

            for (int i = 0; i < finalDets.size(); i++) {
                DetectionResult d = finalDets.get(i);
                log.info(
                        "detectMultiStrategy: final[{}] score={} box=({},{})->({},{}) size={}x{} aspect={}",
                        i,
                        f(d.score),
                        d.x1,
                        d.y1,
                        d.x2,
                        d.y2,
                        d.width(),
                        d.height(),
                        f(d.aspect())
                );
            }

            return finalDets;
        } finally {
            if (orig != null) {
                orig.release();
            }
        }
    }

    private List<DetectionResult> runInferenceOnMat(Mat mat, int origW, int origH, String tag) throws Exception {
        LetterboxResult letterbox = null;

        try {
            letterbox = letterbox(mat);

            float[] input = matToFloat(letterbox.image);

            long[] shape = {1, 3, INPUT_SIZE, INPUT_SIZE};

            Object outputValue;

            long startMs = System.currentTimeMillis();

            try (
                    OnnxTensor tensor = OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(input),
                            shape
                    );
                    OrtSession.Result result = session.run(Map.of(inputName, tensor))
            ) {
                outputValue = result.get(0).getValue();
            }

            long latencyMs = System.currentTimeMillis() - startMs;

            List<DetectionResult> dets = decodeYoloOutput(
                    outputValue,
                    letterbox,
                    origW,
                    origH,
                    tag
            );

            log.info(
                    "runInferenceOnMat[{}]: latency={}ms mat={}x{} orig={}x{} detections={}",
                    tag,
                    latencyMs,
                    mat.cols(),
                    mat.rows(),
                    origW,
                    origH,
                    dets.size()
            );

            return dets;
        } finally {
            if (letterbox != null && letterbox.image != null) {
                letterbox.image.release();
            }
        }
    }

    /**
     * Supports usual Ultralytics YOLOv8 ONNX output:
     * [1, 5, 8400] for one class
     * and also row-first variants:
     * [1, 8400, 5]
     *
     * Also includes partial support for NMS-style output:
     * [1, N, 6] = x1, y1, x2, y2, score, class
     */
    private List<DetectionResult> decodeYoloOutput(
            Object outputValue,
            LetterboxResult letterbox,
            int origW,
            int origH,
            String tag
    ) {
        List<DetectionResult> dets = new ArrayList<>();

        if (!(outputValue instanceof float[][][])) {
            log.error(
                    "decodeYoloOutput[{}]: unexpected output type={}",
                    tag,
                    outputValue == null ? "null" : outputValue.getClass().getName()
            );
            return dets;
        }

        float[][][] raw = (float[][][]) outputValue;

        if (raw.length == 0 || raw[0].length == 0 || raw[0][0].length == 0) {
            log.error("decodeYoloOutput[{}]: empty output", tag);
            return dets;
        }

        int dim1 = raw[0].length;
        int dim2 = raw[0][0].length;

        boolean channelFirst = dim1 <= 20 && dim2 > 20;

        log.info(
                "decodeYoloOutput[{}]: dim1={} dim2={} channelFirst={} confThresh={}",
                tag,
                dim1,
                dim2,
                channelFirst,
                CONF_THRESH
        );

        if (channelFirst) {
            // Shape: [1, channels, boxes]
            int channels = dim1;
            int boxes = dim2;

            if (channels < 5) {
                log.error("decodeYoloOutput[{}]: invalid channel-first output channels={}", tag, channels);
                return dets;
            }

            for (int i = 0; i < boxes; i++) {
                float cx = raw[0][0][i];
                float cy = raw[0][1][i];
                float bw = raw[0][2][i];
                float bh = raw[0][3][i];

                int bestClass = -1;
                float bestScore = 0f;

                for (int c = 0; c < NUM_CLASSES; c++) {
                    int scoreChannel = 4 + c;

                    if (scoreChannel >= channels) {
                        continue;
                    }

                    float score = raw[0][scoreChannel][i];

                    if (score > bestScore) {
                        bestScore = score;
                        bestClass = c;
                    }
                }

                if (bestClass != CARD_CLASS) {
                    continue;
                }

                if (bestScore < CONF_THRESH) {
                    continue;
                }

                DetectionResult d = buildDetectionFromCxCyWh(
                        cx,
                        cy,
                        bw,
                        bh,
                        bestScore,
                        bestClass,
                        letterbox,
                        origW,
                        origH
                );

                if (d != null) {
                    dets.add(d);
                }
            }
        } else {
            // Shape: [1, boxes, channels]
            int boxes = dim1;
            int channels = dim2;

            if (channels < 5) {
                log.error("decodeYoloOutput[{}]: invalid row-first output channels={}", tag, channels);
                return dets;
            }

            boolean possibleNmsOutput = channels == 6 && boxes <= 1000;

            for (int i = 0; i < boxes; i++) {
                float[] row = raw[0][i];

                if (possibleNmsOutput) {
                    // NMS style: x1, y1, x2, y2, score, class
                    float x1 = row[0];
                    float y1 = row[1];
                    float x2 = row[2];
                    float y2 = row[3];
                    float score = row[4];
                    int classId = Math.round(row[5]);

                    if (classId != CARD_CLASS) {
                        continue;
                    }

                    if (score < CONF_THRESH) {
                        continue;
                    }

                    DetectionResult d = buildDetectionFromXyxy(
                            x1,
                            y1,
                            x2,
                            y2,
                            score,
                            classId,
                            letterbox,
                            origW,
                            origH
                    );

                    if (d != null) {
                        dets.add(d);
                    }

                    continue;
                }

                // Usual row-first YOLO: cx, cy, w, h, class_score(s)
                float cx = row[0];
                float cy = row[1];
                float bw = row[2];
                float bh = row[3];

                int bestClass = -1;
                float bestScore = 0f;

                if (channels == 5) {
                    bestClass = 0;
                    bestScore = row[4];
                } else {
                    for (int c = 0; c < NUM_CLASSES; c++) {
                        int scoreIndex = 4 + c;

                        if (scoreIndex >= channels) {
                            continue;
                        }

                        float score = row[scoreIndex];

                        if (score > bestScore) {
                            bestScore = score;
                            bestClass = c;
                        }
                    }
                }

                if (bestClass != CARD_CLASS) {
                    continue;
                }

                if (bestScore < CONF_THRESH) {
                    continue;
                }

                DetectionResult d = buildDetectionFromCxCyWh(
                        cx,
                        cy,
                        bw,
                        bh,
                        bestScore,
                        bestClass,
                        letterbox,
                        origW,
                        origH
                );

                if (d != null) {
                    dets.add(d);
                }
            }
        }

        return dets;
    }

    private DetectionResult buildDetectionFromCxCyWh(
            float cx,
            float cy,
            float bw,
            float bh,
            float score,
            int classId,
            LetterboxResult letterbox,
            int origW,
            int origH
    ) {
        float rawMax = Math.max(
                Math.max(Math.abs(cx), Math.abs(cy)),
                Math.max(Math.abs(bw), Math.abs(bh))
        );

        boolean normalized = rawMax <= 2.0f;

        if (normalized) {
            cx *= INPUT_SIZE;
            cy *= INPUT_SIZE;
            bw *= INPUT_SIZE;
            bh *= INPUT_SIZE;
        }

        float x1Input = cx - bw / 2f;
        float y1Input = cy - bh / 2f;
        float x2Input = cx + bw / 2f;
        float y2Input = cy + bh / 2f;

        return buildDetectionFromInputBox(
                x1Input,
                y1Input,
                x2Input,
                y2Input,
                score,
                classId,
                letterbox,
                origW,
                origH
        );
    }

    private DetectionResult buildDetectionFromXyxy(
            float x1,
            float y1,
            float x2,
            float y2,
            float score,
            int classId,
            LetterboxResult letterbox,
            int origW,
            int origH
    ) {
        float rawMax = Math.max(
                Math.max(Math.abs(x1), Math.abs(y1)),
                Math.max(Math.abs(x2), Math.abs(y2))
        );

        boolean normalized = rawMax <= 2.0f;

        if (normalized) {
            x1 *= INPUT_SIZE;
            y1 *= INPUT_SIZE;
            x2 *= INPUT_SIZE;
            y2 *= INPUT_SIZE;
        }

        return buildDetectionFromInputBox(
                x1,
                y1,
                x2,
                y2,
                score,
                classId,
                letterbox,
                origW,
                origH
        );
    }

    private DetectionResult buildDetectionFromInputBox(
            float x1Input,
            float y1Input,
            float x2Input,
            float y2Input,
            float score,
            int classId,
            LetterboxResult letterbox,
            int origW,
            int origH
    ) {
        float x1Mat = (x1Input - letterbox.padX) / letterbox.scale;
        float y1Mat = (y1Input - letterbox.padY) / letterbox.scale;
        float x2Mat = (x2Input - letterbox.padX) / letterbox.scale;
        float y2Mat = (y2Input - letterbox.padY) / letterbox.scale;

        int x1 = Math.round(x1Mat * origW / letterbox.srcW);
        int y1 = Math.round(y1Mat * origH / letterbox.srcH);
        int x2 = Math.round(x2Mat * origW / letterbox.srcW);
        int y2 = Math.round(y2Mat * origH / letterbox.srcH);

        x1 = clamp(x1, 0, origW - 1);
        y1 = clamp(y1, 0, origH - 1);
        x2 = clamp(x2, 0, origW);
        y2 = clamp(y2, 0, origH);

        if (x2 <= x1 || y2 <= y1) {
            return null;
        }

        DetectionResult d = new DetectionResult(x1, y1, x2, y2, score, classId);

        if (d.width() < origW * MIN_WIDTH_FRAC) {
            return null;
        }

        if (d.aspect() < MIN_ASPECT || d.aspect() > MAX_ASPECT) {
            return null;
        }

        return d;
    }

    /**
     * YOLO-style letterbox:
     * - preserves image aspect ratio
     * - resizes into 640x640
     * - pads remaining area with 114 gray
     */
    private LetterboxResult letterbox(Mat src) {
        int srcW = src.cols();
        int srcH = src.rows();

        float scale = Math.min(
                INPUT_SIZE / (float) srcW,
                INPUT_SIZE / (float) srcH
        );

        int newW = Math.max(1, Math.round(srcW * scale));
        int newH = Math.max(1, Math.round(srcH * scale));

        int padXTotal = INPUT_SIZE - newW;
        int padYTotal = INPUT_SIZE - newH;

        int left = padXTotal / 2;
        int right = padXTotal - left;
        int top = padYTotal / 2;
        int bottom = padYTotal - top;

        Mat resized = new Mat();
        resize(src, resized, new Size(newW, newH));

        Mat padded = new Mat();
        copyMakeBorder(
                resized,
                padded,
                top,
                bottom,
                left,
                right,
                BORDER_CONSTANT,
                new Scalar(114, 114, 114, 0)
        );

        resized.release();

        return new LetterboxResult(
                padded,
                scale,
                left,
                top,
                srcW,
                srcH
        );
    }

    /**
     * BGR Mat -> RGB NCHW float tensor normalized to 0..1
     */
    private float[] matToFloat(Mat mat) {
        int h = mat.rows();
        int w = mat.cols();

        byte[] raw = new byte[h * w * 3];
        mat.data().get(raw);

        float[] input = new float[3 * h * w];

        int area = h * w;

        for (int i = 0; i < area; i++) {
            int b = raw[i * 3] & 0xFF;
            int g = raw[i * 3 + 1] & 0xFF;
            int r = raw[i * 3 + 2] & 0xFF;

            input[i] = r / 255f;
            input[area + i] = g / 255f;
            input[2 * area + i] = b / 255f;
        }

        return input;
    }

    private Mat decodeImage(byte[] imageBytes) {
        try (BytePointer bp = new BytePointer(imageBytes)) {
            Mat buf = new Mat(1, imageBytes.length, CV_8UC1, bp);
            Mat img = imdecode(buf, IMREAD_COLOR);
            buf.release();
            return img;
        }
    }

    private Mat applyClahe(Mat src) {
        Mat lab = new Mat();
        cvtColor(src, lab, COLOR_BGR2Lab);

        MatVector channels = new MatVector();
        split(lab, channels);

        CLAHE clahe = createCLAHE(2.0, new Size(8, 8));

        Mat lClahe = new Mat();
        clahe.apply(channels.get(0), lClahe);
        lClahe.copyTo(channels.get(0));

        Mat merged = new Mat();
        merge(channels, merged);

        Mat result = new Mat();
        cvtColor(merged, result, COLOR_Lab2BGR);

        lab.release();
        merged.release();
        lClahe.release();

        return result;
    }

    private Mat sharpen(Mat src) {
        Mat blur = new Mat();
        GaussianBlur(src, blur, new Size(0, 0), 3);

        Mat sharp = new Mat();
        addWeighted(src, 1.5, blur, -0.5, 0, sharp);

        blur.release();

        return sharp;
    }

    private List<DetectionResult> nms(List<DetectionResult> dets, float iouThresh) {
        if (dets.isEmpty()) {
            return Collections.emptyList();
        }

        dets.sort(Comparator.comparingDouble((DetectionResult d) -> d.score).reversed());

        List<DetectionResult> kept = new ArrayList<>();
        boolean[] suppressed = new boolean[dets.size()];

        for (int i = 0; i < dets.size(); i++) {
            if (suppressed[i]) {
                continue;
            }

            DetectionResult best = dets.get(i);
            kept.add(best);

            for (int j = i + 1; j < dets.size(); j++) {
                if (!suppressed[j] && iou(best, dets.get(j)) > iouThresh) {
                    suppressed[j] = true;
                }
            }
        }

        return kept;
    }

    private float iou(DetectionResult a, DetectionResult b) {
        int ix1 = Math.max(a.x1, b.x1);
        int iy1 = Math.max(a.y1, b.y1);
        int ix2 = Math.min(a.x2, b.x2);
        int iy2 = Math.min(a.y2, b.y2);

        float interW = Math.max(0, ix2 - ix1);
        float interH = Math.max(0, iy2 - iy1);
        float inter = interW * interH;

        float areaA = Math.max(0, a.width()) * Math.max(0, a.height());
        float areaB = Math.max(0, b.width()) * Math.max(0, b.height());

        return inter / (areaA + areaB - inter + 1e-6f);
    }

    private byte[] cropRegion(byte[] imageBytes, DetectionResult d, String tag, String referenceId) {
        Mat img = null;

        try {
            img = decodeImage(imageBytes);

            if (img == null || img.empty()) {
                return null;
            }

            int pad = 0;

            int x1 = Math.max(0, d.x1 - pad);
            int y1 = Math.max(0, d.y1 - pad);
            int x2 = Math.min(img.cols(), d.x2 + pad);
            int y2 = Math.min(img.rows(), d.y2 + pad);

            int cropW = x2 - x1;
            int cropH = y2 - y1;

            if (cropW <= 0 || cropH <= 0) {
                return null;
            }

            log.info("cropRegion [{}]: score={} rawBox=({},{})->({},{}) paddedBox=({},{})->({},{}) size={}x{}", tag, f(d.score), d.x1, d.y1, d.x2, d.y2, x1, y1, x2, y2, cropW, cropH);

            if (isNicTag(tag)) {
                float ratio = cropW > cropH
                        ? cropH / (float) cropW
                        : cropW / (float) cropH;

                log.info("cropRegion [{}]: nic ratio={} allowed=0.45-0.95", tag, f(ratio));

                if (ratio < 0.45f || ratio > 0.95f) {
                    log.warn("cropRegion [{}]: rejected by NIC ratio={}", tag, f(ratio));
                    return null;
                }

                long cropArea = (long) cropW * cropH;
                long imageArea = (long) img.cols() * img.rows();

                double areaPct = 100.0 * cropArea / Math.max(1, imageArea);

                if (cropArea < imageArea * 0.05) {
                    log.warn(
                            "cropRegion [{}]: rejected small crop areaPct={}",
                            tag,
                            f(areaPct)
                    );
                    return null;
                }
            }

            Mat crop = img.apply(new Rect(x1, y1, cropW, cropH));

            BytePointer buf = new BytePointer();
            imencode(".jpg", crop, buf);

            int len = (int) (buf.limit() > 0 ? buf.limit() : buf.capacity());

            byte[] out = new byte[len];
            buf.position(0).get(out);

            buf.deallocate();
            crop.release();

            try {
                String savedPath = localPendingImageStorageService.save(referenceId, tag + "-cardCrop", out);
                log.info("cropRegion [{}]: dumped crop, path={}", tag, savedPath);
            } catch (Exception dumpEx) {
                log.warn("cropRegion [{}]: dump failed: {}", tag, dumpEx.getMessage());
            }

            return out;
        } catch (Exception e) {
            log.error("cropRegion [{}]: {}", tag, e.getMessage(), e);
            return null;
        } finally {
            if (img != null) {
                img.release();
            }
        }
    }

    private boolean isNicTag(String tag) {
        if (tag == null) {
            return false;
        }
        return tag.contains("ScannedNIC") || tag.contains("DeviceNIC") || tag.contains("SelfNIC");
    }

    private List<byte[]> detectAndCropNICContour(byte[] imageBytes, String tag, String referenceId) {
        List<byte[]> crops = new ArrayList<>();

        Mat image = null;

        try {
            image = decodeImage(imageBytes);

            if (image == null || image.empty()) {
                return crops;
            }

            long imageArea = (long) image.cols() * image.rows();

            Mat gray = new Mat();
            cvtColor(image, gray, COLOR_BGR2GRAY);

            Mat blurred = new Mat();
            GaussianBlur(gray, blurred, new Size(5, 5), 0);
            gray.release();

            Mat edges = new Mat();
            Canny(blurred, edges, 75, 200);
            blurred.release();

            MatVector contours = new MatVector();
            Mat hierarchy = new Mat();

            findContours(edges, contours, hierarchy, RETR_LIST, CHAIN_APPROX_SIMPLE);

            edges.release();
            hierarchy.release();

            int contourIdx = 0;
            for (long i = 0; i < contours.size(); i++) {
                Mat contour = contours.get(i);

                Mat contourF = new Mat();
                contour.convertTo(contourF, CV_32F);

                double peri = arcLength(contourF, true);

                Mat approx = new Mat();
                approxPolyDP(contourF, approx, 0.02 * peri, true);

                contourF.release();

                if (approx.rows() == 4) {
                    Rect rect = boundingRect(contour);

                    int x = Math.max(rect.x(), 0);
                    int y = Math.max(rect.y(), 0);
                    int w = Math.min(rect.width(), image.cols() - x);
                    int h = Math.min(rect.height(), image.rows() - y);

                    approx.release();

                    if (w <= 0 || h <= 0) {
                        continue;
                    }

                    long rectArea = (long) w * h;

                    if (rectArea < imageArea * 0.05) {
                        continue;
                    }

                    if (isNicTag(tag)) {
                        float ratio = w > h ? h / (float) w : w / (float) h;

                        if (ratio < 0.45f || ratio > 0.95f) {
                            continue;
                        }
                    }

                    Mat crop = image.apply(new Rect(x, y, w, h));

                    BytePointer buf = new BytePointer();
                    imencode(".jpg", crop, buf);

                    int len = (int) (buf.limit() > 0 ? buf.limit() : buf.capacity());

                    byte[] out = new byte[len];
                    buf.position(0).get(out);

                    buf.deallocate();
                    crop.release();

                    try {
                        localPendingImageStorageService.save(referenceId, tag + "-Contour-cardCrop-" + contourIdx, out);
                    } catch (Exception dumpEx) {
                        log.warn("detectAndCropNICContour: dump failed: {}", dumpEx.getMessage());
                    }
                    contourIdx++;

                    crops.add(out);
                } else {
                    approx.release();
                }
            }
        } catch (Exception e) {
            log.warn("detectAndCropNICContour [{}]: {}", tag, e.getMessage());
        } finally {
            if (image != null) {
                image.release();
            }
        }

        log.info("detectAndCropNICContour [{}]: found {} crop(s)", tag, crops.size());

        return crops;
    }

    private byte[] enhanceImage(byte[] imageBytes) throws Exception {
        Mat src = null;
        Mat lab = null;
        Mat cl = null;
        Mat mergedLab = null;
        Mat enhanced = null;
        Mat blurred = null;

        try {
            src = decodeImage(imageBytes);

            if (src == null || src.empty()) {
                log.warn("enhanceImage: failed to decode image");
                return imageBytes;
            }

            lab = new Mat();
            cvtColor(src, lab, COLOR_BGR2Lab);

            MatVector channels = new MatVector();
            split(lab, channels);

            CLAHE clahe = createCLAHE(4.0, new Size(8, 8));

            cl = new Mat();
            clahe.apply(channels.get(0), cl);
            channels.put(0, cl);

            mergedLab = new Mat();
            merge(channels, mergedLab);

            enhanced = new Mat();
            cvtColor(mergedLab, enhanced, COLOR_Lab2BGR);

            blurred = new Mat();
            GaussianBlur(enhanced, blurred, new Size(0, 0), 3);
            addWeighted(enhanced, 1.5, blurred, -0.5, 0, enhanced);

            BytePointer outputPointer = new BytePointer();
            imencode(".jpg", enhanced, outputPointer);

            int len = (int) (outputPointer.limit() > 0
                    ? outputPointer.limit()
                    : outputPointer.capacity());

            byte[] result = new byte[len];
            outputPointer.position(0).get(result);
            outputPointer.deallocate();

            return result;
        } finally {
            if (src != null) src.release();
            if (lab != null) lab.release();
            if (cl != null) cl.release();
            if (mergedLab != null) mergedLab.release();
            if (enhanced != null) enhanced.release();
            if (blurred != null) blurred.release();
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String f(float v) {
        return String.format(Locale.US, "%.4f", v);
    }

    private static String f(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    private static class LetterboxResult {
        final Mat image;
        final float scale;
        final int padX;
        final int padY;
        final int srcW;
        final int srcH;

        LetterboxResult(Mat image, float scale, int padX, int padY, int srcW, int srcH) {
            this.image = image;
            this.scale = scale;
            this.padX = padX;
            this.padY = padY;
            this.srcW = srcW;
            this.srcH = srcH;
        }
    }

    public static class DetectionResult {
        public int x1;
        public int y1;
        public int x2;
        public int y2;
        public float score;
        public int classId;

        public DetectionResult(int x1, int y1, int x2, int y2, float score, int classId) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.score = score;
            this.classId = classId;
        }

        public int width() {
            return x2 - x1;
        }

        public int height() {
            return y2 - y1;
        }

        public float aspect() {
            int h = Math.max(1, height());
            return width() / (float) h;
        }
    }
}
