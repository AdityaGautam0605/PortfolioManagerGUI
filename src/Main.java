import java.awt.Desktop;
import java.io.IOException;
import java.net.BindException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Default IDE entry point: build the React UI and open the local web application. */
public class Main {
    public static void main(String[] args) throws Exception {
        List<String> options = Arrays.asList(args);
        Path root = projectRoot();
        Path web = root.resolve("web");
        if (!options.contains("--skip-build")) {
            if (!Files.isDirectory(web.resolve("node_modules"))) {
                System.out.println("Installing web UI dependencies...");
                npm(web, "ci");
            }
            System.out.println("Building the web interface...");
            npm(web, "run", "build");
        }
        Path assets = web.resolve("dist");
        if (!Files.isRegularFile(assets.resolve("index.html"))) {
            throw new IllegalStateException("Web UI is not built. Run Main without --skip-build.");
        }
        boolean demo = options.contains("--demo");
        int port = 8080;
        boolean explicitPort = options.contains("--port");
        if (explicitPort) {
            int index = options.indexOf("--port") + 1;
            if (index == options.size()) throw new IllegalArgumentException("--port requires a port number.");
            port = Integer.parseInt(options.get(index));
        }
        WebServer server;
        try {
            server = new WebServer(port, assets, demo);
        } catch (BindException occupied) {
            if (explicitPort) throw occupied;
            // Do not reuse an existing demo server for a connected-mode launch.
            server = new WebServer(0, assets, demo);
            System.out.println("Port 8080 is already in use; opening this workspace on another available port.");
        }
        WebServer running = server;
        Runtime.getRuntime().addShutdownHook(new Thread(running::stop));
        running.start();
        URI url = URI.create("http://127.0.0.1:" + running.getPort() + "/");
        System.out.println("Portfolio web: " + url + (demo ? " (DEMO - sample data)" : " (your configured portfolio)"));
        System.out.println("Stop this run in your IDE to close the server.");
        if (!options.contains("--no-open")) {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(url);
                } else {
                    System.out.println("Open the address above in your browser.");
                }
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                System.out.println("The browser could not open automatically. Open the address above manually.");
            }
        }
    }

    private static void npm(Path directory, String... arguments) throws IOException, InterruptedException {
        // Shell text is fixed; the workspace path is passed as a working directory, never interpolated.
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        List<String> command = windows
                ? List.of("cmd.exe", "/d", "/c", "npm.cmd " + String.join(" ", arguments))
                : java.util.stream.Stream.concat(java.util.stream.Stream.of("npm"), Arrays.stream(arguments)).toList();
        Process process;
        try {
            process = new ProcessBuilder(command).directory(directory.toFile()).inheritIO().start();
        } catch (IOException e) {
            throw new IOException("Node.js/npm could not start. Install Node.js and make npm available to your IDE.", e);
        }
        int exit = process.waitFor();
        if (exit != 0) throw new IllegalStateException("Web build failed (exit " + exit + "). Check the npm output above; the app was not started.");
    }

    private static Path projectRoot() throws Exception {
        Path root = findRoot(Path.of(System.getProperty("user.dir")));
        if (root == null) root = findRoot(Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        if (root == null) throw new IllegalStateException("Cannot find web/package.json. Set the IDE working directory to the repository root.");
        return root;
    }

    private static Path findRoot(Path start) {
        for (Path candidate = start.toAbsolutePath().normalize(); candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("web/package.json"))) return candidate;
        }
        return null;
    }
}
