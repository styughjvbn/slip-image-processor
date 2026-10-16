package kr.co.slip.demo.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class BatchTest {
    @TempDir Path directory;
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
