import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

public class Main {
    private static final String BASELINE_FILE = ".fileguard-baseline.txt";
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_WATCH_SECONDS = 5;
    private static final Set<String> COMMANDS = Set.of("init", "check", "update", "watch");

    public static void main(String[] args) {
        int exitCode = run(args);

        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int run(String[] args) {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            printUsage();
            return 0;
        }

        String command = args[0];
        if (!COMMANDS.contains(command)) {
            System.out.println("알 수 없는 명령입니다: " + command);
            printUsage();
            return 2;
        }

        if (args.length < 2) {
            System.out.println("검사할 폴더를 입력하세요.");
            printUsage();
            return 2;
        }

        Path targetDirectory = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isDirectory(targetDirectory)) {
            System.out.println("폴더를 찾을 수 없습니다: " + targetDirectory);
            return 2;
        }

        try {
            if (command.equals("init")) {
                createBaseline(targetDirectory);
                return 0;
            }

            if (command.equals("update")) {
                createBaseline(targetDirectory);
                System.out.println("기준 상태를 현재 파일 상태로 갱신했습니다.");
                return 0;
            }

            if (command.equals("watch")) {
                int seconds = parseWatchSeconds(args);
                watchChanges(targetDirectory, seconds);
                return 0;
            }

            ChangeSummary summary = checkChanges(targetDirectory, true);
            return summary.hasChanges() ? 1 : 0;
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            return 2;
        } catch (IOException | NoSuchAlgorithmException e) {
            System.out.println("실행 중 오류가 발생했습니다: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("감시를 중단했습니다.");
            return 130;
        }
    }

    private static void createBaseline(Path targetDirectory) throws IOException, NoSuchAlgorithmException {
        Map<String, String> currentFiles = scanFiles(targetDirectory);
        Path baselinePath = targetDirectory.resolve(BASELINE_FILE);
        Path tempPath = targetDirectory.resolve(BASELINE_FILE + ".tmp");

        try (BufferedWriter writer = Files.newBufferedWriter(tempPath)) {
            writer.write("# FileGuard baseline");
            writer.newLine();
            writer.write("# format: relativePath=sha256");
            writer.newLine();

            for (Map.Entry<String, String> entry : currentFiles.entrySet()) {
                writer.write(entry.getKey() + "=" + entry.getValue());
                writer.newLine();
            }
        }

        try {
            Files.move(tempPath, baselinePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tempPath, baselinePath, StandardCopyOption.REPLACE_EXISTING);
        }

        System.out.println("기준 파일을 생성했습니다: " + baselinePath);
        System.out.println("저장된 파일 수: " + currentFiles.size());
    }

    private static ChangeSummary checkChanges(Path targetDirectory, boolean printNoChanges)
            throws IOException, NoSuchAlgorithmException {
        Path baselinePath = targetDirectory.resolve(BASELINE_FILE);

        if (!Files.exists(baselinePath)) {
            throw new IllegalArgumentException("기준 파일이 없습니다. 먼저 init 명령을 실행하세요.\n예: java -cp out Main init "
                    + targetDirectory);
        }

        Map<String, String> baselineFiles = readBaseline(baselinePath);
        Map<String, String> currentFiles = scanFiles(targetDirectory);
        ChangeSummary summary = compareFiles(baselineFiles, currentFiles);

        if (!summary.hasChanges()) {
            if (printNoChanges) {
                System.out.println("변경 사항이 없습니다.");
            }
            return summary;
        }

        for (String path : summary.added.keySet()) {
            printEvent("추가됨", path);
        }

        for (String path : summary.modified.keySet()) {
            printEvent("변경됨", path);
        }

        for (String path : summary.deleted.keySet()) {
            printEvent("삭제됨", path);
        }

        printSummary(summary);
        return summary;
    }

    private static void watchChanges(Path targetDirectory, int seconds)
            throws IOException, NoSuchAlgorithmException, InterruptedException {
        System.out.println("파일 변경 감시를 시작합니다. 종료하려면 Ctrl+C를 누르세요.");
        System.out.println("검사 주기: " + seconds + "초");

        while (true) {
            ChangeSummary summary = checkChanges(targetDirectory, false);
            if (!summary.hasChanges()) {
                System.out.println("[" + now() + "] 변경 사항이 없습니다.");
            }

            Thread.sleep(seconds * 1000L);
        }
    }

    private static int parseWatchSeconds(String[] args) {
        if (args.length < 3) {
            return DEFAULT_WATCH_SECONDS;
        }

        try {
            int seconds = Integer.parseInt(args[2]);
            if (seconds < 1) {
                throw new NumberFormatException("watch seconds must be positive");
            }
            return seconds;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("watch 주기는 1 이상의 초 단위 숫자로 입력하세요.");
        }
    }

    private static ChangeSummary compareFiles(Map<String, String> baselineFiles, Map<String, String> currentFiles) {
        ChangeSummary summary = new ChangeSummary();

        for (Map.Entry<String, String> entry : currentFiles.entrySet()) {
            String path = entry.getKey();
            String hash = entry.getValue();
            String baselineHash = baselineFiles.get(path);

            if (baselineHash == null) {
                summary.added.put(path, hash);
            } else if (!hash.equals(baselineHash)) {
                summary.modified.put(path, hash);
            }
        }

        for (String path : baselineFiles.keySet()) {
            if (!currentFiles.containsKey(path)) {
                summary.deleted.put(path, baselineFiles.get(path));
            }
        }

        return summary;
    }

    private static Map<String, String> scanFiles(Path targetDirectory) throws IOException, NoSuchAlgorithmException {
        Map<String, String> files = new TreeMap<>();

        try (Stream<Path> stream = Files.walk(targetDirectory)) {
            Iterator<Path> iterator = stream
                    .filter(Files::isRegularFile)
                    .filter(file -> !isBaselineFile(targetDirectory, file))
                    .iterator();

            while (iterator.hasNext()) {
                Path file = iterator.next();
                String displayPath = normalizeRelativePath(targetDirectory.relativize(file));
                files.put(displayPath, sha256(file));
            }
        }

        return files;
    }

    private static boolean isBaselineFile(Path targetDirectory, Path file) {
        String relativePath = normalizeRelativePath(targetDirectory.relativize(file));
        return relativePath.equals(BASELINE_FILE) || relativePath.equals(BASELINE_FILE + ".tmp");
    }

    private static String normalizeRelativePath(Path relativePath) {
        return relativePath.toString().replace('\\', '/');
    }

    private static Map<String, String> readBaseline(Path baselinePath) throws IOException {
        Map<String, String> baseline = new TreeMap<>();

        try (BufferedReader reader = Files.newBufferedReader(baselinePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }

                int separator = line.lastIndexOf('=');
                if (separator <= 0) {
                    System.out.println("무시한 잘못된 기준 파일 행: " + line);
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
        byte[] buffer = new byte[8192];

        try (InputStream inputStream = Files.newInputStream(file)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        byte[] hashBytes = digest.digest();
        StringBuilder hex = new StringBuilder(hashBytes.length * 2);

        for (byte hashByte : hashBytes) {
            hex.append(String.format("%02x", hashByte));
        }

        return hex.toString();
    }

    private static void printEvent(String type, String path) {
        System.out.println("[" + now() + "] [" + type + "] " + path);
    }

    private static void printSummary(ChangeSummary summary) {
        System.out.println();
        System.out.println("요약");
        System.out.println("- 추가: " + summary.added.size());
        System.out.println("- 변경: " + summary.modified.size());
        System.out.println("- 삭제: " + summary.deleted.size());
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
        System.out.println("  java -cp out Main update <감시할_폴더>");
        System.out.println("  java -cp out Main watch <감시할_폴더> [초]");
        System.out.println();
        System.out.println("명령");
        System.out.println("  init    현재 파일 상태를 기준 상태로 저장합니다.");
        System.out.println("  check   기준 상태와 현재 상태를 비교합니다. 변경이 있으면 종료 코드 1을 반환합니다.");
        System.out.println("  update  기준 상태를 현재 파일 상태로 다시 저장합니다.");
        System.out.println("  watch   일정 주기마다 변경 사항을 반복 검사합니다.");
        System.out.println();
        System.out.println("예시");
        System.out.println("  java -cp out Main init ./important_files");
        System.out.println("  java -cp out Main check ./important_files");
        System.out.println("  java -cp out Main watch ./important_files 10");
    }

    private static class ChangeSummary {
        private final Map<String, String> added = new TreeMap<>();
        private final Map<String, String> modified = new TreeMap<>();
        private final Map<String, String> deleted = new TreeMap<>();

        private boolean hasChanges() {
            return !added.isEmpty() || !modified.isEmpty() || !deleted.isEmpty();
        }
    }
}
