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
    private long currentPlayId = 0;
    private long playSeq = 0;

    public synchronized boolean play(Path file) {
        if (file == null || !file.toFile().exists() || !file.toFile().canRead()) {
            return false;
        }
        stop();
        final long playId = ++playSeq;
        Future<?> submitted = pool.submit(() -> {
            BufferedInputStream in = null;
            Player localPlayer = null;
            try {
                in = new BufferedInputStream(new FileInputStream(file.toFile()));
                localPlayer = new Player(in);
                synchronized (this) {
                    currentPlayId = playId;
                    currentStream = in;
                    currentPlayer = localPlayer;
                }
                localPlayer.play();
            } catch (Exception ex) {
                System.err.println("MP3 play failed: " + ex.getMessage());
            } finally {
                synchronized (this) {
                    if (currentPlayId == playId) {
                        closeQuietly(localPlayer, in);
                        currentTask = null;
                        currentStream = null;
                        currentPlayer = null;
                        currentPlayId = 0;
                    } else {
                        closeQuietly(localPlayer, in);
                    }
                }
            }
        });
        synchronized (this) {
            currentTask = submitted;
        }
        return true;
    }

    public synchronized void stop() {
        if (currentPlayer != null) {
            try {
                currentPlayer.close();
            } catch (Exception ex) {
                System.err.println("MP3 stop failed: " + ex.getMessage());
            }
        }
        if (currentStream != null) {
            try {
                currentStream.close();
            } catch (IOException ignored) {
            }
        }
        if (currentTask != null) {
            currentTask.cancel(true);
            currentTask = null;
        }
        currentPlayer = null;
        currentStream = null;
        currentPlayId = 0;
    }

    private void closeQuietly(Player player, BufferedInputStream stream) {
        try {
            if (player != null) {
                player.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException ignored) {
        }
    }

    public synchronized void shutdown() {
        stop();
        pool.shutdownNow();
    }
}
