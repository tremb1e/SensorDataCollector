package com.example.sensordatacollector;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 时间戳对齐测试类
 * 用于验证传感器数据的时间戳是否正确对齐
 */
public class TimestampAlignmentTest {
    private static final String TAG = "TimestampAlignmentTest";
    
    /**
     * 测试传感器时间戳同步
     */
    public static void testSensorTimestampSync() {
        Log.d(TAG, "开始测试传感器时间戳同步");
        
        TimestampManager timestampManager = TimestampManager.getInstance();
        timestampManager.setSamplingRate(10); // 10ms采样率
        timestampManager.startSensorCollection();
        
        List<Long> timestamps = new ArrayList<>();
        
        // 模拟在同一采样周期内获取多个传感器的时间戳
        for (int i = 0; i < 3; i++) {
            long timestamp = timestampManager.getCurrentSensorTimestamp();
            timestamps.add(timestamp);
            Log.d(TAG, "传感器 " + i + " 时间戳: " + timestamp);
        }
        
        // 验证同一采样周期内的时间戳是否相同
        boolean allSame = true;
        long firstTimestamp = timestamps.get(0);
        for (long timestamp : timestamps) {
            if (timestamp != firstTimestamp) {
                allSame = false;
                break;
            }
        }
        
        Log.d(TAG, "同一采样周期内时间戳是否相同: " + allSame);
        
        // 等待一个采样周期后再次测试
        try {
            Thread.sleep(15); // 等待超过采样周期
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        long newTimestamp = timestampManager.getCurrentSensorTimestamp();
        boolean timestampUpdated = newTimestamp != firstTimestamp;
        Log.d(TAG, "新采样周期时间戳是否更新: " + timestampUpdated + 
                   " (旧: " + firstTimestamp + ", 新: " + newTimestamp + ")");
        
        timestampManager.stopSensorCollection();
        Log.d(TAG, "传感器时间戳同步测试完成");
    }
    
    /**
     * 测试并发场景下的时间戳同步
     */
    public static void testConcurrentTimestampSync() {
        Log.d(TAG, "开始测试并发场景下的时间戳同步");
        
        TimestampManager timestampManager = TimestampManager.getInstance();
        timestampManager.setSamplingRate(10); // 10ms采样率
        timestampManager.startSensorCollection();
        
        final int threadCount = 3;
        final int iterationsPerThread = 5;
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final List<List<Long>> allTimestamps = new ArrayList<>();
        
        // 初始化结果列表
        for (int i = 0; i < threadCount; i++) {
            allTimestamps.add(new ArrayList<>());
        }
        
        // 创建多个线程同时获取传感器时间戳
        for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
            final int finalThreadIndex = threadIndex;
            new Thread(() -> {
                try {
                    List<Long> timestamps = allTimestamps.get(finalThreadIndex);
                    
                    for (int i = 0; i < iterationsPerThread; i++) {
                        long timestamp = timestampManager.getCurrentSensorTimestamp();
                        timestamps.add(timestamp);
                        Log.d(TAG, "线程 " + finalThreadIndex + " 获取时间戳: " + timestamp);
                        
                        try {
                            Thread.sleep(1); // 短暂间隔
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }, "TimestampTest-" + threadIndex).start();
        }
        
        try {
            // 等待所有线程完成
            if (latch.await(5, TimeUnit.SECONDS)) {
                // 分析结果
                analyzeConcurrentResults(allTimestamps);
            } else {
                Log.e(TAG, "并发测试超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "并发测试被中断");
        }
        
        timestampManager.stopSensorCollection();
        Log.d(TAG, "并发场景时间戳同步测试完成");
    }
    
    /**
     * 分析并发测试结果
     */
    private static void analyzeConcurrentResults(List<List<Long>> allTimestamps) {
        Log.d(TAG, "分析并发测试结果");
        
        // 统计每个时间戳的出现次数
        java.util.Map<Long, Integer> timestampCounts = new java.util.HashMap<>();
        
        for (List<Long> timestamps : allTimestamps) {
            for (Long timestamp : timestamps) {
                timestampCounts.put(timestamp, timestampCounts.getOrDefault(timestamp, 0) + 1);
            }
        }
        
        // 分析同步情况
        int totalTimestamps = 0;
        int synchronizedTimestamps = 0;
        
        for (java.util.Map.Entry<Long, Integer> entry : timestampCounts.entrySet()) {
            totalTimestamps++;
            if (entry.getValue() > 1) {
                synchronizedTimestamps++;
                Log.d(TAG, "时间戳 " + entry.getKey() + " 被 " + entry.getValue() + " 个线程同时获取");
            }
        }
        
        double syncRate = totalTimestamps > 0 ? (double) synchronizedTimestamps / totalTimestamps * 100 : 0;
        Log.d(TAG, "并发同步率: " + String.format("%.2f", syncRate) + "% (" + 
                   synchronizedTimestamps + "/" + totalTimestamps + ")");
    }
    
    /**
     * 运行所有测试
     */
    public static void runAllTests() {
        Log.d(TAG, "开始运行所有时间戳对齐测试");
        
        try {
            testSensorTimestampSync();
            Thread.sleep(100);
            
            testConcurrentTimestampSync();
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "测试被中断");
        }
        
        Log.d(TAG, "所有时间戳对齐测试完成");
    }
} 