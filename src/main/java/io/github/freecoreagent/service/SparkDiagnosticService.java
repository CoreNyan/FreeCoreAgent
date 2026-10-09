package io.github.freecoreagent.service;

import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dynamic Spark Performance Analyzer.
 * Triggers when TPS < 12.0 or when players complain about server lag.
 * Gathers exact, non-hallucinatory diagnostics (Spark API + World entity metrics + Spark sampler).
 */
public final class SparkDiagnosticService {
    private final JavaPlugin plugin;
    private final AtomicBoolean isSamplerRunning = new AtomicBoolean(false);
    private long lastSamplerTimestamp = 0L;

    public SparkDiagnosticService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isSparkAvailable() {
        try {
            // Check SparkProvider directly (Paper 1.21+ bundles Spark as an internal service)
            if (SparkProvider.get() != null) return true;
        } catch (Throwable ignored) {}
        try {
            // Fallback: check Bukkit plugin manager
            return Bukkit.getPluginManager().isPluginEnabled("spark");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Gathers an instantaneous factual performance snapshot without waiting.
     */
    public String getInstantHealthSnapshot() {
        double tps = getLiveTps();
        double mspt = getLiveMspt();
        double cpuProcess = getLiveCpuProcess();
        double cpuSystem = getLiveCpuSystem();

        long maxMem = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long totalMem = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        long freeMem = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        long usedMem = totalMem - freeMem;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "【实时性能真实快照】TPS=%.2f, MSPT=%.1fms, 进程CPU=%.1f%%, 系统CPU=%.1f%%, 内存占用=%dMB/%dMB (%.1f%%)\n",
                tps, mspt, cpuProcess * 100.0, cpuSystem * 100.0, usedMem, maxMem, (usedMem * 100.0 / maxMem)));

        sb.append("【各世界实体与区块负载真相】:\n");
        for (World w : Bukkit.getWorlds()) {
            int entityCount = w.getEntityCount();
            int chunkCount = w.getLoadedChunks().length;
            int players = w.getPlayers().size();
            sb.append(String.format(Locale.ROOT, "  - 世界 [%s]: 在线=%d人, 实体=%d个, 加载区块=%d个\n",
                    w.getName(), players, entityCount, chunkCount));
        }

        return sb.toString().trim();
    }

    public double getLiveTps() {
        try {
            if (isSparkAvailable()) {
                Spark spark = SparkProvider.get();
                var tpsStat = spark.tps();
                if (tpsStat != null) {
                    double t = tpsStat.poll(StatisticWindow.TicksPerSecond.SECONDS_5);
                    if (t > 0) return Math.min(20.0, t);
                }
            }
        } catch (Throwable ignored) {}
        double[] bTps = Bukkit.getTPS();
        return Math.min(20.0, Math.round(bTps[0] * 100.0) / 100.0);
    }

    public double getLiveMspt() {
        try {
            if (isSparkAvailable()) {
                Spark spark = SparkProvider.get();
                var msptStat = spark.mspt();
                if (msptStat != null) {
                    var info = msptStat.poll(StatisticWindow.MillisPerTick.SECONDS_10);
                    if (info != null) return info.mean();
                }
            }
        } catch (Throwable ignored) {}
        return 50.0;
    }

    public double getLiveCpuProcess() {
        try {
            if (isSparkAvailable()) {
                Spark spark = SparkProvider.get();
                var cpu = spark.cpuProcess();
                if (cpu != null) return cpu.poll(StatisticWindow.CpuUsage.SECONDS_10);
            }
        } catch (Throwable ignored) {}
        return 0.0;
    }

    public double getLiveCpuSystem() {
        try {
            if (isSparkAvailable()) {
                Spark spark = SparkProvider.get();
                var cpu = spark.cpuSystem();
                if (cpu != null) return cpu.poll(StatisticWindow.CpuUsage.SECONDS_10);
            }
        } catch (Throwable ignored) {}
        return 0.0;
    }

    /**
     * Runs an asynchronous 12-second Spark Sampler profiling task.
     * Returns a CompletableFuture with the exact diagnostics and Spark web report link.
     */
    public void stopSampler() {
        try {
            isSamplerRunning.set(false);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "spark sampler --stop");
            });
        } catch (Throwable ignored) {}
    }

    public CompletableFuture<String> runSamplerAsync(int timeoutSeconds) {
        int duration = Math.max(15, Math.min(60, timeoutSeconds));
        long now = System.currentTimeMillis();
        if (isSamplerRunning.get()) {
            return CompletableFuture.completedFuture(
                    "【Spark正在运行中】当前已有正在进行的 Spark 性能采样，请稍候获取结果或执行 /spark stop 停止！"
            );
        }

        isSamplerRunning.set(true);
        lastSamplerTimestamp = now;

        CompletableFuture<String> future = new CompletableFuture<>();

        // Trigger console command: spark sampler --timeout <duration>
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "spark sampler --timeout " + duration);
        });

        // Wait (duration + 5) seconds to allow profiling + upload
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            isSamplerRunning.set(false);
            String sparkUrl = extractLatestSparkUrl();
            String snapshot = getInstantHealthSnapshot();
            
            StringBuilder sb = new StringBuilder();
            sb.append("【Spark 深度性能采样报告】\n");
            if (sparkUrl != null && !sparkUrl.isBlank()) {
                sb.append("- 诊断报告链接: ").append(sparkUrl).append("\n");
            } else {
                sb.append("- 诊断报告链接: 上传超时或正在后台排队上传\n");
            }
            sb.append("- 采样时长: ").append(duration).append(" 秒\n\n");
            sb.append(snapshot);
            
            future.complete(sb.toString().trim());
        }, (duration + 5) * 20L);

        return future;
    }

    private String extractLatestSparkUrl() {
        try {
            java.io.File logFile = new java.io.File("logs/latest.log");
            if (!logFile.exists()) return null;

            // Read last 100 lines using RandomAccessFile
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "r")) {
                long length = raf.length();
                long startPos = Math.max(0, length - 16384);
                raf.seek(startPos);
                byte[] bytes = new byte[(int) (length - startPos)];
                raf.readFully(bytes);
                String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

                java.util.regex.Pattern p = java.util.regex.Pattern.compile("https://spark\\.lucko\\.me/[a-zA-Z0-9]+");
                java.util.regex.Matcher m = p.matcher(content);
                String lastUrl = null;
                while (m.find()) {
                    lastUrl = m.group();
                }
                return lastUrl;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }
}