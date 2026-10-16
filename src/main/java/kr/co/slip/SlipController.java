package kr.co.slip;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.util.zip.*;

@RestController
@RequestMapping("/api/slips")
public class SlipController {
    private final SlipProcessor processor;
    private final ObjectMapper mapper;
    // Image processing is CPU/native-memory intensive. Bound concurrent requests per JVM.
    private final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(2);
    public SlipController(SlipProcessor processor, ObjectMapper mapper) { this.processor = processor; this.mapper = mapper; }

    @PostMapping(value = "/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/zip")
    public ResponseEntity<byte[]> process(@RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean ignoreRepeatedPrint,
            @RequestParam(defaultValue = "2") int protectedTopRows) throws IOException {
        if (!slots.tryAcquire()) return ResponseEntity.status(429).header("Retry-After", "5").build();
        try {
            ProcessingOptions defaults = ProcessingOptions.defaults();
            ProcessingResult result = processor.process(file.getBytes(), new ProcessingOptions(defaults.maxDimension(),
                    protectedTopRows, ignoreRepeatedPrint, defaults.minimumInkRatio(), defaults.minimumComponentArea()));
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
                zip.putNextEntry(new ZipEntry("report.json"));
                zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result.report()));
                zip.closeEntry();
                for (var image : result.images().entrySet()) {
                    zip.putNextEntry(new ZipEntry(image.getKey())); zip.write(image.getValue()); zip.closeEntry();
                }
            }
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=slip-result.zip")
                    .contentType(MediaType.parseMediaType("application/zip")).body(buffer.toByteArray());
        } finally { slots.release(); }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> badInput(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage()));
    }
}
