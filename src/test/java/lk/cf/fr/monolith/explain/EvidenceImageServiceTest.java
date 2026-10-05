package lk.cf.fr.monolith.explain;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.cf.fr.monolith.persistence.entity.RegistrationRecord;
import lk.cf.fr.monolith.persistence.repository.RegistrationRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvidenceImageServiceTest {

    @TempDir
    Path data;

    private RegistrationRecordRepository repository;
    private EvidenceImageService service;

    @BeforeEach
    void setUp() {
        repository = mock(RegistrationRecordRepository.class);
        service = new EvidenceImageService(repository, new ObjectMapper(),
                data.resolve("pending-registrations").toString(),
                data.resolve("card-crops").toString(),
                data.resolve("comparison-analysis").toString(),
                data.toString(),
                data.resolve("evaluation-runs").toString());
    }

    private static void touch(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2});
    }

    private RegistrationRecord replay(String ref) {
        RegistrationRecord r = new RegistrationRecord();
        r.setReferenceId(ref);
        r.setStatus("EVALUATION");
        when(repository.findByReferenceId(ref)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    @DisplayName("An old replay row with no refs is located via run-meta, even from another mount point")
    void replayWithoutRefsIsFoundThroughRunMeta() throws Exception {
        Path sample = data.resolve("corpus-final").resolve("madhuka-G01");
        touch(sample.resolve("nicImage.jpg"));
        touch(sample.resolve("faceImage.jpg"));
        touch(sample.resolve("selfImage.jpg"));
        Files.createDirectories(data.resolve("evaluation-runs/final-70"));
        Files.writeString(data.resolve("evaluation-runs/final-70/run-meta.json"),
                "{\"corpusDir\":\"/some/other/mount/data/corpus-final\"}");
        // A shorter run id that is also a prefix must not win.
        Files.createDirectories(data.resolve("evaluation-runs/final"));

        touch(data.resolve("card-crops/eval-final-70-madhuka-G01/DeviceNIC-cardCrop.jpg"));
        touch(data.resolve("comparison-analysis/eval-final-70-madhuka-G01/cmp3-faceVsSelfFace-source.jpg"));
        touch(data.resolve("comparison-analysis/eval-final-70-madhuka-G01/cmp3-faceVsSelfFace-target.jpg"));
        replay("eval-final-70-madhuka-G01");

        List<String> keys = service.list("eval-final-70-madhuka-G01").stream().map(EvidenceImage::key).toList();

        assertEquals(List.of("capture.nic", "capture.face", "capture.self", "crop.deviceNic",
                "cmp3.source", "cmp3.target"), keys);
        assertEquals(4, service.read("eval-final-70-madhuka-G01", "capture.face").length);
    }

    @Test
    @DisplayName("Comparison pairs are tied to the evidence row they explain")
    void pairsCarryTheirEvidenceId() throws Exception {
        touch(data.resolve("comparison-analysis/ref-1/cmp5-scannedNicVsDeviceNic-source.jpg"));
        RegistrationRecord r = new RegistrationRecord();
        r.setReferenceId("ref-1");
        when(repository.findByReferenceId("ref-1")).thenReturn(Optional.of(r));

        EvidenceImage image = service.list("ref-1").get(0);

        assertEquals("face.cmp5", image.evidenceId());
        assertEquals("SOURCE", image.role());
        assertEquals("PAIR", image.group());
    }

    @Test
    @DisplayName("Only catalogue keys are served, and stored refs outside the image roots are ignored")
    void refusesAnythingOutsideTheCatalogueOrRoots() throws Exception {
        Path outside = Files.createTempFile("not-an-image", ".jpg");
        RegistrationRecord r = replay("eval-x-y");
        r.setFaceImageRef(outside.toString());

        assertThrows(ResponseStatusException.class, () -> service.read("eval-x-y", "../../etc/passwd"));
        assertThrows(ResponseStatusException.class, () -> service.read("eval-x-y", "capture.face"));
        assertTrue(service.list("eval-x-y").isEmpty());
        Files.deleteIfExists(outside);
    }
}
