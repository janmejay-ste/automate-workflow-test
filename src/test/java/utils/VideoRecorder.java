package utils;

import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-test video recorder.
 *
 * <h3>Capture strategy</h3>
 * Uses WebDriver {@link TakesScreenshot} with {@link OutputType#FILE}: Chrome writes the PNG
 * to a system temp file and Java moves it — zero BufferedImage allocation, zero ImageIO encode.
 * Effective FPS ≤ 4 (250ms delay between frames); degrades gracefully on slow systems
 * because {@code scheduleWithFixedDelay} is used instead of {@code scheduleAtFixedRate}.
 *
 * <h3>Opt-in</h3>
 * Enabled only when {@code -DrecordVideo=true}. Default {@code false}.
 * No hidden CI or headless detection — the flag alone controls behaviour.
 *
 * <h3>FAIL-only default</h3>
 * PASS frames are discarded (no FFmpeg) unless {@code -DrecordPassedVideo=true}.
 *
 * <h3>Thread isolation</h3>
 * One recorder per test thread via {@link ThreadLocal}. Each driver is a separate Chrome
 * process so {@code getScreenshotAs()} calls are naturally isolated.
 *
 * <h3>Known limitations</h3>
 * <ul>
 *   <li>Viewport-only capture: OS dialogs, browser chrome invisible (feature + limitation).</li>
 *   <li>ChromeDriver HTTP round-trip per frame: adds overhead on Remote/Grid environments.</li>
 *   <li>PNG temp-file architecture: future optimisation is {@code ffmpeg -f image2pipe} stdin pipe.</li>
 * </ul>
 */
public final class VideoRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(VideoRecorder.class);

    /** ThreadLocal — one recorder per test thread. */
    private static final ThreadLocal<VideoRecorder> CURRENT = new ThreadLocal<>();

    // ── instance state ────────────────────────────────────────────────────────

    private final String testId;
    private final TakesScreenshot screenshotter;
    private final Path framesDir;
    private final int maxFrames;

    /** Set to {@code true} in {@link #stop} before executor shutdown.
     *  Background thread checks this at the very top of {@link #captureFrame} so no
     *  screenshot is attempted after teardown begins. */
    private volatile boolean stopping  = false;
    private volatile boolean truncated = false;

    private final AtomicInteger frameCounter      = new AtomicInteger(0);
    private final AtomicInteger captureErrorCount = new AtomicInteger(0);

    private ScheduledExecutorService executor;

    // ── factory ───────────────────────────────────────────────────────────────

    /**
     * Starts a new recorder for {@code testId} and stores it in the current thread's
     * {@link ThreadLocal}. Returns {@code null} silently if:
     * <ul>
     *   <li>{@code -DrecordVideo=true} is not set</li>
     *   <li>{@code driver} does not implement {@link TakesScreenshot}</li>
     *   <li>The temp frames directory cannot be created</li>
     * </ul>
     */
    public static VideoRecorder start(String testId, WebDriver driver) {
        if (shouldDisable()) return null;
        if (!(driver instanceof TakesScreenshot)) {
            LOG.warn("[VideoRecorder] Driver does not implement TakesScreenshot — recording skipped for {}", testId);
            return null;
        }
        try {
            VideoRecorder rec = new VideoRecorder(testId, (TakesScreenshot) driver);
            CURRENT.set(rec);
            rec.startCapture();
            LOG.info("[VideoRecorder] Started for test: {}", testId);
            return rec;
        } catch (Exception e) {
            LOG.warn("[VideoRecorder] Failed to start for {}: {}", testId, e.getMessage());
            return null;
        }
    }

    /** @return the recorder for the current thread, or {@code null} if not started. */
    public static VideoRecorder current() {
        return CURRENT.get();
    }

    /** Folder name pattern for PASS recordings: {@code "testName_yyyyMMdd_HHmmss_SSS"}. */
    public static String buildPassedFolder(String testName) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        return testName + "_" + ts;
    }

    // ── constructor ───────────────────────────────────────────────────────────

    private VideoRecorder(String testId, TakesScreenshot screenshotter) throws IOException {
        this.testId       = testId;
        this.screenshotter = screenshotter;

        // Configurable max recording duration (default 10 minutes at 4 FPS)
        int maxMinutes = Integer.parseInt(System.getProperty("recordVideo.maxMinutes", "10"));
        this.maxFrames = maxMinutes * 60 * 4;   // 4 FPS × 60 s × N minutes

        // Temp frames directory: reports/temp_frames/{testId}_{HHmmss_SSS}/
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss_SSS"));
        this.framesDir = Paths.get("reports", "temp_frames",
                testId.replaceAll("[^a-zA-Z0-9_\\-]", "_") + "_" + ts);
        Files.createDirectories(framesDir);
    }

    // ── capture ───────────────────────────────────────────────────────────────

    private void startCapture() {
        // scheduleWithFixedDelay: next task starts 250ms AFTER previous one completes.
        // This means effective FPS ≤ 4 — overloaded systems degrade gracefully without
        // task stacking (which scheduleAtFixedRate would cause).
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "video-recorder-" + testId);
            t.setDaemon(true);   // daemon: JVM exit kills it without blocking
            return t;
        });
        executor.scheduleWithFixedDelay(this::captureFrame, 0L, 250L, TimeUnit.MILLISECONDS);
    }

    private void captureFrame() {
        // Hard stop — checked before any driver call so no screenshot is attempted
        // after stop() signals teardown. This is the primary guard against the driver
        // lifecycle race: if stop() sets stopping=true and the background thread is
        // between tasks, it will exit here before touching the driver again.
        if (stopping) return;

        // Max-frames guard — configurable via -DrecordVideo.maxMinutes
        if (frameCounter.get() >= maxFrames) {
            if (!truncated) {
                truncated = true;
                LOG.warn("[VideoRecorder] Max frames ({}) reached for {} — recording truncated. "
                        + "Increase limit with -DrecordVideo.maxMinutes=N", maxFrames, testId);
            }
            return;
        }

        try {
            // Chrome writes a PNG to a system temp file; we move it — no BufferedImage,
            // no ImageIO, no JPEG encode. Zero Java image processing overhead.
            File tmp  = screenshotter.getScreenshotAs(OutputType.FILE);
            Path dest = framesDir.resolve(String.format("frame_%05d.png", frameCounter.get()));
            Files.move(tmp.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
            frameCounter.incrementAndGet();   // only increment after successful write
        } catch (Exception e) {
            // Log once per 20 consecutive failures to avoid spam during page transitions
            if (captureErrorCount.incrementAndGet() % 20 == 1) {
                LOG.debug("[VideoRecorder] Frame capture error ({}): {}", testId, e.getMessage());
            }
        }
    }

    // ── stop ─────────────────────────────────────────────────────────────────

    /**
     * Stops the background capture thread, then optionally assembles an MP4.
     *
     * <p>The {@code stopping} flag is set <em>before</em> shutting down the executor and
     * {@code awaitTermination} waits for any in-flight {@code captureFrame()} to complete.
     * After this method returns, the driver is safe to use or quit by the caller.
     *
     * @param targetDir directory to write {@code recording.mp4} into (ignored if {@code assemble=false})
     * @param assemble  {@code true} → run FFmpeg and return {@link VideoResult};
     *                  {@code false} → discard frames and return {@code null} (fast path for PASS tests)
     * @return {@link VideoResult} on success, or {@code null} if assembly was skipped / failed
     */
    public VideoResult stop(Path targetDir, boolean assemble) {
        if (executor == null) return null;

        // 1. Signal background thread BEFORE shutting down the executor.
        //    Any captureFrame() already running will complete its current screenshot
        //    but will exit immediately on the next scheduling tick.
        stopping = true;

        // 2. Shut down the scheduler (no new tasks will be submitted).
        executor.shutdown();

        // 3. Wait for any in-flight captureFrame() to complete — up to 3 seconds.
        //    After awaitTermination returns, no background thread will touch the driver.
        try { executor.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        CURRENT.remove();

        int captured = frameCounter.get();
        LOG.info("[VideoRecorder] Stopped for {}. Captured {} frames (≤4fps PNG, maxFrames={})",
                testId, captured, maxFrames);

        // Fast path: no assembly requested (PASS tests by default, SKIP tests)
        if (!assemble || captured == 0) {
            deleteFramesDir();
            return null;
        }

        // FFmpeg assembly
        Path outputFile = targetDir.resolve("recording.mp4");
        if (!isFfmpegAvailable() || !assembleMp4(outputFile)) {
            deleteFramesDir();
            return null;
        }

        // Delete frames only after successful assembly; preserve on failure for forensics
        deleteFramesDir();
        return new VideoResult(outputFile.toAbsolutePath().toString(), captured, truncated);
    }

    // ── FFmpeg ────────────────────────────────────────────────────────────────

    /**
     * Checks whether FFmpeg is available in PATH by running {@code ffmpeg -version}
     * and verifying the exit code. Returns {@code false} on timeout, non-zero exit,
     * or any IOException.
     */
    private static boolean isFfmpegAvailable() {
        try {
            Process probe = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true).start();
            // ffmpeg -version output is small (~20 lines) — safe to waitFor() before draining
            // because it will never fill a 4KB pipe buffer.
            boolean finished = probe.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                probe.destroyForcibly();
                LOG.warn("[VideoRecorder] FFmpeg probe timed out after 10s");
                return false;
            }
            // Drain remaining output now that process has exited (cleanup only)
            probe.getInputStream().transferTo(OutputStream.nullOutputStream());
            if (probe.exitValue() != 0) {
                LOG.warn("[VideoRecorder] FFmpeg probe exited with code {}", probe.exitValue());
                return false;
            }
            return true;
        } catch (Exception e) {
            LOG.warn("[VideoRecorder] FFmpeg not found in PATH: {}", e.getMessage());
            return false;
        }
    }

    private boolean assembleMp4(Path output) {
        try {
            Files.createDirectories(output.getParent());
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-framerate", "4",
                    "-i",  framesDir.resolve("frame_%05d.png").toAbsolutePath().toString(),
                    "-c:v", "libx264",
                    "-pix_fmt", "yuv420p",
                    "-crf", "23",                                    // constant quality (0–51; 23 = good default)
                    "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",     // even dimensions (libx264 requirement)
                    "-movflags", "+faststart",                       // moov atom at front for streaming
                    output.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            // FFmpeg encoding produces substantial output (frame-by-frame progress lines).
            // We must drain stdout in a separate daemon thread; otherwise the pipe buffer fills
            // and FFmpeg blocks — making the 120s waitFor() timeout unreachable.
            Thread drainer = new Thread(() -> {
                try { proc.getInputStream().transferTo(OutputStream.nullOutputStream()); }
                catch (IOException ignored) {}
            }, "ffmpeg-drainer-" + testId);
            drainer.setDaemon(true);
            drainer.start();

            boolean finished = proc.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                LOG.error("[VideoRecorder] FFmpeg timed out (120s) for {} — process killed", testId);
                return false;
            }
            if (proc.exitValue() != 0) {
                LOG.error("[VideoRecorder] FFmpeg exited {} for {}", proc.exitValue(), testId);
                return false;
            }

            LOG.info("[VideoRecorder] Video saved: {} ({} frames{})",
                    output, frameCounter.get(), truncated ? ", TRUNCATED" : "");
            return true;

        } catch (Exception e) {
            LOG.error("[VideoRecorder] Assembly failed for {}: {}", testId, e.getMessage());
            return false;
        }
    }

    // ── cleanup ───────────────────────────────────────────────────────────────

    /** Recursively deletes the frames directory. Per-file exceptions are caught and logged
     *  (Windows file-lock safe) — cleanup failure never propagates. */
    private void deleteFramesDir() {
        if (framesDir == null || !Files.exists(framesDir)) return;
        try {
            Files.walkFileTree(framesDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    try { Files.delete(f); }
                    catch (IOException e) {
                        LOG.debug("[VideoRecorder] Could not delete frame {}: {}", f, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) {
                    try { Files.delete(d); }
                    catch (IOException ex) {
                        LOG.debug("[VideoRecorder] Could not delete dir {}: {}", d, ex.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warn("[VideoRecorder] Cleanup walk failed for {}: {}", framesDir, e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Feature flag — the ONLY auto-disable condition. No CI/headless detection. */
    private static boolean shouldDisable() {
        return !Boolean.parseBoolean(System.getProperty("recordVideo", "false"));
    }

    // ── result type ───────────────────────────────────────────────────────────

    /**
     * Returned by {@link #stop(Path, boolean)} when a video was successfully assembled.
     *
     * @param path      absolute path to the MP4 file
     * @param frames    number of PNG frames captured
     * @param truncated {@code true} if the recording hit the max-frame limit before the test ended
     */
    public record VideoResult(String path, int frames, boolean truncated) {}
}
