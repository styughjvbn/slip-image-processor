package kr.co.slip.demo.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class BatchTest {
    @TempDir Path directory;
    @Test void parallelBatchSupportsZeroQueueAndRemovesStaleDiagnosticsWhenModeChanges() throws Exception {
        kr.co.slip.opencv.NativeOpenCv.load();
        Path input = Files.createDirectory(directory.resolve("images")), output = directory.resolve("result");
        org.opencv.core.Mat image = new org.opencv.core.Mat(800, 600, org.opencv.core.CvType.CV_8UC3, org.opencv.core.Scalar.all(255));
        org.opencv.core.MatOfByte encoded = new org.opencv.core.MatOfByte();
        try {
            org.opencv.imgcodecs.Imgcodecs.imencode(".png", image, encoded);
            for (String name : new String[]{"b.png", "a.png", "c.png"}) Files.write(input.resolve(name), encoded.toArray());
        } finally { image.release(); encoded.release(); }
        Batch.run(new String[]{"--batch", input.toString(), output.toString(), "--workers=2", "--queue-capacity=0"});
        assertTrue(Files.exists(output.resolve("a.png/02-lines.png")));
        Files.writeString(output.resolve("a.png/unrelated.txt"), "keep");
        Batch.run(new String[]{"--batch", input.toString(), output.toString(), "--workers=2", "--queue-capacity=1", "--output-mode=PRODUCTION"});
        var summary = new ObjectMapper().readTree(Files.readString(output.resolve("summary.json")));
        assertEquals(3, summary.size()); assertEquals("a.png", summary.get(0).get("file").asText());
        for (var item : summary) assertEquals("OK", item.get("status").asText());
        assertFalse(Files.exists(output.resolve("a.png/02-lines.png")));
        assertTrue(Files.exists(output.resolve("a.png/unrelated.txt")));
        assertFalse(Files.readString(output.resolve("index.html")).contains("src=\"a.png/02-lines.png"));
    }
    @Test void acceptsSizeFlagWithoutRotationFileAndRejectsInvalidSizeBeforeWriting() throws Exception {
        Path input = Files.createDirectory(directory.resolve("input"));
        Path output = directory.resolve("output");
        Batch.run(new String[]{"--batch", input.toString(), output.toString(), "--max-dimension=1100"});
        assertTrue(Files.exists(output.resolve("summary.json")));
        Path invalidOutput = directory.resolve("invalid");
        assertThrows(IllegalArgumentException.class, () -> Batch.run(new String[]{"--batch", input.toString(),
                invalidOutput.toString(), "--max-dimension=3001"}));
        assertFalse(Files.exists(invalidOutput));
    }
    @Test void readsAndValidatesPerFileRotation() throws Exception {
        Path file = directory.resolve("rotations.json");
        ObjectMapper mapper = new ObjectMapper();
        Files.writeString(file, "{\"a.jpg\":90,\"b.jpg\":180,\"c.jpg\":-1}");
        assertEquals(90, Batch.readRotations(file, mapper).get("a.jpg"));
        for (String invalid : new String[]{"{\"a.jpg\":45}", "{\"a.jpg\":90.5}", "[]", "null"}) {
            Files.writeString(file, invalid);
            assertThrows(IllegalArgumentException.class, () -> Batch.readRotations(file, mapper));
        }
    }
}
