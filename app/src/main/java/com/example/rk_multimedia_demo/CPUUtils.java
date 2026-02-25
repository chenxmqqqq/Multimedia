package com.example.rk_multimedia_demo;

import android.util.Log;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class CPUUtils {
    private static final String TAG = "CPUUtils";
    private long lastTotalJiffies = 0;
    private long lastActiveJiffies = 0;
    private int cpuCoreCount = 8; // 8核设备

    public CPUUtils() {
        cpuCoreCount = getCpuCoreCount();
        Log.d(TAG, "CPU核心数：" + cpuCoreCount + "，总容量：" + (cpuCoreCount * 100) + "%");
    }

    private int getCpuCoreCount() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/sys/devices/system/cpu/present"));
            String line = reader.readLine();
            reader.close();
            if (line != null && line.contains("-")) {
                return Integer.parseInt(line.split("-")[1]) + 1;
            }
        } catch (Exception e) {
            Log.w(TAG, "识别核心数失败，默认8核", e);
        }
        return 8;
    }

    /**
     * 计算top风格CPU总占用率（0-800%，和top命令数值一致）
     */
    public float getTopStyleCPUUsage() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"));
            String line = reader.readLine();
            reader.close();

            if (line == null || !line.startsWith("cpu ")) {
                return 0;
            }

            String[] parts = line.split("\\s+");
            long user = Long.parseLong(parts[1]);
            long nice = Long.parseLong(parts[2]);
            long sys = Long.parseLong(parts[3]);
            long idle = Long.parseLong(parts[4]);
            long iowait = Long.parseLong(parts[5]);
            long irq = Long.parseLong(parts[6]);
            long softirq = Long.parseLong(parts[7]);

            long totalJiffies = user + nice + sys + idle + iowait + irq + softirq;
            long activeJiffies = totalJiffies - idle - iowait;

            if (lastTotalJiffies == 0 || lastActiveJiffies == 0) {
                lastTotalJiffies = totalJiffies;
                lastActiveJiffies = activeJiffies;
                return 0;
            }

            long deltaTotal = totalJiffies - lastTotalJiffies;
            long deltaActive = activeJiffies - lastActiveJiffies;

            lastTotalJiffies = totalJiffies;
            lastActiveJiffies = activeJiffies;

            if (deltaTotal == 0) {
                return 0;
            }

            // 核心：换算为8核总容量的使用率（匹配top数值）
            float usageSingleCore = (deltaActive * 100.0f) / deltaTotal;
            float usageTotalCore = usageSingleCore * cpuCoreCount;
            return Math.min(Math.round(usageTotalCore * 10) / 10f, cpuCoreCount * 100f);

        } catch (IOException | NumberFormatException e) {
            Log.e(TAG, "获取CPU使用率失败", e);
            return 0;
        }
    }
}