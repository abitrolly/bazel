// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.profiler;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.profiler.SystemNetworkStats.NetIfAddr;
import com.google.devtools.build.lib.profiler.SystemNetworkStats.NetIoCounter;
import com.google.devtools.build.lib.unix.ProcMeminfoParser;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.worker.WorkerMetric;
import com.google.devtools.build.lib.worker.WorkerMetricsCollector;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Thread to collect local resource usage data and log into JSON profile. */
public class CollectLocalResourceUsage extends Thread {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  // TODO(twerth): Make these configurable.
  private static final Duration BUCKET_DURATION = Duration.ofSeconds(1);
  private static final Duration LOCAL_RESOURCES_COLLECT_SLEEP_INTERVAL = Duration.ofMillis(200);

  private final BugReporter bugReporter;
  private final boolean collectWorkerDataInProfiler;
  private final boolean collectLoadAverage;
  private final boolean collectSystemNetworkUsage;

  private volatile boolean stopLocalUsageCollection;
  private volatile boolean profilingStarted;

  @GuardedBy("this")
  private TimeSeries localCpuUsage;

  @GuardedBy("this")
  private TimeSeries systemCpuUsage;

  @GuardedBy("this")
  private TimeSeries localMemoryUsage;

  @GuardedBy("this")
  private TimeSeries systemMemoryUsage;

  @GuardedBy("this")
  private TimeSeries workersMemoryUsage;

  @GuardedBy("this")
  private TimeSeries systemLoadAverage;

  @GuardedBy("this")
  private TimeSeries systemNetworkUpUsage;

  @GuardedBy("this")
  private TimeSeries systemNetworkDownUsage;

  private Stopwatch stopwatch;

  private final WorkerMetricsCollector workerMetricsCollector;

  CollectLocalResourceUsage(
      BugReporter bugReporter,
      WorkerMetricsCollector workerMetricsCollector,
      boolean collectWorkerDataInProfiler,
      boolean collectLoadAverage,
      boolean collectSystemNetworkUsage) {
    this.bugReporter = checkNotNull(bugReporter);
    this.collectWorkerDataInProfiler = collectWorkerDataInProfiler;
    this.workerMetricsCollector = workerMetricsCollector;
    this.collectLoadAverage = collectLoadAverage;
    this.collectSystemNetworkUsage = collectSystemNetworkUsage;
  }

  @Override
  public void run() {
    ImmutableSet<String> localLoopbackInterfaces;
    if (collectSystemNetworkUsage) {
      localLoopbackInterfaces = getLocalLoopbackInterfaces();
    } else {
      localLoopbackInterfaces = ImmutableSet.of();
    }
    int numProcessors = Runtime.getRuntime().availableProcessors();
    stopwatch = Stopwatch.createStarted();
    synchronized (this) {
      localCpuUsage =
          new TimeSeries(
              /* startTimeMillis= */ stopwatch.elapsed().toMillis(), BUCKET_DURATION.toMillis());
      localMemoryUsage =
          new TimeSeries(
              /* startTimeMillis= */ stopwatch.elapsed().toMillis(), BUCKET_DURATION.toMillis());
      systemCpuUsage =
          new TimeSeries(
              /* startTimeMillis= */ stopwatch.elapsed().toMillis(), BUCKET_DURATION.toMillis());
      systemMemoryUsage =
          new TimeSeries(
              /* startTimeMillis= */ stopwatch.elapsed().toMillis(), BUCKET_DURATION.toMillis());
      if (collectWorkerDataInProfiler) {
        workersMemoryUsage =
            new TimeSeries(
                /* startTimeMillis= */ stopwatch.elapsed().toMillis(), BUCKET_DURATION.toMillis());
      }
      if (collectLoadAverage) {
        systemLoadAverage =
            new TimeSeries(
                /* startTimeMillis= */ stopwatch.elapsed().toMillis(), BUCKET_DURATION.toMillis());
      }
      if (collectSystemNetworkUsage) {
        systemNetworkUpUsage =
            new TimeSeries(
                /* startTimeMillis= */ stopwatch.elapsed().toMillis(), BUCKET_DURATION.toMillis());
        systemNetworkDownUsage =
            new TimeSeries(
                /* startTimeMillis= */ stopwatch.elapsed().toMillis(), BUCKET_DURATION.toMillis());
      }
    }
    OperatingSystemMXBean osBean =
        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    Duration previousElapsed = stopwatch.elapsed();
    long previousCpuTimeNanos = osBean.getProcessCpuTime();
    Map<String, NetIoCounter> previousNetworkIoCounters = null;
    profilingStarted = true;
    while (!stopLocalUsageCollection) {
      try {
        Thread.sleep(LOCAL_RESOURCES_COLLECT_SLEEP_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        return;
      }
      Duration nextElapsed = stopwatch.elapsed();
      long nextCpuTimeNanos = osBean.getProcessCpuTime();

      double systemCpuLoad = osBean.getSystemCpuLoad();
      double systemUsage = systemCpuLoad * numProcessors;

      long systemMemoryUsageMb = -1;
      if (OS.getCurrent() == OS.LINUX) {
        // On Linux we get a better estimate by using /proc/meminfo. See
        // https://www.linuxatemyram.com/ for more info on buffer caches.
        try {
          ProcMeminfoParser procMeminfoParser = new ProcMeminfoParser("/proc/meminfo");
          systemMemoryUsageMb =
              (procMeminfoParser.getTotalKb() - procMeminfoParser.getFreeRamKb()) / 1024;
        } catch (IOException e) {
          // Silently ignore and fallback.
        }
      }
      if (systemMemoryUsageMb <= 0) {
        // In case we aren't running on Linux or /proc/meminfo parsing went wrong, fall back to the
        // OS bean.
        systemMemoryUsageMb =
            (osBean.getTotalPhysicalMemorySize() - osBean.getFreePhysicalMemorySize())
                / (1024 * 1024);
      }

      long memoryUsage;
      try {
        memoryUsage =
            memoryBean.getHeapMemoryUsage().getUsed()
                + memoryBean.getNonHeapMemoryUsage().getUsed();
      } catch (IllegalArgumentException e) {
        // The JVM may report committed > max. See b/180619163.
        bugReporter.sendBugReport(e);
        memoryUsage = -1;
      }

      int workerMemoryUsageMb = 0;
      if (collectWorkerDataInProfiler) {
        workerMemoryUsageMb =
            this.workerMetricsCollector.collectMetrics().stream()
                    .map(WorkerMetric::getWorkerStat)
                    .filter(Objects::nonNull)
                    .mapToInt(WorkerMetric.WorkerStat::getUsedMemoryInKB)
                    .sum()
                / 1024;
      }
      double loadAverage = 0;
      if (collectLoadAverage) {
        loadAverage = osBean.getSystemLoadAverage();
      }

      double deltaNanos = nextElapsed.minus(previousElapsed).toNanos();
      double cpuLevel = (nextCpuTimeNanos - previousCpuTimeNanos) / deltaNanos;

      Map<String, NetIoCounter> nextNetworkIoCounters = null;
      if (collectSystemNetworkUsage) {
        try {
          nextNetworkIoCounters = SystemNetworkStats.getNetIoCounters();
        } catch (IOException e) {
          logger.atWarning().withCause(e).log("Failed to get Net IO counters");
        }
        if (previousNetworkIoCounters == null) {
          previousNetworkIoCounters = nextNetworkIoCounters;
        }
      }

      synchronized (this) {
        if (localCpuUsage != null) {
          localCpuUsage.addRange(previousElapsed.toMillis(), nextElapsed.toMillis(), cpuLevel);
        }
        if (localMemoryUsage != null && memoryUsage != -1) {
          long memoryUsageMb = memoryUsage / (1024 * 1024);
          localMemoryUsage.addRange(
              previousElapsed.toMillis(), nextElapsed.toMillis(), (double) memoryUsageMb);
        }
        if (systemCpuUsage != null) {
          systemCpuUsage.addRange(previousElapsed.toMillis(), nextElapsed.toMillis(), systemUsage);
        }
        if (systemMemoryUsage != null) {
          systemMemoryUsage.addRange(
              previousElapsed.toMillis(), nextElapsed.toMillis(), (double) systemMemoryUsageMb);
        }
        if (collectWorkerDataInProfiler && (workersMemoryUsage != null)) {
          workersMemoryUsage.addRange(
              previousElapsed.toMillis(), nextElapsed.toMillis(), workerMemoryUsageMb);
        }
        if (collectLoadAverage && loadAverage > 0) {
          systemLoadAverage.addRange(
              previousElapsed.toMillis(), nextElapsed.toMillis(), loadAverage);
        }
        if (collectSystemNetworkUsage
            && previousNetworkIoCounters != null
            && nextNetworkIoCounters != null) {
          AggregatedNetIoCounter aggregated =
              aggregateNetIoCounter(
                  previousNetworkIoCounters,
                  nextNetworkIoCounters,
                  deltaNanos,
                  localLoopbackInterfaces);
          systemNetworkUpUsage.addRange(
              previousElapsed.toMillis(), nextElapsed.toMillis(), aggregated.upMbps);
          systemNetworkDownUsage.addRange(
              previousElapsed.toMillis(), nextElapsed.toMillis(), aggregated.downMbps);
        }
      }
      previousElapsed = nextElapsed;
      previousCpuTimeNanos = nextCpuTimeNanos;
      previousNetworkIoCounters = nextNetworkIoCounters;
    }
  }

  public void stopCollecting() {
    Preconditions.checkArgument(!stopLocalUsageCollection);
    stopLocalUsageCollection = true;
    interrupt();
  }

  synchronized void logCollectedData() {
    if (!profilingStarted) {
      return;
    }
    Preconditions.checkArgument(stopLocalUsageCollection);
    long endTimeNanos = System.nanoTime();
    long elapsedNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
    long startTimeNanos = endTimeNanos - elapsedNanos;
    int len = (int) (elapsedNanos / BUCKET_DURATION.toNanos()) + 1;
    Profiler profiler = Profiler.instance();

    logCollectedData(profiler, localCpuUsage, ProfilerTask.LOCAL_CPU_USAGE, startTimeNanos, len);
    localCpuUsage = null;

    logCollectedData(
        profiler, localMemoryUsage, ProfilerTask.LOCAL_MEMORY_USAGE, startTimeNanos, len);
    localMemoryUsage = null;

    logCollectedData(profiler, systemCpuUsage, ProfilerTask.SYSTEM_CPU_USAGE, startTimeNanos, len);
    systemCpuUsage = null;

    logCollectedData(
        profiler, systemMemoryUsage, ProfilerTask.SYSTEM_MEMORY_USAGE, startTimeNanos, len);
    systemMemoryUsage = null;

    if (collectWorkerDataInProfiler) {
      logCollectedData(
          profiler, workersMemoryUsage, ProfilerTask.WORKERS_MEMORY_USAGE, startTimeNanos, len);
    }
    workersMemoryUsage = null;

    if (collectLoadAverage) {
      logCollectedData(
          profiler, systemLoadAverage, ProfilerTask.SYSTEM_LOAD_AVERAGE, startTimeNanos, len);
    }
    systemLoadAverage = null;

    if (collectSystemNetworkUsage) {
      logCollectedData(
          profiler,
          systemNetworkUpUsage,
          ProfilerTask.SYSTEM_NETWORK_UP_USAGE,
          startTimeNanos,
          len);
      logCollectedData(
          profiler,
          systemNetworkDownUsage,
          ProfilerTask.SYSTEM_NETWORK_DOWN_USAGE,
          startTimeNanos,
          len);
    }
    systemNetworkUpUsage = null;
    systemNetworkDownUsage = null;
  }

  private static void logCollectedData(
      Profiler profiler, TimeSeries timeSeries, ProfilerTask type, long startTimeNanos, int len) {
    double[] localResourceValues = timeSeries.toDoubleArray(len);
    for (int i = 0; i < len; i++) {
      long eventTimeNanos = startTimeNanos + i * BUCKET_DURATION.toNanos();
      profiler.logEventAtTime(eventTimeNanos, type, String.valueOf(localResourceValues[i]));
    }
  }

  private boolean isLocalLoopback(List<NetIfAddr> addresses) {
    for (NetIfAddr addr : addresses) {
      switch (addr.family()) {
        case AF_INET:
          if (addr.ipAddr().equals("127.0.0.1")) {
            return true;
          }
          break;
        case AF_INET6:
          if (addr.ipAddr().equals("::1")) {
            return true;
          }
          break;
        case UNKNOWN:
      }
    }
    return false;
  }

  private ImmutableSet<String> getLocalLoopbackInterfaces() {
    ImmutableSet.Builder<String> result = ImmutableSet.builder();
    try {
      for (Map.Entry<String, List<NetIfAddr>> entry :
          SystemNetworkStats.getNetIfAddrs().entrySet()) {
        if (isLocalLoopback(entry.getValue())) {
          result.add(entry.getKey());
        }
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Failed to query network interfaces");
    }
    return result.build();
  }

  static class AggregatedNetIoCounter {
    private final double upMbps;
    private final double downMbps;

    AggregatedNetIoCounter(double upMbps, double downMbps) {
      this.upMbps = upMbps;
      this.downMbps = downMbps;
    }
  }

  private AggregatedNetIoCounter aggregateNetIoCounter(
      Map<String, NetIoCounter> previousNetIoCounters,
      Map<String, NetIoCounter> nextNetIoCounters,
      double deltaNanos,
      Set<String> excludedInterfaces) {
    long deltaBytesSent = 0;
    long deltaBytesRecv = 0;
    for (Map.Entry<String, NetIoCounter> entry : previousNetIoCounters.entrySet()) {
      String name = entry.getKey();
      if (excludedInterfaces.contains(name)) {
        continue;
      }
      NetIoCounter previous = entry.getValue();
      NetIoCounter next = nextNetIoCounters.get(name);
      deltaBytesSent += calcDeltaBytes(previous.bytesSent(), next.bytesSent());
      deltaBytesRecv += calcDeltaBytes(previous.bytesRecv(), next.bytesRecv());
    }
    double upMbps = calcNetworkMbps(deltaBytesSent, deltaNanos);
    double downMbps = calcNetworkMbps(deltaBytesRecv, deltaNanos);
    return new AggregatedNetIoCounter(upMbps, downMbps);
  }

  private long calcDeltaBytes(long prevBytes, long nextBytes) {
    // The nextBytes could wrap, and if that happens, assume prevBytes is 0 (best effort).
    if (nextBytes < prevBytes) {
      return nextBytes;
    } else {
      return nextBytes - prevBytes;
    }
  }

  private double calcNetworkMbps(long deltaBytes, double deltaNanos) {
    return deltaBytes / deltaNanos * 8000;
  }
}
