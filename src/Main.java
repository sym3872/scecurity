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

// 폴더 안 파일의 SHA-256 해시를 기준 상태와 비교하는 간단한 파일 무결성 검사 도구입니다.
public class Main {
    // 기준 상태는 감시 대상 폴더 안에 저장하며, 검사할 때는 이 파일을 제외합니다.
    private static final String BASELINE_FILE = ".fileguard-baseline.txt";
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_WATCH_SECONDS = 5;
    private static final Set<String> COMMANDS = Set.of("init", "check", "update", "watch");

    public static void main(String[] args) {
        // run()의 종료 코드를 운영체제에 전달해 스크립트에서도 결과를 확인할 수 있게 합니다.
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

        // 상대 경로를 정규화해 기준 파일과 검사 대상의 경로 표현을 일관되게 만듭니다.
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

        // 먼저 임시 파일을 완성한 뒤 교체해, 저장 도중 중단되어도 기존 기준 파일이 깨지지 않게 합니다.
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
            // 지원되는 파일 시스템에서는 원자적으로 교체합니다.
            Files.move(tempPath, baselinePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 원자적 이동을 지원하지 않는 파일 시스템도 사용할 수 있도록 일반 이동으로 한 번 더 시도합니다.
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

        // 저장 당시의 해시와 현재 다시 계산한 해시를 비교합니다.
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
            // 파일 시스템 이벤트를 받는 방식이 아니라, 매 주기마다 폴더 전체를 다시 검사합니다.
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

        // 현재 파일을 기준 파일과 비교해 새로 추가되었거나 내용이 달라진 파일을 찾습니다.
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

        // 기준에는 있었지만 현재 목록에 없는 파일은 삭제된 것으로 판단합니다.
        for (String path : baselineFiles.keySet()) {
            if (!currentFiles.containsKey(path)) {
                summary.deleted.put(path, baselineFiles.get(path));
            }
        }

        return summary;
    }

    private static Map<String, String> scanFiles(Path targetDirectory) throws IOException, NoSuchAlgorithmException {
        // TreeMap을 사용해 기준 파일과 출력 순서를 항상 경로 기준으로 일정하게 유지합니다.
        Map<String, String> files = new TreeMap<>();

        try (Stream<Path> stream = Files.walk(targetDirectory)) {
            Iterator<Path> iterator = stream
                    .filter(Files::isRegularFile)
                    // 기준 파일 자신과 저장 중 만들어지는 임시 파일은 검사 대상에서 제외합니다.
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
        // Windows와 Unix 계열에서 같은 기준 파일을 사용할 수 있도록 구분자를 '/'로 통일합니다.
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

                // 파일 이름에 '='가 포함될 수 있으므로 마지막 '='를 경계로 사용합니다.
                int separator = line.lastIndexOf('=');
                if (separator <= 0) {
                    // 손상되었거나 형식이 맞지 않는 행은 전체 검사를 중단하지 않고 건너뜁니다.
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
        // 큰 파일도 메모리에 모두 올리지 않도록 8 KiB 단위로 읽습니다.
        byte[] buffer = new byte[8192];

        try (InputStream inputStream = Files.newInputStream(file)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        byte[] hashBytes = digest.digest();
        StringBuilder hex = new StringBuilder(hashBytes.length * 2);

        // 해시 바이트를 기준 파일에 저장하기 쉬운 16진수 문자열로 변환합니다.
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
        // 각 변경 유형별로 경로와 현재(또는 기준) 해시를 보관합니다.
        private final Map<String, String> added = new TreeMap<>();
        private final Map<String, String> modified = new TreeMap<>();
        private final Map<String, String> deleted = new TreeMap<>();

        private boolean hasChanges() {
            return !added.isEmpty() || !modified.isEmpty() || !deleted.isEmpty();
        }
    }
}
