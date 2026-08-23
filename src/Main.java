import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

public class Main {
    private static final String BASELINE_FILE = ".fileguard-baseline.txt";
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) {
        if (args.length != 2 || (!args[0].equals("init") && !args[0].equals("check"))) {
            printUsage();
            return;
        }

        String command = args[0];
        Path targetDirectory = Path.of(args[1]).toAbsolutePath().normalize();

        if (!Files.isDirectory(targetDirectory)) {
            System.out.println("폴더를 찾을 수 없습니다: " + targetDirectory);
            return;
        }

        try {
            if (command.equals("init")) {
                createBaseline(targetDirectory);
            } else {
                checkChanges(targetDirectory);
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            System.out.println("실행 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    private static void createBaseline(Path targetDirectory) throws IOException, NoSuchAlgorithmException {
        Map<String, String> currentFiles = scanFiles(targetDirectory);
        Path baselinePath = targetDirectory.resolve(BASELINE_FILE);

        try (BufferedWriter writer = Files.newBufferedWriter(baselinePath)) {
            for (Map.Entry<String, String> entry : currentFiles.entrySet()) {
                writer.write(entry.getKey() + "=" + entry.getValue());
                writer.newLine();
            }
        }

        System.out.println("기준 파일을 생성했습니다: " + baselinePath);
        System.out.println("저장된 파일 수: " + currentFiles.size());
    }

    private static void checkChanges(Path targetDirectory) throws IOException, NoSuchAlgorithmException {
        Path baselinePath = targetDirectory.resolve(BASELINE_FILE);

        if (!Files.exists(baselinePath)) {
            System.out.println("기준 파일이 없습니다. 먼저 init 명령을 실행하세요.");
            System.out.println("예: java -cp out Main init " + targetDirectory);
            return;
        }

        Map<String, String> baselineFiles = readBaseline(baselinePath);
        Map<String, String> currentFiles = scanFiles(targetDirectory);

        int added = 0;
        int deleted = 0;
        int modified = 0;

        for (String path : currentFiles.keySet()) {
            if (!baselineFiles.containsKey(path)) {
                added++;
                printEvent("추가됨", path);
            } else if (!currentFiles.get(path).equals(baselineFiles.get(path))) {
                modified++;
                printEvent("변경됨", path);
            }
        }

        for (String path : baselineFiles.keySet()) {
            if (!currentFiles.containsKey(path)) {
                deleted++;
                printEvent("삭제됨", path);
            }
        }

        if (added == 0 && deleted == 0 && modified == 0) {
            System.out.println("변경 사항이 없습니다.");
            return;
        }

        System.out.println();
        System.out.println("요약");
        System.out.println("- 추가: " + added);
        System.out.println("- 변경: " + modified);
        System.out.println("- 삭제: " + deleted);
    }

    private static Map<String, String> scanFiles(Path targetDirectory) throws IOException, NoSuchAlgorithmException {
        Map<String, String> files = new TreeMap<>();

        try (Stream<Path> stream = Files.walk(targetDirectory)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                Path relativePath = targetDirectory.relativize(file);
                String displayPath = relativePath.toString();

                if (displayPath.equals(BASELINE_FILE)) {
                    continue;
                }

                files.put(displayPath, sha256(file));
            }
        }

        return files;
    }

    private static Map<String, String> readBaseline(Path baselinePath) throws IOException {
        Map<String, String> baseline = new TreeMap<>();

        try (BufferedReader reader = Files.newBufferedReader(baselinePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int separator = line.lastIndexOf('=');
                if (separator <= 0) {
                    continue;
                }

                String path = line.substring(0, separator);
                String hash = line.substring(separator + 1);
                baseline.put(path, hash);
            }
        }

        return baseline;
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] fileBytes = Files.readAllBytes(file);
        byte[] hashBytes = digest.digest(fileBytes);
        StringBuilder hex = new StringBuilder();

        for (byte hashByte : hashBytes) {
            hex.append(String.format("%02x", hashByte));
        }

        return hex.toString();
    }

    private static void printEvent(String type, String path) {
        System.out.println("[" + now() + "] [" + type + "] " + path);
    }

    private static String now() {
        return LocalDateTime.now().format(LOG_TIME);
    }

    private static void printUsage() {
        System.out.println("FileGuard - 파일 무결성 검사 도구");
        System.out.println();
        System.out.println("사용법");
        System.out.println("  java -cp out Main init <감시할_폴더>");
        System.out.println("  java -cp out Main check <감시할_폴더>");
        System.out.println();
        System.out.println("예시");
        System.out.println("  java -cp out Main init ./important_files");
        System.out.println("  java -cp out Main check ./important_files");
    }
}
