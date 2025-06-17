package com.example.sensordatacollector;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.Handler;
import android.os.BatteryManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.List;

/**
 * 传感器数据收集前台服务
 * 支持后台运行和屏幕状态监听
 */
public class SensorService extends Service implements ComponentCallbacks2 {
    private static final String TAG = "SensorService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "sensor_service_channel";

    private SensorCollector sensorCollector;
    private DataManager dataManager;
    private StorageManager storageManager;
    private BatteryStatsManager batteryStatsManager;
    private ForegroundAppManager foregroundAppManager;
    
    // 时间戳管理器
    private TimestampManager timestampManager;
    
    private final IBinder binder = new LocalBinder();
    private boolean isRecordingToFile = false;
    private boolean isScreenOn = true;
    
    // 采样率，默认为5毫秒（高频采样）
    private int samplingRateMs = 10; // 默认10毫秒
    
    // 服务级别的Handler，用于延迟任务
    private Handler serviceHandler;
    
    // 屏幕状态广播接收器
    private BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                isScreenOn = false;
                // 息屏时完全停止数据采集（既不显示也不写入文件）
                Log.i(TAG, "屏幕关闭，停止所有数据采集");
                
                // 停止传感器监听
                if (sensorCollector != null && sensorCollector.isListening()) {
                    sensorCollector.stopListening();
                }
                
                // 停止数据写入
                dataManager.setShouldRecordToFile(false);
                
                // 息屏时延迟更新通知，避免触发系统UI安全检查
                serviceHandler.postDelayed(() -> {
                    try {
                        updateNotificationSafely("Service paused (Screen Off)");
                    } catch (Exception e) {
                        Log.w(TAG, "息屏时更新通知失败", e);
                    }
                }, 1000); // 延迟1秒更新
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                isScreenOn = true;
                // 屏幕开启时，恢复之前的采集状态
                Log.i(TAG, "屏幕开启，恢复数据采集");
                
                // 恢复传感器监听
                if (sensorCollector != null && !sensorCollector.isListening()) {
                    sensorCollector.startListening(samplingRateMs);
                }
                
                // 恢复数据写入状态（如果之前在记录）
                if (isRecordingToFile) {
                    dataManager.setShouldRecordToFile(true);
                }
                
                // 屏幕开启时延迟更新通知，避免状态切换冲突
                serviceHandler.postDelayed(() -> {
                    try {
                        if (isRecordingToFile) {
                            updateNotificationSafely("Collecting sensor data");
                        } else {
                            updateNotificationSafely("Monitoring sensor data (Not Saving)");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "开屏时更新通知失败", e);
                    }
                }, 1500); // 延迟1.5秒，确保屏幕状态稳定
            }
        }
    };

    // 电池状态监听器 - 低电量时自动降低采样率
    private BroadcastReceiver batteryStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                
                if (level >= 0 && scale > 0) {
                    float batteryPct = level * 100 / (float) scale;
                    
                    // 低电量时自动降低采样率
                    if (batteryPct <= 15.0f && sensorCollector != null && sensorCollector.isListening()) {
                        int currentRate = sensorCollector.getSamplingRateMs();
                        // 如果当前采样率小于20ms，则降低到20ms以节省电量
                        if (currentRate < 20) {
                            Log.i(TAG, "电池电量低(" + String.format("%.1f", batteryPct) + "%)，自动降低采样率到20ms以节省电量");
                            sensorCollector.stopListening();
                            sensorCollector.startListening(20);
                            samplingRateMs = 20;
                        }
                    } else if (batteryPct >= 30.0f && sensorCollector != null && sensorCollector.isListening()) {
                        // 电量恢复后，可以考虑恢复原始采样率（这里暂不自动恢复，避免频繁切换）
                        // 用户可以手动调整采样率
                    }
                }
            }
        }
    };

    public class LocalBinder extends Binder {
        public SensorService getService() {
            return SensorService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "创建传感器服务");
        
        // 初始化服务级别的Handler
        serviceHandler = new Handler(Looper.getMainLooper());
        
        // 获取管理器实例
        dataManager = DataManager.getInstance();
        batteryStatsManager = BatteryStatsManager.getInstance(this);
        storageManager = new StorageManager(this);
        sensorCollector = new SensorCollector(this, dataManager);
        foregroundAppManager = ForegroundAppManager.getInstance(this);
        
        // 初始化时间戳管理器
        timestampManager = TimestampManager.getInstance();
        
        // 监听数据
        dataManager.addListener(storageManager);
        
        // 注册屏幕状态广播接收器
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenStateReceiver, screenFilter);
        
        // 注册电池状态广播接收器
        IntentFilter batteryFilter = new IntentFilter();
        batteryFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryStateReceiver, batteryFilter);
        
        // 注册内存回调，确保服务能够响应系统内存压力
        registerComponentCallbacks(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("samplingRateMs")) {
            samplingRateMs = intent.getIntExtra("samplingRateMs", 200);
        }
        
        // 使用兼容不同Android版本的方式启动前台服务
        startForegroundService();
        
        // 服务启动时，默认开始监控，但不记录到文件
        startSensorMonitoring(samplingRateMs);
        
        // 如果服务被系统杀死，重新启动服务
        return START_STICKY;
    }
    
    /**
     * 启动前台服务（兼容不同Android版本）
     */
    private void startForegroundService() {
        Notification notification = createNotification("Sensor service is running");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            try {
                // 使用带类型的方式启动前台服务
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                Log.d(TAG, "使用Android 14+方式启动前台服务");
            } catch (Exception e) {
                // 如果指定类型失败，尝试不指定类型
                startForeground(NOTIFICATION_ID, notification);
                Log.e(TAG, "启动带类型的前台服务失败，回退到默认方式", e);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10+
            // Android 10-13使用类型但不需要特殊权限
            try {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                Log.d(TAG, "使用Android 10+方式启动前台服务");
            } catch (Exception e) {
                startForeground(NOTIFICATION_ID, notification);
                Log.e(TAG, "启动带类型的前台服务失败，回退到默认方式", e);
            }
        } else {
            // Android 9及以下版本使用传统方式
            startForeground(NOTIFICATION_ID, notification);
            Log.d(TAG, "使用传统方式启动前台服务");
        }
    }

    /**
     * 创建通知
     */
    private Notification createNotification(String contentText) {
        createNotificationChannel();
        
        Intent notificationIntent = new Intent(this, MainActivity.class);
        
        // 添加适配不同Android版本的PendingIntent创建代码
        PendingIntent pendingIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent, 
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            // 兼容旧版本Android
            pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        }
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Sensor Data Collector")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .build();
    }


    
    /**
     * 安全地更新通知内容，避免触发系统UI安全检查
     */
    private void updateNotificationSafely(String contentText) {
        // 使用Handler延迟执行，避免在状态切换时立即更新通知
        serviceHandler.post(() -> {
            try {
                // 检查服务是否仍在运行
                if (isDestroyed()) {
                    Log.d(TAG, "服务已销毁，跳过通知更新");
                    return;
                }
                
                NotificationManager notificationManager = getSystemService(NotificationManager.class);
                if (notificationManager != null) {
                    // 始终使用最小化的通知，避免系统UI权限问题
                    Notification notification = createMinimalNotification(contentText);
                    notificationManager.notify(NOTIFICATION_ID, notification);
                    Log.v(TAG, "通知更新成功: " + contentText);
                }
            } catch (SecurityException e) {
                Log.w(TAG, "更新通知时权限不足: " + e.getMessage());
                // 不重试，避免无限循环，只记录错误
            } catch (Exception e) {
                Log.w(TAG, "安全更新通知失败: " + e.getMessage());
            }
        });
    }
    

    
    /**
     * 创建最小化通知（用于权限受限时）
     */
    private Notification createMinimalNotification(String contentText) {
        createNotificationChannel();
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Sensor Service")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setShowWhen(false)
                .setSilent(true)
                .setLocalOnly(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build();
    }
    

    
    // 添加服务销毁状态检查
    private volatile boolean destroyed = false;
    
    private boolean isDestroyed() {
        return destroyed;
    }

    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Sensor Service Notifications",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows the status of the sensor data collection service");
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * 开始收集数据（即开始写入文件）
     */
    public void startCollecting(int newSamplingRateMs) {
        this.samplingRateMs = newSamplingRateMs;
        dataManager.setCollecting(true);
        isRecordingToFile = true;
        
        // 只有在屏幕开启时才启动传感器和数据写入
        if (isScreenOn) {
            dataManager.setShouldRecordToFile(true);
            if (!sensorCollector.isListening()) {
                sensorCollector.startListening(this.samplingRateMs);
            } else if (sensorCollector.getSamplingRateMs() != this.samplingRateMs * 1000) {
                sensorCollector.stopListening();
                sensorCollector.startListening(this.samplingRateMs);
            }
            updateNotificationSafely("Collecting sensor data");
            Log.i(TAG, "开始传感器数据收集(写入文件)，采样率: " + this.samplingRateMs + "ms");
        } else {
            // 息屏时不启动，但记录用户意图，等屏幕开启时恢复
            dataManager.setShouldRecordToFile(false);
            updateNotificationSafely("Service paused (Screen Off)");
            Log.i(TAG, "屏幕关闭状态，数据收集将在屏幕开启时启动");
        }
    }
    
    /**
     * 仅启动传感器监听，用于UI显示，不记录数据到文件
     */
    public void startSensorMonitoring(int newSamplingRateMs) {
        this.samplingRateMs = newSamplingRateMs;
        dataManager.setShouldRecordToFile(false);
        dataManager.setCollecting(false);
        isRecordingToFile = false;
        
        // 只有在屏幕开启时才启动传感器监听
        if (isScreenOn) {
            if (!sensorCollector.isListening()) {
                sensorCollector.startListening(this.samplingRateMs);
            } else if (sensorCollector.getSamplingRateMs() != this.samplingRateMs * 1000) {
                sensorCollector.stopListening();
                sensorCollector.startListening(this.samplingRateMs);
            }
            updateNotificationSafely("Monitoring sensor data (Not Saving)");
            Log.i(TAG, "开始传感器数据监控(不写入文件)，采样率: " + this.samplingRateMs + "ms");
        } else {
            // 息屏时不启动传感器监听
            updateNotificationSafely("Service paused (Screen Off)");
            Log.i(TAG, "屏幕关闭状态，传感器监控将在屏幕开启时启动");
        }
    }

    /**
     * 停止收集数据（即停止写入文件，但保持监控）
     */
    public void stopCollecting() {
        dataManager.setShouldRecordToFile(false);
        dataManager.setCollecting(false);
        isRecordingToFile = false;
        
        if (isScreenOn) {
            // 屏幕开启时继续传感器监听，只是不写入文件
            if (!sensorCollector.isListening()) {
                sensorCollector.startListening(samplingRateMs);
            }
            updateNotificationSafely("Monitoring sensor data (Not Saving)");
            Log.i(TAG, "已停止数据收集(写入文件)，继续监控。");
        } else {
            // 息屏时完全停止
            if (sensorCollector.isListening()) {
                sensorCollector.stopListening();
            }
            updateNotificationSafely("Service paused (Screen Off)");
            Log.i(TAG, "已停止数据收集，屏幕关闭状态下暂停监控。");
        }
    }

    /**
     * 完全停止所有传感器活动（例如在服务销毁时）
     */
    public void stopAllSensorActivity() {
        if (sensorCollector != null && sensorCollector.isListening()) {
            sensorCollector.stopListening();
        }
        dataManager.setShouldRecordToFile(false);
        dataManager.setCollecting(false);
        isRecordingToFile = false;
        Log.i(TAG, "已停止所有传感器活动。");
    }

    /**
     * 获取当前采样率
     */
    public int getSamplingRateMs() {
        return samplingRateMs;
    }

    /**
     * 设置采样率
     */
    public void setSamplingRateMs(int samplingRateMs) {
        this.samplingRateMs = samplingRateMs;
        
        // 更新时间戳管理器的采样率
        if (timestampManager != null) {
            timestampManager.setSamplingRate(samplingRateMs);
        }
        
        Log.d(TAG, "采样率已设置为: " + samplingRateMs + "ms");
    }

    /**
     * 获取电池信息
     */
    public String getBatteryStatsInfo() {
        return batteryStatsManager.getBatteryStatsInfo();
    }
    
    /**
     * 获取服务运行状态
     */
    public boolean isRecordingToFile() {
        return isRecordingToFile;
    }

    public boolean isSensorListening() {
        return sensorCollector != null && sensorCollector.isListening();
    }
    
    /**
     * 获取前台应用信息
     */
    public ForegroundAppManager.ForegroundAppInfo getForegroundAppInfo() {
        if (foregroundAppManager != null) {
            return foregroundAppManager.getForegroundAppInfo();
        }
        return new ForegroundAppManager.ForegroundAppInfo("未知应用", "");
    }
    
    /**
     * 检查是否有使用统计权限
     */
    public boolean hasUsageStatsPermission() {
        return foregroundAppManager != null && foregroundAppManager.hasUsageStatsPermission();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "销毁传感器服务");
        destroyed = true; // 标记服务已销毁
        
        // 停止所有传感器活动
        stopAllSensorActivity();
        
        // 停止服务Handler中的所有挂起任务
        if (serviceHandler != null) {
            serviceHandler.removeCallbacksAndMessages(null);
        }

        // 取消注册屏幕状态广播接收器
        try {
            if (screenStateReceiver != null) {
                unregisterReceiver(screenStateReceiver);
                screenStateReceiver = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "取消注册屏幕状态接收器失败", e);
        }
        
        // 取消注册电池状态广播接收器
        try {
            if (batteryStateReceiver != null) {
                unregisterReceiver(batteryStateReceiver);
                batteryStateReceiver = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "取消注册电池状态接收器失败", e);
        }
        
        // 取消注册内存回调
        try {
            unregisterComponentCallbacks(this);
        } catch (Exception e) {
            Log.w(TAG, "取消注册组件回调失败", e);
        }

        // 关闭 SensorCollector
        if (sensorCollector != null) {
            try {
                sensorCollector.release(); // 使用正确的方法名：release
                Log.d(TAG, "SensorCollector 已关闭");
            } catch (Exception e) {
                Log.e(TAG, "关闭 SensorCollector 失败", e);
            }
            sensorCollector = null;
        }

        // 关闭 DataManager
        if (dataManager != null) {
            try {
                dataManager.removeListener(storageManager); // 先移除监听器
                dataManager.shutdown();
                Log.d(TAG, "DataManager 已关闭");
            } catch (Exception e) {
                Log.e(TAG, "关闭 DataManager 失败", e);
            }
            dataManager = null;
        }

        // 关闭 StorageManager
        if (storageManager != null) {
            try {
                storageManager.shutdown();
                Log.d(TAG, "StorageManager 已关闭");
            } catch (Exception e) {
                Log.e(TAG, "关闭 StorageManager 失败", e);
            }
            storageManager = null;
        }

        // 关闭 BatteryStatsManager
        if (batteryStatsManager != null) {
            try {
                batteryStatsManager.shutdown();
                Log.d(TAG, "BatteryStatsManager 已关闭");
            } catch (Exception e) {
                Log.e(TAG, "关闭 BatteryStatsManager 失败", e);
            }
            batteryStatsManager = null;
        }
        
        // 关闭 ForegroundAppManager
        if (foregroundAppManager != null) {
            try {
                foregroundAppManager.shutdown();
                Log.d(TAG, "ForegroundAppManager 已关闭");
            } catch (Exception e) {
                Log.e(TAG, "关闭 ForegroundAppManager 失败", e);
            }
            foregroundAppManager = null;
        }
        
        // 关闭 TimestampManager
        if (timestampManager != null) {
            try {
                timestampManager.cleanup(); // 使用正确的方法名：cleanup
                Log.d(TAG, "TimestampManager 已关闭");
            } catch (Exception e) {
                Log.e(TAG, "关闭 TimestampManager 失败", e);
            }
            timestampManager = null;
        }
        
        // 停止前台服务并移除通知
        try {
            stopForeground(true);
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.cancel(NOTIFICATION_ID);
            }
        } catch (Exception e) {
            Log.w(TAG, "停止前台服务或移除通知失败", e);
        }

        super.onDestroy();
        Log.d(TAG, "传感器服务已完全销毁");
    }

    /**
     * 实现ComponentCallbacks2接口方法
     * 这个方法会在系统配置变化时调用
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // 服务不需要特别处理配置变化
    }

    /**
     * 实现ComponentCallbacks2接口方法
     * 这个方法会在系统内存极度不足时调用
     */
    @Override
    public void onLowMemory() {
        // 系统内存极度不足，需要释放所有非必要资源
        Log.i(TAG, "系统内存极度不足，SensorService执行全面资源释放");
        
        // 暂时停止传感器监听，减少资源占用
        if (sensorCollector != null && sensorCollector.isListening() && !isRecordingToFile) {
            sensorCollector.stopListening();
            updateNotificationSafely("Monitoring paused (Low Memory)");
            
            // 设置定时器，稍后尝试恢复
            serviceHandler.postDelayed(() -> {
                // 再次检查服务和SensorCollector是否有效
                if (SensorService.this == null || sensorCollector == null) return;
                if (isScreenOn && !sensorCollector.isListening()) {
                    sensorCollector.startListening(samplingRateMs);
                    if (isRecordingToFile) {
                        updateNotificationSafely("Collecting sensor data");
                    } else {
                        updateNotificationSafely("Monitoring sensor data (Not Saving)");
                    }
                }
            }, 5000); // 5秒后尝试恢复
        }
        
        // 建议系统执行GC
        Runtime.getRuntime().gc();
    }

    /**
     * 实现ComponentCallbacks2接口方法
     * 这个方法会在系统内存不足时被调用，根据level级别可以执行不同程度的资源释放
     * @param level 内存紧张等级
     */
    @Override
    public void onTrimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE || 
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            // 内存非常紧张，需要立即释放所有可能的资源
            Log.i(TAG, "内存严重不足，SensorService执行紧急资源释放");
            
            // 如果只是在监控(不记录)状态，可以暂停监控
            if (!isRecordingToFile && sensorCollector != null && sensorCollector.isListening()) {
                sensorCollector.stopListening();
                updateNotificationSafely("Monitoring paused (Low Memory)");
                
                // 设置定时器，稍后尝试恢复
                serviceHandler.postDelayed(() -> {
                    // 再次检查服务和SensorCollector是否有效
                    if (SensorService.this == null || sensorCollector == null) return;
                    if (isScreenOn && !sensorCollector.isListening()) {
                        sensorCollector.startListening(samplingRateMs);
                        updateNotificationSafely("Monitoring sensor data (Not Saving)");
                    }
                }, 3000); // 3秒后尝试恢复
            }
            
            // 建议系统执行GC
            Runtime.getRuntime().gc();
            
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            // 内存较为紧张，可以释放一些非关键资源
            Log.i(TAG, "内存较为紧张，SensorService释放部分资源");
            
            // 如果采样率过高，可以考虑降低采样率
            if (samplingRateMs < 50 && sensorCollector != null && sensorCollector.isListening()) {
                int oldRate = samplingRateMs;
                samplingRateMs = Math.min(samplingRateMs * 2, 50); // 降低采样率，但不超过50ms
                
                // 重启传感器监听，使用新的采样率
                sensorCollector.stopListening();
                sensorCollector.startListening(samplingRateMs);
                Log.d(TAG, "由于内存压力，临时降低采样率: " + oldRate + "ms -> " + samplingRateMs + "ms");
            }
            
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // 内存有些紧张，可以考虑释放缓存
            Log.i(TAG, "内存略有紧张，SensorService执行轻度资源释放");
        }
    }
}
