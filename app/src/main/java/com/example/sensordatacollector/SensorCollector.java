package com.example.sensordatacollector;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SensorCollector implements SensorEventListener, ComponentCallbacks2 {

    private static final String TAG = "SensorCollector";
    private SensorManager sensorManager;
    private Sensor accelerometer, gyroscope, magnetometer;
    private volatile java.lang.ref.WeakReference<Context> contextRef; // 使用WeakReference避免内存泄漏
    private ForegroundAppManager foregroundAppManager;

    private DataCollectionListener dataCollectionListener;
    private ExecutorService executorService;
    
    // 时间戳管理器
    private TimestampManager timestampManager;
    
    // 添加活跃传感器列表
    private final List<Sensor> activeSensors = new ArrayList<>();
    
    // 线程池状态监控
    private final AtomicBoolean isExecutorRunning = new AtomicBoolean(false);
    
    // 默认传感器延迟(微秒) - 使用volatile确保线程安全
    private volatile int currentDelayMicros = SensorManager.SENSOR_DELAY_NORMAL;
    private volatile int samplingRateMs = 10; // 默认采样率，与currentDelayMicros保持一致
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    
    // 用于监控传感器事件频率的变量
    private volatile long lastSensorEventTime = 0;
    private volatile int sensorEventCount = 0;
    
    // 用于统计实际采样间隔的变量
    private volatile long totalInterval = 0;
    private volatile int intervalCount = 0;
    
    // 线程池配置参数 - 优化减少线程数量
    private static final int CORE_POOL_SIZE = 1;
    private static final int MAX_POOL_SIZE = 1; // 传感器只需要单线程处理
    private static final int KEEP_ALIVE_TIME = 10; // 减少保活时间
    private static final int QUEUE_CAPACITY = 100; // 减少队列容量

    public interface DataCollectionListener {
        void onDataCollected(DataRecord dataRecord);
    }

    public SensorCollector(Context context, DataCollectionListener listener) {
        this.contextRef = new java.lang.ref.WeakReference<>(context);
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.dataCollectionListener = listener;
        
        // 初始化时间戳管理器
        this.timestampManager = TimestampManager.getInstance();
        
        // 使用更健壮的线程池代替简单的单线程执行器
        createExecutor();
        
        this.foregroundAppManager = ForegroundAppManager.getInstance(context);
        
        if (sensorManager != null) {
            try {
                accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
                
                if (accelerometer == null || gyroscope == null || magnetometer == null) {
                    Log.w(TAG, "部分传感器不可用！" + 
                          (accelerometer == null ? " 加速度计缺失" : "") +
                          (gyroscope == null ? " 陀螺仪缺失" : "") +
                          (magnetometer == null ? " 磁力计缺失" : ""));
                }
            } catch (Exception e) {
                Log.e(TAG, "获取传感器时出错", e);
            }
        } else {
            Log.e(TAG, "无法获取SensorManager服务");
        }
        
        // 注册内存回调 - 使用WeakReference避免内存泄漏
        Context appContext = this.contextRef.get();
        if (appContext != null) {
            appContext.registerComponentCallbacks(this);
            Log.d(TAG, "注册了内存回调");
        }
    }
    
    /**
     * 创建或重建执行器
     */
    private synchronized void createExecutor() {
        // 如果已存在执行器，先关闭
        if (executorService != null && !executorService.isShutdown()) {
            try {
                executorService.shutdown();
                // 等待一小段时间让当前任务完成
                if (!executorService.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                    executorService.shutdownNow(); // 如果超时则强制关闭
                    Log.w(TAG, "旧的传感器执行器被强制关闭");
                }
            } catch (InterruptedException e) {
                if (executorService != null) { // 添加null检查
                    executorService.shutdownNow();
                }
                Thread.currentThread().interrupt();
                Log.w(TAG, "关闭旧的传感器执行器时被中断", e);
            } catch (Exception e) {
                Log.w(TAG, "关闭旧的执行器时出现异常", e);
            }
        }
        
        // 创建新的执行器，使用有界队列和拒绝策略
        executorService = new ThreadPoolExecutor(
            CORE_POOL_SIZE, 
            MAX_POOL_SIZE, 
            KEEP_ALIVE_TIME, 
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY), 
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "SensorCollector-Worker");
                    thread.setPriority(Thread.NORM_PRIORITY); // 正常优先级
                    return thread;
                }
            },
            new ThreadPoolExecutor.DiscardOldestPolicy() // 如果队列满了，丢弃最旧的任务
        );
        
        isExecutorRunning.set(true);
        Log.d(TAG, "创建了新的传感器数据处理执行器");
    }

    /**
     * 使用默认采样率开始监听传感器
     */
    public void startListening() {
        startListening(10); // 默认使用10毫秒的采样率
    }
    
    /**
     * 使用自定义采样率开始监听传感器
     * @param samplingRateMs 采样率(毫秒)
     */
    public synchronized void startListening(int samplingRateMs) {
        if (sensorManager == null) {
            Log.e(TAG, "SensorManager为空，无法启动传感器监听");
            return;
        }
        
        // 防止多次注册
        if (isListening.get()) {
            stopListening();
        }
        
        // 确保采样率在合理范围内，并考虑硬件限制
        int safeRate = Math.max(5, samplingRateMs); // 最小5ms，但默认为10ms
        
        // 检查硬件最小延迟限制
        int hardwareMinDelayMicros = getMinHardwareDelay();
        int requestedDelayMicros = safeRate * 1000;
        
        Log.d(TAG, "采样率检查 - 请求: " + safeRate + "ms (" + requestedDelayMicros + "μs), 硬件最小延迟: " + 
                   hardwareMinDelayMicros + "μs (" + (hardwareMinDelayMicros / 1000.0f) + "ms)");
        
        if (requestedDelayMicros < hardwareMinDelayMicros) {
            Log.w(TAG, "请求的采样率 " + safeRate + "ms (" + requestedDelayMicros + "μs) " +
                      "超过硬件最小延迟 " + hardwareMinDelayMicros + "μs，将调整为硬件支持的最小延迟");
            currentDelayMicros = hardwareMinDelayMicros;
            this.samplingRateMs = Math.max(1, hardwareMinDelayMicros / 1000); // 确保至少1ms，避免除法结果为0
        } else {
            currentDelayMicros = requestedDelayMicros;
            this.samplingRateMs = safeRate;
        }
        
        Log.i(TAG, "设置传感器采样率: 请求=" + safeRate + "ms, 实际=" + this.samplingRateMs + "ms (" + currentDelayMicros + "μs)");
        
        // 如果实际采样率与请求的不同，给出警告
        if (this.samplingRateMs != safeRate) {
            Log.w(TAG, "注意：由于硬件限制，实际采样率(" + this.samplingRateMs + "ms)与请求的采样率(" + safeRate + "ms)不同");
        }
        
        // 记录传感器的实际能力信息
        logSensorCapabilities();
        
        // 设置时间戳管理器的采样率并开始采集
        // 使用实际的采样率，而不是请求的采样率
        timestampManager.setSamplingRate(this.samplingRateMs);
        timestampManager.startSensorCollection();
        
        Log.d(TAG, "开始监听传感器，采样率: " + safeRate + "ms (" + currentDelayMicros + "μs)");
        
        try {
            // 检查执行器状态并重置
            if (executorService == null || executorService.isShutdown()) {
                createExecutor();
            }
            
            if (registerSensorListeners()) {
                isListening.set(true);
                Log.i(TAG, "传感器监听启动成功");
            } else {
                Log.e(TAG, "传感器监听启动失败，没有成功注册任何传感器");
            }
        } catch (Exception e) {
            Log.e(TAG, "启动传感器监听失败", e);
        }
    }
    
    /**
     * 注册传感器监听器
     * @return 是否成功注册至少一个传感器
     */
    private boolean registerSensorListeners() {
        // 清空之前的传感器列表
        synchronized (activeSensors) {
            activeSensors.clear();
            
            boolean anySuccess = false;
            
            if (accelerometer != null) {
                try {
                    boolean success = sensorManager.registerListener(this, accelerometer, currentDelayMicros);
                    if (success) {
                        activeSensors.add(accelerometer);
                        anySuccess = true;
                        Log.d(TAG, "加速度计注册成功");
                    } else {
                        Log.w(TAG, "加速度计注册失败");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "注册加速度计时发生异常", e);
                }
            }
            
            if (gyroscope != null) {
                try {
                    boolean success = sensorManager.registerListener(this, gyroscope, currentDelayMicros);
                    if (success) {
                        activeSensors.add(gyroscope);
                        anySuccess = true;
                        Log.d(TAG, "陀螺仪注册成功");
                    } else {
                        Log.w(TAG, "陀螺仪注册失败");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "注册陀螺仪时发生异常", e);
                }
            }
            
            if (magnetometer != null) {
                try {
                    boolean success = sensorManager.registerListener(this, magnetometer, currentDelayMicros);
                    if (success) {
                        activeSensors.add(magnetometer);
                        anySuccess = true;
                        Log.d(TAG, "磁力计注册成功");
                    } else {
                        Log.w(TAG, "磁力计注册失败");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "注册磁力计时发生异常", e);
                }
            }
            
            Log.i(TAG, "成功注册了 " + activeSensors.size() + " 个传感器");
            return anySuccess;
        }
    }

    /**
     * 停止监听传感器
     */
    public synchronized void stopListening() {
        if (sensorManager != null) {
            try {
                sensorManager.unregisterListener(this);
                isListening.set(false);
                Log.d(TAG, "停止传感器监听");
            } catch (Exception e) {
                Log.e(TAG, "停止传感器监听时出错", e);
            }
        }
        
        // 停止时间戳管理器的传感器采集
        if (timestampManager != null) {
            timestampManager.stopSensorCollection();
        }
    }
    
    /**
     * 获取当前采样率(毫秒)
     */
    public int getSamplingRateMs() {
        return samplingRateMs; // 直接返回保存的采样率，而不是从微秒转换
    }
    
    /**
     * 是否正在监听传感器
     */
    public boolean isListening() {
        return isListening.get();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null || event.values.length < 3) {
            Log.w(TAG, "收到无效的传感器事件");
            return;
        }
        
        // 检查监听状态
        if (!isListening.get()) {
            Log.v(TAG, "收到传感器事件，但当前未处于监听状态，忽略此事件");
            return;
        }
        
        // 检查执行器状态，如果已关闭则不处理
        if (!isExecutorRunning.get()) {
            Log.v(TAG, "传感器收集器已关闭，忽略传感器事件");
            return;
        }
        
        // 监控传感器事件频率
        long currentEventTime = System.currentTimeMillis();
        if (lastSensorEventTime != 0) {
            long eventInterval = currentEventTime - lastSensorEventTime;
            sensorEventCount++;
            
            // 统计实际采样间隔
            totalInterval += eventInterval;
            intervalCount++;
            
            // 每100个事件记录一次统计信息
            if (sensorEventCount % 100 == 0) {
                long avgInterval = totalInterval / intervalCount;
                Log.d(TAG, "传感器事件频率监控 - 最近间隔: " + eventInterval + "ms, 平均间隔: " + avgInterval + "ms, 设置采样率: " + samplingRateMs + "ms");
                
                // 如果平均间隔与设置的采样率差异较大，给出警告
                if (Math.abs(avgInterval - samplingRateMs) > samplingRateMs * 0.5) { // 超过50%差异
                    Log.w(TAG, "警告：实际平均采样间隔(" + avgInterval + "ms)与设置的采样率(" + samplingRateMs + "ms)差异较大");
                }
                
                // 重置统计
                totalInterval = 0;
                intervalCount = 0;
            }
        }
        lastSensorEventTime = currentEventTime;
        
        // 为了确保精度不丢失，立即复制传感器值和时间戳
        // 使用时间戳管理器获取统一的时间戳
        final long timestamp = timestampManager.getCurrentSensorTimestamp();
        final float valueX = event.values[0];
        final float valueY = event.values[1];
        final float valueZ = event.values[2];
        final int accuracy = event.accuracy;
        final int sensorType = event.sensor.getType();
        
        // 在单独的线程中处理传感器数据
        if (executorService == null || executorService.isShutdown()) {
            // 如果线程池已关闭，尝试重新创建
            try {
                Log.d(TAG, "传感器处理线程池已关闭，重新创建");
                createExecutor();
            } catch (Exception e) {
                Log.e(TAG, "无法重新创建线程池", e);
                handleSensorEventData(timestamp, valueX, valueY, valueZ, accuracy, sensorType);
                return;
            }
        }
        
        try {
            executorService.submit(() -> {
                try {
                    handleSensorEventData(timestamp, valueX, valueY, valueZ, accuracy, sensorType);
                } catch (Exception e) {
                    Log.e(TAG, "处理传感器数据出错", e);
                }
            });
        } catch (RejectedExecutionException e) {
            // 如果任务被拒绝，记录日志并尝试恢复
            Log.w(TAG, "传感器数据处理任务被拒绝：" + e.getMessage());
            
            // 尝试恢复线程池
            try {
                if (executorService.isShutdown() && isExecutorRunning.get()) {
                    createExecutor();
                    Log.d(TAG, "已重新创建传感器处理线程池");
                    
                    // 尝试重新提交任务
                    executorService.submit(() -> {
                        handleSensorEventData(timestamp, valueX, valueY, valueZ, accuracy, sensorType);
                    });
                    return;
                }
            } catch (Exception ex) {
                Log.e(TAG, "恢复线程池失败", ex);
            }
            
            // 直接在当前线程处理数据，避免丢失
            handleSensorEventData(timestamp, valueX, valueY, valueZ, accuracy, sensorType);
        } catch (Exception e) {
            // 其他异常
            Log.e(TAG, "提交传感器数据处理任务时发生异常", e);
            handleSensorEventData(timestamp, valueX, valueY, valueZ, accuracy, sensorType);
        }
    }
    
    /**
     * 统一处理传感器数据，创建DataRecord并通知监听器。
     * 此方法取代了旧的 processSensorData 和 processEventInMainThread。
     */
    private void handleSensorEventData(long timestamp, float valueX, float valueY, float valueZ, int accuracy, int sensorType) {
        try {
            String sensorName = "unknown"; // 默认值
            
            // 获取前台应用信息
            ForegroundAppManager.ForegroundAppInfo appInfo = null;
            if (foregroundAppManager != null) {
                try {
                    appInfo = foregroundAppManager.getForegroundAppInfo();
                } catch (Exception e) {
                    Log.w(TAG, "获取前台应用信息失败", e);
                }
            }
            
            if (appInfo == null) {
                // 提供一个默认的ForegroundAppInfo对象，避免空指针
                appInfo = new ForegroundAppManager.ForegroundAppInfo("未知应用", "");
            }
            
            // 确定传感器类型
            switch (sensorType) {
                case Sensor.TYPE_ACCELEROMETER:
                    sensorName = "accelerometer";
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    sensorName = "gyroscope";
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    sensorName = "magnetometer";
                    break;
                // 可以选择添加 default case 来处理未知传感器类型，或者保持当前逻辑仅处理已知类型
            }

            // 创建数据记录
            DataRecord record = new DataRecord(
                    timestamp,
                    sensorName,
                    valueX,
                    valueY,
                    valueZ,
                    accuracy,
                    appInfo.getAppName(),
                    appInfo.getPackageName(),
                    DataManager.getCurrentUserId() // 静态方法调用，确保线程安全
            );

            // 发送数据
            if (dataCollectionListener != null) {
                try {
                    dataCollectionListener.onDataCollected(record);
                } catch (Exception e) {
                    Log.e(TAG, "通知DataCollectionListener失败", e);
                    // 如果通知失败，可能是DataManager已关闭，减少日志频率
                    if (e.getMessage() != null && e.getMessage().contains("已关闭")) {
                        Log.v(TAG, "DataCollectionListener已关闭，跳过后续通知");
                    }
                }
            }
        } catch (Exception e) {
            // 捕获创建DataRecord或通知监听器时可能发生的任何顶层异常
            Log.e(TAG, "在handleSensorEventData中发生严重错误", e);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 记录精度变化
        if (sensor != null) {
            Log.d(TAG, "传感器精度变化: " + sensor.getName() + ", 精度: " + accuracy);
        }
    }

    /**
     * 处理内存压力
     */
    @Override
    public void onTrimMemory(int level) {
        Log.d(TAG, "SensorCollector.onTrimMemory: " + level);
        
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || 
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            // 严重内存压力，释放所有可能的资源
            Log.d(TAG, "严重内存压力，停止传感器采集");
            
            // 临时停止传感器监听释放资源
            stopListening();
            
            // 保留对dataManager的引用，但释放数据缓存
            if (dataCollectionListener != null && dataCollectionListener instanceof ComponentCallbacks2) {
                ((ComponentCallbacks2) dataCollectionListener).onTrimMemory(level);
            }
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            // 中等内存压力，减少采样率
            if (isListening.get() && sensorManager != null) {
                Log.d(TAG, "中等内存压力，减少传感器采样率");
                try {
                    // 临时停止监听
                    sensorManager.unregisterListener(this);
                    
                    // 以降低的采样率重新注册（延长采样间隔）
                    for (Sensor sensor : activeSensors) {
                        // 使用更低的采样率，降低CPU和内存压力
                        int currentDelay = samplingRateMs > 100 ? SensorManager.SENSOR_DELAY_NORMAL : SensorManager.SENSOR_DELAY_UI;
                        sensorManager.registerListener(this, sensor, currentDelay);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "调整传感器采样率失败", e);
                }
            }
        }
    }
    
    /**
     * 处理低内存
     */
    @Override
    public void onLowMemory() {
        Log.d(TAG, "SensorCollector.onLowMemory");
        
        // 系统内存极度不足，立即释放所有资源
        stopListening();
        activeSensors.clear();
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
     * 获取所有传感器的最小硬件延迟
     */
    private int getMinHardwareDelay() {
        int minDelay = Integer.MAX_VALUE;
        
        try {
            if (accelerometer != null) {
                int accelDelay = accelerometer.getMinDelay();
                Log.d(TAG, "加速度计最小延迟: " + accelDelay + "μs");
                if (accelDelay > 0) {
                    minDelay = Math.min(minDelay, accelDelay);
                } else if (accelDelay == 0) {
                    Log.w(TAG, "加速度计只支持on-change模式，不支持连续采样");
                }
            }
            if (gyroscope != null) {
                int gyroDelay = gyroscope.getMinDelay();
                Log.d(TAG, "陀螺仪最小延迟: " + gyroDelay + "μs");
                if (gyroDelay > 0) {
                    minDelay = Math.min(minDelay, gyroDelay);
                } else if (gyroDelay == 0) {
                    Log.w(TAG, "陀螺仪只支持on-change模式，不支持连续采样");
                }
            }
            if (magnetometer != null) {
                int magDelay = magnetometer.getMinDelay();
                Log.d(TAG, "磁力计最小延迟: " + magDelay + "μs");
                if (magDelay > 0) {
                    minDelay = Math.min(minDelay, magDelay);
                } else if (magDelay == 0) {
                    Log.w(TAG, "磁力计只支持on-change模式，不支持连续采样");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "获取传感器最小延迟失败", e);
        }
        
        // 如果没有获取到有效的最小延迟，使用保守的默认值
        if (minDelay == Integer.MAX_VALUE) {
            minDelay = 10000; // 10ms = 10000μs，更保守的默认值
            Log.w(TAG, "无法获取传感器硬件最小延迟，使用默认值: " + minDelay + "μs");
        } else {
            Log.d(TAG, "检测到的最小硬件延迟: " + minDelay + "μs (" + (minDelay / 1000.0f) + "ms)");
        }
        
        return minDelay;
    }
    
    /**
     * 记录传感器能力信息
     */
    private void logSensorCapabilities() {
        if (sensorManager == null) return;
        
        try {
            if (accelerometer != null) {
                Log.d(TAG, "加速度计信息 - 最小延迟: " + accelerometer.getMinDelay() + "μs (" + 
                      (accelerometer.getMinDelay() / 1000.0f) + "ms), 最大范围: " + accelerometer.getMaximumRange());
            }
            if (gyroscope != null) {
                Log.d(TAG, "陀螺仪信息 - 最小延迟: " + gyroscope.getMinDelay() + "μs (" + 
                      (gyroscope.getMinDelay() / 1000.0f) + "ms), 最大范围: " + gyroscope.getMaximumRange());
            }
            if (magnetometer != null) {
                Log.d(TAG, "磁力计信息 - 最小延迟: " + magnetometer.getMinDelay() + "μs (" + 
                      (magnetometer.getMinDelay() / 1000.0f) + "ms), 最大范围: " + magnetometer.getMaximumRange());
            }
        } catch (Exception e) {
            Log.w(TAG, "获取传感器能力信息失败", e);
        }
    }
    
    /**
     * 释放资源
     */
    public void release() {
        // 取消注册内存回调
        if (contextRef != null) {
            Context context = contextRef.get();
            if (context != null) {
                context.unregisterComponentCallbacks(this);
            }
        }
        
        stopListening();
        isExecutorRunning.set(false);
        
        if (executorService != null && !executorService.isShutdown()) {
            try {
                // 尝试优雅关闭
                executorService.shutdown();
                if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                    // 如果超时，强制关闭
                    executorService.shutdownNow();
                }
                executorService = null;
                Log.d(TAG, "关闭传感器数据处理线程池");
            } catch (InterruptedException e) {
                // 如果当前线程被中断，强制关闭
                if (executorService != null) {
                    executorService.shutdownNow();
                }
                Thread.currentThread().interrupt();
            }
        }
    }
}

