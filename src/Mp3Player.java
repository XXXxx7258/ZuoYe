import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javazoom.jl.player.Player;

/**
 * 简易 MP3 播放器，后台线程播放，支持停止/关闭。
 */
public final class Mp3Player {
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mp3-player");
        t.setDaemon(true);
        return t;
    });
    private Future<?> currentTask;
    private Player currentPlayer;
    private BufferedInputStream currentStream;

    public synchronized void play(Path file) {
        stop();
        currentTask = pool.submit(() -> {
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file.toFile()))) {
                synchronized (this) {
                    currentStream = in;
                    currentPlayer = new Player(in);
                }
                currentPlayer.play();
            } catch (Exception ignored) {
            } finally {
                synchronized (this) {
                    closePlayerQuietly();
                    currentTask = null;
                }
            }
        });
    }

    public synchronized void stop() {
        if (currentPlayer != null) {
            try {
                currentPlayer.close();
            } catch (Exception ignored) {
            }
        }
        closePlayerQuietly();
        if (currentTask != null) {
            currentTask.cancel(true);
            currentTask = null;
        }
    }

    private void closePlayerQuietly() {
        try {
            if (currentStream != null) {
                currentStream.close();
            }
        } catch (IOException ignored) {
        }
        currentStream = null;
        currentPlayer = null;
    }

    public synchronized void shutdown() {
        stop();
        pool.shutdownNow();
    }
}
