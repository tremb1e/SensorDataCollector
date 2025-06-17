package com.example.sensordatacollector;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.content.res.Configuration;
import android.app.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据管理器，负责处理所有收集的数据
 */
public class DataManager implements SensorCollector.DataCollectionListener, ComponentCallbacks2 {
    private static final String TAG = "DataManager";

    // 使用volatile确保实例可见性
    private static volatile DataManager instance;

    // 使用final保证线程安全，并加锁处理
    private final List<DataRecordListener> listeners = new ArrayList<>();

    // 使用单线程执行器处理数据
    private ExecutorService processExecutor;

    // 时间戳管理器
    private TimestampManager timestampManager;

    // 线程池状态监控
    private final AtomicBoolean isExecutorRunning = new AtomicBoolean(true);

    // 这个标志现在控制 StorageManager 是否写入文件
    private final AtomicBoolean shouldRecordToFile = new AtomicBoolean(false);

    // 这个标志表示是否正在收集数据
    private final AtomicBoolean collecting = new AtomicBoolean(false);


    
    // 前台应用管理器引用
    private static volatile ForegroundAppManager foregroundAppManager;

    // 当前用户ID - 使用volatile确保线程安全
    private static volatile String currentUserId = "test";

    // 用于在主线程中执行任务
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 应用上下文引用 - 使用WeakReference避免内存泄漏
    private volatile java.lang.ref.WeakReference<Context> contextRef;
    
    // 线程池配置参数 - 优化减少线程数量
    private static final int CORE_POOL_SIZE = 1;
    private static final int MAX_POOL_SIZE = 2; // 减少最大线程数
    private static final int KEEP_ALIVE_TIME = 10; // 减少保活时间到10秒
    private static final int QUEUE_CAPACITY = 50; // 减少队列容量

    public interface DataRecordListener {
        void onNewDataRecord(DataRecord dataRecord);
    }

    // 私有构造函数，防止直接实例化
    private DataManager() {
        Log.d(TAG, "创建DataManager单例");
        // 初始化时间戳管理器
        this.timestampManager = TimestampManager.getInstance();
        // 创建线程池
        createExecutor();
    }

    /**
     * 初始化应用上下文
     * 必须在使用前调用一次此方法，通常在Application中
     */
    public void initWithContext(Context context) {
        if (context != null) {
            this.contextRef = new java.lang.ref.WeakReference<>(context.getApplicationContext());
            // 注册内存回调 - 安全地使用WeakReference
            Context appContext = this.contextRef.get();
            if (appContext != null) {
                appContext.registerComponentCallbacks(this);
                Log.d(TAG, "DataManager已注册内存回调");
            } else {
                Log.w(TAG, "无法从WeakReference获取有效的ApplicationContext");
            }
        }
    }

    /**
     * 设置前台应用管理器
     */
    public void setForegroundAppManager(ForegroundAppManager manager) {
        foregroundAppManager = manager;
    }

    /**
     * 设置当前用户ID
     */
    public static synchronized void setCurrentUserId(String userId) {
        currentUserId = userId != null && !userId.trim().isEmpty() ? userId.trim() : "test";
        Log.d(TAG, "设置用户ID: " + currentUserId);
    }

    /**
     * 获取当前用户ID
     */
    public static synchronized String getCurrentUserId() {
        return currentUserId != null ? currentUserId : "test";
    }



    /**
     * 获取DataManager单例实例，使用双重检查锁定
     */
    public static DataManager getInstance() {
        if (instance == null) {
            synchronized (DataManager.class) {
                if (instance == null) {
                    instance = new DataManager();
                }
            }
        }
        return instance;
    }

    /**
     * 添加数据记录监听器
     */
    public void addListener(DataRecordListener listener) {
        if (listener == null) return;

        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
                Log.d(TAG, "添加数据监听器，当前监听器数量: " + listeners.size());
            }
        }
    }

    /**
     * 移除数据记录监听器
     */
    public void removeListener(DataRecordListener listener) {
        if (listener == null) return;

        synchronized (listeners) {
            if (listeners.remove(listener)) {
                Log.d(TAG, "移除数据监听器，当前监听器数量: " + listeners.size());
            }
        }
    }

    /**
     * 通知所有监听器有新数据
     */
    private void notifyListeners(final DataRecord dataRecord) {
        if (dataRecord == null) {
            Log.w(TAG, "尝试通知空数据记录");
            return;
        }

        synchronized (listeners) {
            if (listeners.isEmpty()) {
                Log.v(TAG, "没有数据监听器");
                return;
            }

            // 创建副本，防止并发修改
            final List<DataRecordListener> listenersCopy = new ArrayList<>(listeners);

            // 检查线程池状态并在处理线程中通知所有监听器
            if (processExecutor != null && !processExecutor.isShutdown()) {
                try {
                    processExecutor.submit(() -> {
                        for (DataRecordListener listener : listenersCopy) {
                            try {
                                listener.onNewDataRecord(dataRecord);
                            } catch (Exception e) {
                                Log.e(TAG, "通知监听器出错", e);
                            }
                        }
                    });
                } catch (RejectedExecutionException e) {
                    Log.w(TAG, "通知监听器任务被拒绝: " + e.getMessage());
                    handleRejectedExecution(e, () -> {
                        for (DataRecordListener listener : listenersCopy) {
                            try {
                                listener.onNewDataRecord(dataRecord);
                            } catch (Exception ex) {
                                Log.e(TAG, "通知监听器出错", ex);
                            }
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "提交通知监听器任务失败", e);
                    // 直接在当前线程通知监听器
                    for (DataRecordListener listener : listenersCopy) {
                        try {
                            listener.onNewDataRecord(dataRecord);
                        } catch (Exception ex) {
                            Log.e(TAG, "通知监听器出错", ex);
                        }
                    }
                }
            } else {
                // 线程池不可用，检查DataManager状态
                if (isExecutorRunning.get()) {
                    Log.d(TAG, "线程池不可用，尝试重建");
                    ensureExecutorAvailable();
                    // 重建后再次尝试使用线程池
                    if (processExecutor != null && !processExecutor.isShutdown()) {
                        try {
                            processExecutor.submit(() -> {
                                for (DataRecordListener listener : listenersCopy) {
                                    try {
                                        listener.onNewDataRecord(dataRecord);
                                    } catch (Exception e) {
                                        Log.e(TAG, "通知监听器出错", e);
                                    }
                                }
                            });
                            return; // 成功提交，直接返回
                        } catch (Exception e) {
                            Log.w(TAG, "重建后仍无法使用线程池: " + e.getMessage());
                        }
                    }
                } else {
                    // DataManager已关闭，静默处理，避免日志污染
                    Log.v(TAG, "DataManager已关闭，跳过数据处理");
                    return; // 直接返回，不处理数据
                }
                
                // 如果重建失败，直接在当前线程通知监听器
                Log.d(TAG, "在当前线程通知监听器");
                for (DataRecordListener listener : listenersCopy) {
                    try {
                        listener.onNewDataRecord(dataRecord);
                    } catch (Exception e) {
                        Log.e(TAG, "通知监听器出错", e);
                    }
                }
            }
        }
    }

    /**
     * 设置是否将数据记录到文件
     */
    public void setShouldRecordToFile(boolean shouldRecord) {
        shouldRecordToFile.set(shouldRecord);
        Log.d(TAG, "设置数据记录到文件状态: " + shouldRecord);
    }

    /**
     * 是否正在将数据记录到文件
     */
    public boolean isRecordingToFile() {
        return shouldRecordToFile.get();
    }

    /**
     * 设置是否正在收集数据
     */
    public void setCollecting(boolean isCollecting) {
        collecting.set(isCollecting);
        Log.d(TAG, "设置数据收集状态: " + isCollecting);
    }

    /**
     * 停止数据收集
     */
    public void stopCollecting() {
        collecting.set(false);
        Log.d(TAG, "停止数据收集");
    }

    /**
     * 是否正在收集数据
     */
    public boolean isCollecting() {
        return collecting.get();
    }

    /**
     * 获取前台应用信息
     */
    private ForegroundAppManager.ForegroundAppInfo getForegroundAppInfo() {
        if (foregroundAppManager != null) {
            return foregroundAppManager.getForegroundAppInfo();
        }
        return new ForegroundAppManager.ForegroundAppInfo("未知应用", "");
    }

    /**
     * 创建或重建执行器
     */
    private synchronized void createExecutor() {
        // 如果已存在执行器，先关闭
        if (processExecutor != null && !processExecutor.isShutdown()) {
            try {
                processExecutor.shutdown();
            } catch (Exception e) {
                Log.w(TAG, "关闭旧的执行器时出现异常", e);
            }
        }
        
        // 创建新的执行器，使用有界队列和拒绝策略
        processExecutor = new ThreadPoolExecutor(
            CORE_POOL_SIZE, 
            MAX_POOL_SIZE, 
            KEEP_ALIVE_TIME, 
            TimeUnit.SECONDS, 
            new LinkedBlockingQueue<>(QUEUE_CAPACITY), // 有界队列，限制队列容量
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "DataManager-Worker");
                    thread.setPriority(Thread.NORM_PRIORITY - 1); // 稍低优先级
                    thread.setDaemon(true); // 设置为守护线程，避免阻止应用退出
                    return thread;
                }
            },
            new RejectedExecutionHandler() {
                @Override
                public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                    // 当队列满时，记录警告但不抛出异常
                    Log.w(TAG, "数据处理队列已满，任务被拒绝");
                    
                    // 如果线程池已关闭，尝试重建
                    if (executor.isShutdown() && isExecutorRunning.get()) {
                        Log.d(TAG, "线程池已关闭，尝试重建");
                        mainHandler.post(() -> {
                            createExecutor();
                            // 重新提交任务
                            try {
                                if (processExecutor != null && !processExecutor.isShutdown()) {
                                    processExecutor.submit(r);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "重新提交任务失败", e);
                                // 直接执行任务，确保数据不丢失
                                try {
                                    r.run();
                                } catch (Exception ex) {
                                    Log.e(TAG, "直接执行任务失败", ex);
                                }
                            }
                        });
                    } else {
                        // 直接在调用线程中执行任务，确保数据不丢失
                        try {
                            Log.d(TAG, "在调用线程中直接执行被拒绝的任务");
                            r.run();
                        } catch (Exception e) {
                            Log.e(TAG, "直接执行被拒绝任务失败", e);
                        }
                    }
                }
            }
        );
        
        isExecutorRunning.set(true);
        Log.d(TAG, "创建了新的数据处理执行器 (核心线程: " + CORE_POOL_SIZE + 
                  ", 最大线程: " + MAX_POOL_SIZE + ", 队列容量: " + QUEUE_CAPACITY + ")");
    }

    /**
     * 传感器数据收集回调
     */
    @Override
    public void onDataCollected(DataRecord dataRecord) {
        if (dataRecord == null) {
            Log.w(TAG, "收到空的数据记录");
            return;
        }
        
        // 确保执行器可用
        ensureExecutorAvailable();
        
        // 检查执行器状态并处理数据
        if (processExecutor != null && !processExecutor.isShutdown()) {
            try {
                processExecutor.submit(() -> {
                    try {
                        notifyListeners(dataRecord);
                    } catch (Exception e) {
                        Log.e(TAG, "处理传感器数据出错", e);
                    }
                });
            } catch (RejectedExecutionException e) {
                handleRejectedExecution(e, () -> notifyListeners(dataRecord));
            } catch (Exception e) {
                Log.e(TAG, "提交任务时发生未知异常", e);
                // 直接在当前线程处理，确保数据不丢失
                notifyListeners(dataRecord);
            }
        } else {
            // 检查DataManager状态，避免不必要的日志和处理
            if (isExecutorRunning.get()) {
                Log.w(TAG, "线程池不可用，在当前线程处理传感器数据");
                // 直接处理数据，确保不丢失
                notifyListeners(dataRecord);
            } else {
                // DataManager已关闭，静默跳过
                Log.v(TAG, "DataManager已关闭，跳过传感器数据处理");
            }
        }
    }

    /**
     * 确保执行器可用
     */
    private synchronized void ensureExecutorAvailable() {
        if (processExecutor == null || processExecutor.isShutdown()) {
            if (isExecutorRunning.get()) {
                Log.d(TAG, "重建数据处理线程池");
                createExecutor();
            } else {
                // DataManager已关闭，不重建线程池，也不记录警告（避免日志污染）
                Log.v(TAG, "DataManager已关闭，跳过线程池重建");
            }
        }
    }
    
    /**
     * 处理被拒绝的任务执行
     */
    private void handleRejectedExecution(RejectedExecutionException e, Runnable fallbackTask) {
        Log.w(TAG, "任务被拒绝执行: " + e.getMessage());
        
        // 如果执行器已关闭且应该运行，尝试重建
        if ((processExecutor == null || processExecutor.isShutdown()) && isExecutorRunning.get()) {
            Log.d(TAG, "尝试重建数据处理线程池");
            try {
                createExecutor();
                
                // 重建后尝试重新提交任务
                if (processExecutor != null && !processExecutor.isShutdown()) {
                    try {
                        processExecutor.submit(fallbackTask);
                        return;
                    } catch (Exception retryEx) {
                        Log.e(TAG, "重新提交任务失败", retryEx);
                    }
                }
            } catch (Exception createEx) {
                Log.e(TAG, "重建执行器失败", createEx);
            }
        }
        
        // 如果重建失败或其他情况，直接在当前线程执行
        Log.w(TAG, "线程池不可用，在当前线程执行任务");
        try {
            fallbackTask.run();
        } catch (Exception ex) {
            Log.e(TAG, "执行fallback任务失败", ex);
        }
    }

    /**
     * 处理内存压力，实现ComponentCallbacks2接口
     */
    @Override
    public void onTrimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || 
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            // 严重内存压力，清理所有可能的资源
            Log.i(TAG, "严重内存压力，DataManager释放所有非必要资源");
            
            // 停止采集，防止持续消耗内存
            stopCollecting();
            
            // 通知服务清理资源
            if (contextRef != null && contextRef.get() != null && contextRef.get() instanceof Service) {
                try {
                    ((Service) contextRef.get()).stopForeground(true);
                    Log.d(TAG, "尝试停止前台服务，减轻内存压力");
                } catch (Exception e) {
                    Log.e(TAG, "停止前台服务失败", e);
                }
            }
        }
        // 中等内存压力时不需要特殊处理
    }
    
    /**
     * 实现ComponentCallbacks2接口方法，处理配置变化
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // 配置变化时不需要特殊处理
        Log.d(TAG, "配置变化: " + newConfig);
    }
    
    /**
     * 处理低内存警告，实现ComponentCallbacks2接口
     */
    @Override
    public void onLowMemory() {
        Log.i(TAG, "系统内存不足，DataManager执行紧急资源释放");
        
        // 停止数据收集
        collecting.set(false);
        
        // 尝试停止所有后台任务
        shutdown();
    }

    /**
     * 关闭资源
     */
    public void shutdown() {
        // 取消注册内存回调
        if (contextRef != null && contextRef.get() != null) {
            contextRef.get().unregisterComponentCallbacks(this);
        }
        
        // 设置标志
        isExecutorRunning.set(false);
        
        // 关闭线程池
        if (processExecutor != null && !processExecutor.isShutdown()) {
            processExecutor.shutdown();
            try {
                // 等待所有任务完成，最多等待5秒
                if (!processExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    // 强制关闭
                    processExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "关闭线程池被中断", e);
                processExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        Log.i(TAG, "DataManager资源已关闭");
    }
}