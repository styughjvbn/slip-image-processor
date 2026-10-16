package kr.co.slip.demo.batch;

import kr.co.slip.api.SlipImageProcessor;
import kr.co.slip.opencv.SlipProcessor;

import kr.co.slip.api.ProcessingResult;

import kr.co.slip.api.ProcessingOptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

public final class Batch {
    private Batch() {}
    public static void run(String[] args) throws Exception {
        int maxDimension = ProcessingOptions.defaults().maxDimension();
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--max-dimension=")) maxDimension = Integer.parseInt(arg.substring("--max-dimension=".length()));
            else positional.add(arg);
        }
        args = positional.toArray(String[]::new);
        ProcessingOptions options = ProcessingOptions.defaults().withMaxDimension(maxDimension);
        Path input = Path.of(args.length > 1 ? args[1] : "raw-data").toAbsolutePath().normalize();
        Path output = Path.of(args.length > 2 ? args[2] : "output").toAbsolutePath().normalize();
        if (!Files.isDirectory(input)) throw new IllegalArgumentException("Input directory does not exist: " + input);
        if (output.startsWith(input) || input.startsWith(output)) throw new IllegalArgumentException("Input and output directories must be separate");
        Files.createDirectories(output);
        SlipImageProcessor processor = new SlipProcessor();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Integer> rotations = args.length > 3 ? readRotations(Path.of(args[3]), mapper) : Map.of();
        List<Map<String, Object>> summary = new ArrayList<>();
        int failures = 0;
        try (var files = Files.list(input)) {
            for (Path path : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = path.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png|bmp|tif|tiff)$")) continue;
                long start = System.nanoTime();
                try {
                    if (Files.size(path) > 20 * 1024 * 1024) throw new IllegalArgumentException("Input exceeds 20 MiB");
                    ProcessingResult result = processor.process(Files.readAllBytes(path),
                            options.withRotation(rotations.getOrDefault(name, -1)));
                    Path directory = output.resolve(name); // retain extension to avoid stem collisions
                    Files.createDirectories(directory);
                    try (var prior = Files.list(directory)) {
                        for (Path artifact : prior.filter(p -> p.getFileName().toString().matches("(?:table-\\d+-compacted|paper-\\d+)\\.png")).toList())
                            Files.deleteIfExists(artifact);
                    }
                    for (var image : result.images().entrySet()) Files.write(directory.resolve(image.getKey()), image.getValue());
                    mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("report.json").toFile(), result.report());
                    int removed = result.report().tables().stream().mapToInt(ProcessingResult.Table::removedRows).sum();
                    summary.add(Map.of("file", name, "status", "OK", "correction", result.report().correction(),
                            "tables", result.report().tables().size(), "removedRows", removed,
                            "rotationDegrees", result.report().rotation().clockwiseDegrees(),
                            "rotationMode", result.report().rotation().mode(),
                            "papers", result.report().papers().size(),
                            "milliseconds", (System.nanoTime() - start) / 1_000_000));
                    System.out.printf("%s tables=%d removed=%d correction=%s%n", name, result.report().tables().size(), removed, result.report().correction());
                } catch (Exception e) {
                    failures++;
                    summary.add(Map.of("file", name, "status", "FAILED", "error", Objects.toString(e.getMessage(), e.getClass().getSimpleName())));
                    System.err.println(name + ": " + e.getMessage());
                }
            }
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("summary.json").toFile(), summary);
        writeGallery(output, summary);
        if (failures > 0) throw new IllegalStateException(failures + " image(s) failed; see summary.json");
    }

    static Map<String, Integer> readRotations(Path path, ObjectMapper mapper) throws Exception {
        var root = mapper.readTree(Files.readString(path));
        if (root == null || !root.isObject()) throw new IllegalArgumentException("Rotation overrides must be a JSON object");
        Map<String, Integer> result = new HashMap<>();
        var fields = root.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!entry.getValue().isIntegralNumber() || !entry.getValue().canConvertToInt())
                throw new IllegalArgumentException("Rotation must be an integer for " + entry.getKey());
            int degrees = entry.getValue().intValue();
            ProcessingOptions.defaults().withRotation(degrees); // shared validation
            result.put(entry.getKey(), degrees);
        }
        return Map.copyOf(result);
    }

    private static void writeGallery(Path output, List<Map<String, Object>> summary) throws Exception {
        StringBuilder html = new StringBuilder("""
                <!doctype html><html lang="ko"><meta charset="utf-8">
                <title>전표 처리 결과</title><style>
                body{font:16px system-ui;background:#f1f5f9;color:#172234;margin:32px}
                section{background:white;padding:24px;margin:24px 0;border-radius:12px}
                .images{display:flex;gap:16px;align-items:flex-start;overflow:auto}
                figure{margin:0;min-width:240px;max-width:32%}img{max-width:100%;max-height:650px;object-fit:contain}
                a{color:#1468a0}figcaption{margin:8px 0}h2{font-size:20px}.paper img{background:#cbd5e1}
                </style><h1>전표 처리 결과</h1>
                <p>빨간 사각형은 제거 대상 행입니다. 원문 전체는 보정 이미지에 보존되며, 빈 행은 표별 결과에서 잘라냅니다.</p>
                <p>기입 내용과 고정 인쇄의 자동 구분에는 오차가 있으므로 행 표시와 JSON을 함께 검토하세요.</p>
                <a href="summary.json">전체 처리 통계 JSON</a>
                """);
        for (Map<String, Object> item : summary) {
            String name = item.get("file").toString();
            html.append("<section><h2>").append(escape(name)).append("</h2>");
            if (!item.get("status").equals("OK")) {
                html.append("<p>실패: ").append(escape(item.get("error").toString())).append("</p></section>");
                continue;
            }
            String prefix = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20") + "/";
            html.append("<p>").append(item.get("correction")).append(" · 표 ").append(item.get("tables"))
                    .append("개 · 제거 행 ").append(item.get("removedRows"))
                    .append("개 · 회전 ").append(item.get("rotationDegrees")).append("° (").append(item.get("rotationMode"))
                    .append(") · <a href=\"").append(prefix).append("report.json\">상세 JSON</a></p><div class=\"images\">");
            for (String filename : List.of("01-corrected.png", "02-lines.png", "05-row-decisions.png", "06-paper-contours.png"))
                figure(html, prefix, filename);
            html.append("</div><h3>윤곽으로 분리한 종이 (투명 배경)</h3><div class=\"images\">");
            try (var artifacts = Files.list(output.resolve(name))) {
                for (Path artifact : artifacts.filter(p -> p.getFileName().toString().matches("paper-\\d+\\.png")).sorted().toList())
                    figure(html, prefix, artifact.getFileName().toString());
            }
            html.append("</div><h3>빈 행을 제거한 표</h3><div class=\"images\">");
            try (var artifacts = Files.list(output.resolve(name))) {
                for (Path artifact : artifacts.filter(p -> p.getFileName().toString().matches("table-\\d+-compacted\\.png")).sorted().toList())
                    figure(html, prefix, artifact.getFileName().toString());
            }
            html.append("</div></section>");
        }
        Files.writeString(output.resolve("index.html"), html.append("</html>").toString());
    }
    private static void figure(StringBuilder html, String prefix, String filename) {
        html.append(filename.startsWith("paper-") ? "<figure class=\"paper\"><figcaption>" : "<figure><figcaption>").append(filename).append("</figcaption><a href=\"").append(prefix).append(filename)
                .append("\"><img loading=\"lazy\" src=\"").append(prefix).append(filename).append("\"></a></figure>");
    }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
