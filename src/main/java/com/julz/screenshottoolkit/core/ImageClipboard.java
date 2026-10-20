package com.julz.screenshottoolkit.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Copies a PNG file to the system clipboard as an image.
 *
 * <p>Java's own image clipboard needs AWT, which does not run next to Minecraft's window, and the
 * window library only copies text. So each platform's own tool does it, run as a separate process:
 * PowerShell on Windows, AppleScript on macOS, and wl-copy or xclip on Linux.
 */
public final class ImageClipboard {
    private static final long TIMEOUT_SECONDS = 10;

    public enum Platform {
        WINDOWS, MAC, LINUX, OTHER;

        public static Platform current() {
            String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (name.contains("win")) {
                return WINDOWS;
            }
            if (name.contains("mac")) {
                return MAC;
            }
            if (name.contains("linux") || name.contains("bsd")) {
                return LINUX;
            }
            return OTHER;
        }
    }

    /** What happened. {@code missingTool} names the tool to install when none was found. */
    public record Result(boolean success, String missingTool, String error) {
        static final Result OK = new Result(true, null, null);

        static Result missing(String tool) {
            return new Result(false, tool, null);
        }

        static Result failed(String error) {
            return new Result(false, null, error);
        }
    }

    /** A process to run: the command line, extra environment variables and what to feed it. */
    public record Command(List<String> args, Map<String, String> environment, Path stdin) {
    }

    private ImageClipboard() {
    }

    /**
     * The command that copies the image, or null when there is none. {@code hasTool} says whether
     * a program can be found on the PATH; {@code env} is the process environment.
     */
    public static Command command(Platform platform, Path image, Map<String, String> env, Predicate<String> hasTool) {
        String file = image.toAbsolutePath().toString();
        return switch (platform) {
            // The path goes through an environment variable, so no quoting is needed. -STA is
            // required by the Windows clipboard API.
            case WINDOWS -> new Command(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-Command",
                    "Add-Type -AssemblyName System.Windows.Forms, System.Drawing; "
                            + "$image = [System.Drawing.Image]::FromFile($env:SCREENSHOT_TOOLKIT_IMAGE); "
                            + "try { [System.Windows.Forms.Clipboard]::SetImage($image) } finally { $image.Dispose() }"),
                    Map.of("SCREENSHOT_TOOLKIT_IMAGE", file), null);
            case MAC -> new Command(List.of("osascript",
                    "-e", "on run argv",
                    "-e", "set the clipboard to (read (POSIX file (item 1 of argv)) as «class PNGf»)",
                    "-e", "end run",
                    file), Map.of(), null);
            case LINUX -> {
                boolean wayland = env.containsKey("WAYLAND_DISPLAY");
                if (wayland && hasTool.test("wl-copy")) {
                    yield new Command(List.of("wl-copy", "--type", "image/png"), Map.of(), image);
                }
                if (hasTool.test("xclip")) {
                    yield new Command(List.of("xclip", "-selection", "clipboard", "-target", "image/png", "-in", file), Map.of(), null);
                }
                yield null;
            }
            case OTHER -> null;
        };
    }

    /** The tool to suggest when {@link #command} found nothing. */
    public static String missingTool(Platform platform, Map<String, String> env) {
        if (platform == Platform.LINUX) {
            return env.containsKey("WAYLAND_DISPLAY") ? "wl-clipboard" : "xclip";
        }
        return null;
    }

    /** Copies the image on the given executor. Never throws; failures come back in the result. */
    public static CompletableFuture<Result> copy(Path image, Executor executor) {
        return CompletableFuture.supplyAsync(() -> copyNow(image), executor);
    }

    public static Result copyNow(Path image) {
        Platform platform = Platform.current();
        Map<String, String> env = System.getenv();
        Command command = command(platform, image, env, ImageClipboard::onPath);
        if (command == null) {
            String tool = missingTool(platform, env);
            return tool != null ? Result.missing(tool) : Result.failed("Not supported on " + System.getProperty("os.name"));
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(command.args()).redirectErrorStream(true);
            builder.environment().putAll(command.environment());
            if (command.stdin() != null) {
                builder.redirectInput(command.stdin().toFile());
            }
            Process process = builder.start();
            // wl-copy and xclip stay in the background to serve the clipboard; they fork, so the
            // process started here still exits.
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Result.failed(command.args().getFirst() + " did not finish");
            }
            if (process.exitValue() != 0) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                return Result.failed(command.args().getFirst() + " exited with " + process.exitValue()
                        + (output.isEmpty() ? "" : ": " + output));
            }
            return Result.OK;
        } catch (IOException e) {
            return Result.failed(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("Interrupted");
        }
    }

    private static boolean onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            File file = new File(dir, program);
            if (file.isFile() && file.canExecute()) {
                return true;
            }
        }
        return false;
    }
}
