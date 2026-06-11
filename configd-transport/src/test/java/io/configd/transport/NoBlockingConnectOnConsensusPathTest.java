package io.configd.transport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * RR-002 REGRESSION GUARD (static source scan).
 * <p>
 * Fails if a <em>deadline-less</em> blocking socket establishment call reappears
 * in the transport/consensus source. This is the structural defect behind RR-002:
 * a timeout-less {@code new Socket(host, port)} / {@code factory.createSocket(host,
 * port)} / unbounded {@code startHandshake()} reached on the single tick thread
 * froze the whole node for ~127 s when a peer black-holed SYNs.
 *
 * <h3>Why a static scan rather than a runtime watchdog</h3>
 * A runtime tripwire (e.g. a tick-duration assertion) would only fire if a test
 * happened to drive the exact black-hole path, and is inherently timing-sensitive
 * — doubly fragile on this CPU-credit-throttled 2-vCPU box where timing tests
 * already flake (RR-094). The defect, by contrast, is purely structural: it is a
 * specific shape of source code. A static scan is fully deterministic, has zero
 * timing dependence, runs in milliseconds, and catches the regression at the
 * exact site regardless of test path coverage. It directly encodes the charter
 * invariant: connect/handshake must be bounded and must not appear outside the
 * dedicated connector. The discriminating behavioural proof lives in
 * {@link TcpRaftTransportBlackholeTest} and the live drill; this guard is the
 * cheap, robust tripwire that keeps the structural fix from silently rotting.
 */
class NoBlockingConnectOnConsensusPathTest {

    /** Modules whose main sources carry consensus/request traffic on the tick thread. */
    private static final List<Path> SCAN_ROOTS = List.of(
            moduleSrc("configd-transport"),
            moduleSrc("configd-consensus-core"),
            moduleSrc("configd-server"));

    /**
     * Timeout-less {@code new Socket(host, port)} — the two-arg (and four-arg)
     * connecting constructors connect synchronously with no timeout. The
     * no-arg {@code new Socket()} (then {@code connect(addr, timeoutMs)}) is fine.
     */
    private static final Pattern NEW_SOCKET_CONNECTING =
            Pattern.compile("new\\s+Socket\\s*\\(\\s*[^)\\s]");

    /**
     * Timeout-less {@code factory.createSocket(host, port, ...)} on an
     * SSLSocketFactory — connects synchronously. The no-arg
     * {@code createSocket()} (then bounded {@code connect}) is fine.
     */
    private static final Pattern CREATE_SOCKET_CONNECTING =
            Pattern.compile("createSocket\\s*\\(\\s*[^)\\s]");

    /**
     * Any {@code startHandshake()} call. Permitted ONLY in
     * {@code TcpRaftTransport.createClientSocket}, which runs on the dedicated
     * connector thread and wraps it in {@code setSoTimeout(HANDSHAKE_TIMEOUT_MS)}.
     */
    private static final Pattern START_HANDSHAKE =
            Pattern.compile("\\.startHandshake\\s*\\(");

    @Test
    void noTimeoutLessBlockingConnectOnConsensusOrRequestPath() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path root : SCAN_ROOTS) {
            if (!Files.isDirectory(root)) {
                fail("scan root does not exist: " + root.toAbsolutePath()
                        + " — update SCAN_ROOTS for the new layout");
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                    scanFile(p, violations);
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("RR-002 regression: deadline-less blocking socket establishment on the "
                    + "consensus/request path reintroduced:\n  " + String.join("\n  ", violations)
                    + "\nUse new Socket() + connect(addr, CONNECT_TIMEOUT_MS), a bounded "
                    + "handshake on the connector thread, and keep establishment off the tick thread.");
        }
    }

    private static void scanFile(Path file, List<String> violations) throws IOException {
        String name = file.getFileName().toString();
        boolean isTransportConnector = name.equals("TcpRaftTransport.java");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String line = stripCommentsAndStrings(raw);
            if (line.isBlank()) {
                continue;
            }
            int lineNo = i + 1;

            if (matches(NEW_SOCKET_CONNECTING, line)) {
                violations.add(rel(file) + ":" + lineNo
                        + "  timeout-less connecting `new Socket(host, port)`  -> " + raw.trim());
            }
            // Connecting createSocket(...) is only a concern on an SSL factory; we
            // flag any createSocket with args and exempt the connector's own
            // bounded no-arg form (already arg-less, so it won't match).
            if (matches(CREATE_SOCKET_CONNECTING, line)) {
                violations.add(rel(file) + ":" + lineNo
                        + "  timeout-less connecting `factory.createSocket(host, port)`  -> " + raw.trim());
            }
            if (matches(START_HANDSHAKE, line) && !isTransportConnector) {
                violations.add(rel(file) + ":" + lineNo
                        + "  `startHandshake()` outside TcpRaftTransport's connector  -> " + raw.trim());
            }
        }
    }

    private static boolean matches(Pattern p, String line) {
        Matcher m = p.matcher(line);
        return m.find();
    }

    /**
     * Removes line comments, block-comment bodies (best-effort, line-local), and
     * string/char literal contents so a pattern inside a comment or a Javadoc
     * example (e.g. "new Socket(addr, port)" in prose) does not false-positive.
     */
    private static String stripCommentsAndStrings(String line) {
        StringBuilder sb = new StringBuilder(line.length());
        boolean inStr = false, inChar = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            // line comment: drop the rest
            if (!inStr && !inChar && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            }
            // block comment / javadoc star-prefixed lines: treat '*' lead and "/*" as comment
            if (!inStr && !inChar && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                break; // line-local: everything after /* on this line is comment-ish
            }
            if (!inStr && !inChar && c == '*') {
                // javadoc continuation line; if the trimmed line starts with * it's a comment
                String trimmed = line.stripLeading();
                if (trimmed.startsWith("*")) {
                    break;
                }
            }
            if (!inChar && c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inStr = !inStr;
                continue;
            }
            if (!inStr && c == '\'' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inChar = !inChar;
                continue;
            }
            if (!inStr && !inChar) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Path moduleSrc(String module) {
        // Tests run with cwd == the module dir (configd-transport). Resolve the
        // sibling module's main sources relative to the reactor root.
        Path reactorRoot = Path.of("").toAbsolutePath();
        // If we are inside a module, climb to the parent that holds the modules.
        if (!Files.isDirectory(reactorRoot.resolve(module))) {
            Path parent = reactorRoot.getParent();
            if (parent != null && Files.isDirectory(parent.resolve(module))) {
                reactorRoot = parent;
            }
        }
        return reactorRoot.resolve(module).resolve("src/main/java");
    }

    private static String rel(Path file) {
        return file.toString();
    }

    @Test
    void scanRootsResolveToRealDirectories() {
        for (Path root : SCAN_ROOTS) {
            assertTrue(Files.isDirectory(root),
                    "scan root must exist (guard would otherwise scan nothing): " + root.toAbsolutePath());
        }
    }
}
