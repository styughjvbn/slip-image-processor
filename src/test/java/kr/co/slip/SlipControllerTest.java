package kr.co.slip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import java.io.ByteArrayInputStream;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SlipControllerTest {
    private MockMvc mvc;
    @BeforeEach void setup() {
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new SlipController(new SlipProcessor(), new com.fasterxml.jackson.databind.ObjectMapper())).build();
    }
    @Test void returnsZipWithReportAndImages() throws Exception {
        Mat image = new Mat(1000, 800, CvType.CV_8UC3, Scalar.all(255));
        MatOfByte buffer = new MatOfByte();
        try {
            Imgcodecs.imencode(".png", image, buffer);
            byte[] response = mvc.perform(multipart("/api/slips/process")
                    .file(new MockMultipartFile("file", "slip.png", "image/png", buffer.toArray())))
                    .andExpect(status().isOk()).andExpect(content().contentType("application/zip"))
                    .andReturn().getResponse().getContentAsByteArray();
            int entries = 0;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response))) {
                assertEquals("report.json", zip.getNextEntry().getName());
                assertTrue(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("correction"));
                entries++;
                while (zip.getNextEntry() != null) entries++;
            }
            assertEquals(6, entries);
        } finally { image.release(); buffer.release(); }
    }
    @Test void invalidImageAndOptionsAreBadRequests() throws Exception {
        var file = new MockMultipartFile("file", "bad.jpg", "image/jpeg", new byte[]{1, 2});
        mvc.perform(multipart("/api/slips/process").file(file)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/slips/process").file(file).param("protectedTopRows", "0"))
                .andExpect(status().isBadRequest());
    }
}
