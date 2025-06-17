package com.example.sensordatacollector;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 电池统计管理器，用于监控应用耗电量
 */
public class BatteryStatsManager implements ComponentCallbacks2 {
    private static final String TAG = "BatteryStatsManager";
    private final Context context;
    private long startBatteryLevel = -1;
    private long startTime = -1;
    private int recordedUsage = 0;
    private float peakTemperature = 0;
    
    // CPU和内存使用数据
    private float currentCpuUsage = 0;
    private float currentMemoryMB = 0;
    
    // 定时统计
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> statsTask;
    private static final int STATS_INTERVAL_SECONDS = 60; // 每分钟更新一次统计

    // 单例模式
    private static volatile BatteryStatsManager instance;
    
    // 主线程Handler，用于延迟任务
    private Handler mainThreadHandler;
    
    private BatteryStatsManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainThreadHandler = new Handler(Looper.getMainLooper()); // 初始化Handler
        initialize();
        
        // 注册内存回调
        if (this.context != null) {
            this.context.registerComponentCallbacks(this);
            Log.d(TAG, context.getString(R.string.log_memory_callback_registered));
        }
    }

    public static synchronized BatteryStatsManager getInstance(Context context) {
        if (instance == null) {
            synchronized (BatteryStatsManager.class) {
                if (instance == null) {
                    // 使用ApplicationContext避免Activity内存泄漏
                    Context appContext = context.getApplicationContext();
                    instance = new BatteryStatsManager(appContext);
                }
            }
        }
        return instance;
    }

    private void initialize() {
        // 记录初始电池信息
        startBatteryLevel = getCurrentBatteryLevel();
        startTime = System.currentTimeMillis();
        
        // 启动定时统计任务
        startStatsMonitoring();
    }
    
    /**
     * 启动定时统计监控
     */
    private void startStatsMonitoring() {
        // 停止已有的任务
        if (statsTask != null && !statsTask.isDone()) {
            statsTask.cancel(true);
        }
        
        // 只在首次创建scheduler时创建，避免重复创建线程池
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "BatteryStats-Monitor");
                thread.setDaemon(true); // 设置为守护线程
                thread.setPriority(Thread.MIN_PRIORITY); // 设置最低优先级
                return thread;
            });
        }
        
        // 启动定时任务
        try {
            statsTask = scheduler.scheduleAtFixedRate(
                this::updateStats, 
                0, 
                STATS_INTERVAL_SECONDS, 
                TimeUnit.SECONDS
            );
            Log.d(TAG, context.getString(R.string.log_battery_stats_monitoring_started));
        } catch (Exception e) {
            Log.e(TAG, context.getString(R.string.log_error_starting_battery_stats_monitoring), e);
        }
    }
    
    /**
     * 更新统计数据
     */
    private void updateStats() {
        try {
            // 更新峰值温度
            float currentTemp = getBatteryTemperature();
            if (currentTemp > peakTemperature) {
                peakTemperature = currentTemp;
            }
            
            // 安全地更新性能统计，避免访问可能导致权限问题的系统服务
            updatePerformanceStatsSafely();
        } catch (Exception e) {
            Log.w(TAG, context.getString(R.string.log_error_updating_stats), e);
        }
    }
    
    /**
     * 安全地更新性能统计，避免权限问题
     */
    private void updatePerformanceStatsSafely() {
        try {
            // 只更新基本的内存统计，避免访问可能有权限问题的系统服务
            updateMemoryStats();
            
            // 不再尝试获取CPU使用率，因为可能导致权限问题
            currentCpuUsage = 0; // 重置为0，表示不可用
            
        } catch (SecurityException e) {
            Log.w(TAG, context.getString(R.string.log_permission_denied_accessing_performance_data), e);
            currentCpuUsage = 0;
            currentMemoryMB = getMemoryUsageRuntime(); // 使用Runtime作为备选
        } catch (Exception e) {
            Log.w(TAG, context.getString(R.string.log_error_updating_performance_stats), e);
        }
    }
    
    /**
     * 更新内存统计
     */
    private void updateMemoryStats() {
        try {
            android.app.ActivityManager activityManager = 
                (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            
            if (activityManager != null) {
                android.app.ActivityManager.MemoryInfo memoryInfo = 
                    new android.app.ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(memoryInfo);
                
                // 获取当前进程的内存使用
                int[] pids = {android.os.Process.myPid()};
                android.os.Debug.MemoryInfo[] memInfos = activityManager.getProcessMemoryInfo(pids);
                if (memInfos.length > 0) {
                    // 使用PSS(Proportional Set Size)作为内存使用量
                    currentMemoryMB = memInfos[0].getTotalPss() / 1024.0f;
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, context.getString(R.string.log_permission_denied_getting_memory_info), e);
            currentMemoryMB = getMemoryUsageRuntime();
        } catch (Exception e) {
            Log.w(TAG, context.getString(R.string.log_error_getting_memory_info), e);
            currentMemoryMB = getMemoryUsageRuntime();
        }
    }

    /**
     * 获取当前电池电量百分比
     */
    public int getCurrentBatteryLevel() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);
        if (batteryStatus == null) return -1;
        
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        
        return (int) ((level / (float) scale) * 100);
    }

    /**
     * 获取充电状态
     */
    public boolean isCharging() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);
        if (batteryStatus == null) return false;
        
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING || 
               status == BatteryManager.BATTERY_STATUS_FULL;
    }
    
    /**
     * 获取充电类型
     */
    public String getChargingType() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);
        if (batteryStatus == null) return context.getString(R.string.unknown);
        
        int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        switch (chargePlug) {
            case BatteryManager.BATTERY_PLUGGED_AC:
                return context.getString(R.string.charging_type_ac);
            case BatteryManager.BATTERY_PLUGGED_USB:
                return context.getString(R.string.charging_type_usb);
            case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                return context.getString(R.string.charging_type_wireless);
            default:
                return context.getString(R.string.charging_type_not_charging);
        }
    }

    /**
     * 获取电池温度（摄氏度）
     */
    public float getBatteryTemperature() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);
        if (batteryStatus == null) return 0;
        
        int temp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        return temp / 10.0f; // 转换为摄氏度
    }
    
    /**
     * 获取电池峰值温度
     */
    public float getPeakTemperature() {
        return peakTemperature;
    }

    /**
     * 获取电池耗电百分比（从应用启动到现在）
     */
    public int getBatteryUsage() {
        if (startBatteryLevel == -1) return 0;
        
        int currentLevel = getCurrentBatteryLevel();
        if (currentLevel == -1 || isCharging()) return recordedUsage;
        
        int usage = (int) (startBatteryLevel - currentLevel);
        // 保存记录的最大使用量
        if (usage > recordedUsage) {
            recordedUsage = usage;
        }
        return usage;
    }

    /**
     * 获取运行时长（分钟）
     */
    public long getRunningTimeMinutes() {
        if (startTime == -1) return 0;
        return (System.currentTimeMillis() - startTime) / (1000 * 60);
    }
    
    /**
     * 获取格式化的运行时间
     */
    public String getFormattedRunningTime() {
        long minutes = getRunningTimeMinutes();
        long hours = minutes / 60;
        minutes = minutes % 60;
        
        return String.format(Locale.getDefault(), context.getString(R.string.formatted_running_time), hours, minutes);
    }
    
    /**
     * 获取当前CPU使用率的估计值
     * 注意：由于权限限制，这只是一个粗略估计
     */
    public float getCpuUsage() {
        return currentCpuUsage;
    }
    
    /**
     * 获取应用内存使用量（MB）
     */
    public float getMemoryUsageMB() {
        return currentMemoryMB > 0 ? currentMemoryMB : getMemoryUsageRuntime();
    }
    
    /**
     * 使用Runtime获取内存使用量（备选方法）
     */
    private float getMemoryUsageRuntime() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemInBytes = runtime.totalMemory() - runtime.freeMemory();
        return usedMemInBytes / (1024f * 1024f);
    }

    /**
     * 获取电池统计信息的文本描述
     */
    public String getBatteryStatsInfo() {
        StringBuilder info = new StringBuilder();
        info.append(context.getString(R.string.battery_info_current_level, getCurrentBatteryLevel())).append("\n");
        info.append(context.getString(R.string.battery_info_usage, getBatteryUsage())).append("\n");
        info.append(context.getString(R.string.battery_info_running_time, getFormattedRunningTime())).append("\n");
        
        float temp = getBatteryTemperature();
        info.append(context.getString(R.string.battery_info_temperature, String.format(Locale.getDefault(), "%.1f", temp))).append("°C");
        
        if (peakTemperature > 0 && peakTemperature != temp) {
            info.append(context.getString(R.string.battery_info_peak_temperature, String.format(Locale.getDefault(), "%.1f", peakTemperature))).append("°C)");
        }
        
        info.append("\n").append(context.getString(R.string.battery_info_charging_status, isCharging() ? context.getString(R.string.charging_status_charging, getChargingType()) : context.getString(R.string.charging_status_not_charging)));
        info.append("\n").append(context.getString(R.string.battery_info_memory_usage, String.format(Locale.getDefault(), "%.1f", getMemoryUsageMB())));
        
        return info.toString();
    }
    
    /**
     * 实现ComponentCallbacks2接口方法
     * 这个方法会在系统内存不足时被调用
     * @param level 内存紧张等级
     */
    @Override
    public void onTrimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE || 
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            // 内存非常紧张，需要立即释放所有可能的资源
            Log.i(TAG, context.getString(R.string.log_low_memory_critical));
            
            // 暂停数据统计任务
            if (statsTask != null && !statsTask.isDone()) {
                statsTask.cancel(false);
                Log.d(TAG, context.getString(R.string.log_battery_stats_task_paused));
                
                // 设置定时器，在稍后尝试恢复
                mainThreadHandler.postDelayed(() -> {
                    // 再次检查scheduler是否可用，避免在shutdown后执行
                    if (scheduler != null && !scheduler.isShutdown()) {
                        startStatsMonitoring();
                    }
                }, 5000); // 5秒后尝试恢复
            }
            
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            // 内存较为紧张，可以释放一些非关键资源
            Log.i(TAG, context.getString(R.string.log_low_memory_moderate));
            
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // 内存有些紧张，可以考虑释放缓存
            Log.i(TAG, context.getString(R.string.log_low_memory_low));
        }
    }
    
    /**
     * 实现ComponentCallbacks2接口方法
     * 这个方法会在系统配置变化时调用
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // 不需要处理配置变化
    }
    
    /**
     * 实现ComponentCallbacks2接口方法
     * 这个方法会在系统资源不足时调用
     */
    @Override
    public void onLowMemory() {
        // 系统内存极度不足，需要释放所有非必要资源
        Log.i(TAG, context.getString(R.string.log_system_low_memory_critical));
        
        // 暂停电池数据统计任务
        if (statsTask != null && !statsTask.isDone()) {
            statsTask.cancel(false);
            Log.d(TAG, context.getString(R.string.log_battery_stats_task_paused));
            
            // 设置定时器，在内存不足状态可能解除后恢复
            mainThreadHandler.postDelayed(() -> {
                // 再次检查scheduler是否可用
                if (scheduler != null && !scheduler.isShutdown()) {
                    startStatsMonitoring();
                }
            }, 10000); // 10秒后尝试恢复
        }
    }
    
    /**
     * 释放资源
     */
    public void shutdown() {
        // 取消注册内存回调
        if (context != null) {
            context.unregisterComponentCallbacks(this);
            Log.d(TAG, context.getString(R.string.log_memory_callback_unregistered));
        }
        
        // 清理Handler中的回调
        if (mainThreadHandler != null) {
            mainThreadHandler.removeCallbacksAndMessages(null);
        }
        
        if (scheduler != null && !scheduler.isShutdown()) {
            try {
                if (statsTask != null) {
                    statsTask.cancel(true);
                }
                scheduler.shutdown();
                if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
