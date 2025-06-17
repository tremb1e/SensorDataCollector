package com.example.sensordatacollector;

import android.util.Log;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 时间戳管理器
 * 负责统一管理传感器数据的时间戳，确保时间戳对齐
 */
public class TimestampManager {
    private static final String TAG = "TimestampManager";
    private static volatile TimestampManager instance;
    
    // 当前传感器采样的统一时间戳
    private final AtomicLong currentSensorTimestamp = new AtomicLong(0);
    
    // 传感器时间戳历史记录（用于数据对齐）
    private final ConcurrentLinkedQueue<Long> sensorTimestampHistory = new ConcurrentLinkedQueue<>();
    
    // 历史记录的最大保留数量
    private static final int MAX_HISTORY_SIZE = 1000;
    
    // 时间窗口（毫秒），用于数据时间戳对齐
    private static final long TIME_WINDOW_MS = 50;
    
    // 采样率
    private volatile int samplingRateMs = 10;
    
    // 是否正在采集传感器数据
    private final AtomicBoolean isSensorCollecting = new AtomicBoolean(false);
    
    // 用于控制日志输出频率的计数器
    private volatile int logCounter = 0;
    
    private TimestampManager() {
        Log.d(TAG, "时间戳管理器已初始化");
    }
    
    public static TimestampManager getInstance() {
        if (instance == null) {
            synchronized (TimestampManager.class) {
                if (instance == null) {
                    instance = new TimestampManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 设置采样率
     */
    public void setSamplingRate(int samplingRateMs) {
        this.samplingRateMs = Math.max(1, samplingRateMs);
        Log.d(TAG, "采样率已设置为: " + this.samplingRateMs + "ms");
    }
    
    /**
     * 获取采样率
     */
    public int getSamplingRate() {
        return samplingRateMs;
    }
    
    /**
     * 开始传感器数据采集
     */
    public void startSensorCollection() {
        isSensorCollecting.set(true);
        Log.d(TAG, "开始传感器数据采集时间戳同步");
    }
    
    /**
     * 停止传感器数据采集
     */
    public void stopSensorCollection() {
        isSensorCollecting.set(false);
        currentSensorTimestamp.set(0);
        sensorTimestampHistory.clear();
        Log.d(TAG, "停止传感器数据采集时间戳同步");
    }
    
    /**
     * 获取当前传感器数据的统一时间戳
     * 所有三个传感器在同一采样周期应该使用相同的时间戳
     * 
     * 注意：这个方法被设计为在高频调用下保持性能，
     * 同时确保在同一采样周期内返回相同的时间戳
     * 移除synchronized以提高性能，使用原子操作确保线程安全
     */
    public long getCurrentSensorTimestamp() {
        if (!isSensorCollecting.get()) {
            return System.currentTimeMillis();
        }
        
        long currentTime = System.currentTimeMillis();
        long lastTimestamp = currentSensorTimestamp.get();
        
        // 如果是第一次调用或者距离上次时间戳超过采样周期，更新时间戳
        if (lastTimestamp == 0 || (currentTime - lastTimestamp) >= samplingRateMs) {
            // 使用compareAndSet确保原子性更新，最多尝试3次避免过度竞争
            for (int attempts = 0; attempts < 3; attempts++) {
                if (currentSensorTimestamp.compareAndSet(lastTimestamp, currentTime)) {
                    addToHistory(currentTime);
                    
                    // 减少日志输出频率，只在每20次更新时记录一次
                    logCounter++;
                    if (logCounter % 20 == 0) {
                        Log.v(TAG, "更新传感器统一时间戳: " + currentTime + " (采样周期: " + samplingRateMs + "ms, 间隔: " + (lastTimestamp == 0 ? 0 : currentTime - lastTimestamp) + "ms)");
                    }
                    return currentTime;
                } else {
                    // 如果CAS失败，重新获取最新时间戳继续尝试
                    lastTimestamp = currentSensorTimestamp.get();
                    // 如果最新时间戳已经足够新，直接使用它
                    if ((currentTime - lastTimestamp) < samplingRateMs) {
                        if (logCounter % 100 == 0) {
                            Log.v(TAG, "CAS重试后使用最新时间戳: " + lastTimestamp);
                        }
                        return lastTimestamp;
                    }
                    // 否则更新当前时间继续尝试
                    currentTime = System.currentTimeMillis();
                }
            }
            
            // 如果多次CAS失败，直接返回当前获取的时间戳
            long finalTimestamp = currentSensorTimestamp.get();
            if (logCounter % 200 == 0) {
                Log.v(TAG, "CAS多次失败，返回当前时间戳: " + finalTimestamp);
            }
            return finalTimestamp;
        } else {
            // 在同一采样周期内，返回相同的时间戳
            // 减少日志输出频率，因为这种情况很常见
            if (logCounter % 200 == 0) {
                long timeDiff = currentTime - lastTimestamp;
                Log.v(TAG, "返回同一采样周期的时间戳: " + lastTimestamp + " (当前时间: " + currentTime + ", 间隔: " + timeDiff + "ms < " + samplingRateMs + "ms)");
            }
            return lastTimestamp;
        }
    }
    
    /**
     * 强制更新传感器时间戳（在新的采样周期开始时调用）
     */
    public long updateSensorTimestamp() {
        if (!isSensorCollecting.get()) {
            return System.currentTimeMillis();
        }
        
        long currentTime = System.currentTimeMillis();
        currentSensorTimestamp.set(currentTime);
        addToHistory(currentTime);
        Log.v(TAG, "更新传感器时间戳: " + currentTime);
        return currentTime;
    }
    
    /**
     * 添加时间戳到历史记录
     */
    private void addToHistory(long timestamp) {
        sensorTimestampHistory.offer(timestamp);
        
        // 限制历史记录大小，移除最旧的记录
        while (sensorTimestampHistory.size() > MAX_HISTORY_SIZE) {
            sensorTimestampHistory.poll();
        }
        
        // 清理超过时间窗口的旧记录
        long currentTime = System.currentTimeMillis();
        sensorTimestampHistory.removeIf(ts -> (currentTime - ts) > (TIME_WINDOW_MS * 10));
    }
    
    /**
     * 检查是否正在采集传感器数据
     */
    public boolean isSensorCollecting() {
        return isSensorCollecting.get();
    }
    
    /**
     * 获取时间戳历史记录数量（用于调试）
     */
    public int getHistorySize() {
        return sensorTimestampHistory.size();
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        stopSensorCollection();
        Log.d(TAG, "时间戳管理器资源已清理");
    }
} 