package com.example.sensordatacollector;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("deprecation")
public class MainActivity extends AppCompatActivity implements DataManager.DataRecordListener, ComponentCallbacks2 {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 101;
    private static final int REQUEST_CODE_USAGE_STATS = 102;
    private static final String PREFS_NAME = "app_preferences";
    private static final String PREF_SERVER_IP = "server_ip";
    private static final String PREF_SERVER_PORT = "server_port";
    private static final String PREF_SAMPLING_RATE = "sampling_rate";
    private static final String PREF_USER_ID = "user_id";
    
    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.INTERNET,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.POST_NOTIFICATIONS
    };

    // UI 组件
    private TextView tvAccelerometerData, tvMagnetometerData, tvGyroscopeData;
    private TextView tvFilePath, tvStatus, tvBatteryStats, tvPendingFiles, tvLastUploadTime, tvUploadProgress;
    private TextView tvStorageSize, tvForegroundApp;
    private EditText etServerIp, etServerPort, etUserId;
    private Spinner etSamplingRate;
    private Button btnUpload, btnStart, btnStop, btnPermission, btnCleanFiles, btnKeepRecentFiles, btnViewFiles;
    private View statusIndicator;
    private ProgressBar progressUpload;
    private RecyclerView recyclerRecentApps;
    private RecentAppsAdapter recentAppsAdapter;
    private TextView tvCurrentFile;

    // 管理器
    private DataManager dataManager;
    private StorageManager storageManager;
    private NetworkManager networkManager;
    private BatteryStatsManager batteryStatsManager;
    private SensorCollector sensorCollector;
    private ForegroundAppManager foregroundAppManager;
    
    private SensorService sensorService;
    private boolean isBound = false;
    
    // 定时更新UI任务
    private final Handler uiUpdateHandler = new Handler(Looper.getMainLooper());
    private static final long UI_UPDATE_INTERVAL_MS = 3000;
    private Runnable uiUpdateRunnable;
    
    // 屏幕是否点亮
    private boolean isScreenOn = true;
    private volatile boolean isActivityVisible = false;
    private SharedPreferences preferences;
    private ExecutorService backgroundTaskExecutor;
    private AlertDialog currentDialog;
    private BroadcastReceiver screenStateReceiver;
    
    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            SensorService.LocalBinder binder = (SensorService.LocalBinder) service;
            sensorService = binder.getService();
            isBound = true;
            Log.i(TAG, "服务已连接: " + name.getClassName());
            updateServiceStatus();
            if (sensorService != null && etSamplingRate != null) {
                etSamplingRate.setSelection(findSamplingRateIndex(sensorService.getSamplingRateMs()));
            }
            checkAndRequestUsageStatsPermission();
            startSensorMonitoringOnly();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            sensorService = null;
            Log.w(TAG, "服务已断开连接: " + name.getClassName());
            updateServiceStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        backgroundTaskExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "MainActivity-BackgroundWorker");
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        initializeViews();
        initializeManagers();
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
        setupListeners();
        loadSavedSettings();
        initializeUiUpdateRunnable();
        bindSensorService();
        registerScreenStateReceiver();
        getApplicationContext().registerComponentCallbacks(this);
    }
    
    private void initializeViews() {
        tvAccelerometerData = findViewById(R.id.tv_accelerometer_data);
        tvMagnetometerData = findViewById(R.id.tv_magnetometer_data);
        tvGyroscopeData = findViewById(R.id.tv_gyroscope_data);
        tvFilePath = findViewById(R.id.tv_file_path);
        tvStatus = findViewById(R.id.tv_status);
        tvBatteryStats = findViewById(R.id.tv_battery_stats);
        tvPendingFiles = findViewById(R.id.tv_pending_files);
        tvLastUploadTime = findViewById(R.id.tv_last_upload_time);
        tvUploadProgress = findViewById(R.id.tv_upload_progress);
        tvStorageSize = findViewById(R.id.tv_storage_size);
        tvForegroundApp = findViewById(R.id.tv_foreground_app);
        etServerIp = findViewById(R.id.et_server_ip);
        etServerPort = findViewById(R.id.et_server_port);
        etUserId = findViewById(R.id.et_user_id);
        etSamplingRate = findViewById(R.id.et_sampling_rate);
        btnUpload = findViewById(R.id.btn_upload);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnPermission = findViewById(R.id.btn_permission);
        btnCleanFiles = findViewById(R.id.btn_clean_files);
        btnKeepRecentFiles = findViewById(R.id.btn_keep_recent_files);
        btnViewFiles = findViewById(R.id.btn_view_files);
        statusIndicator = findViewById(R.id.status_indicator);
        progressUpload = findViewById(R.id.progress_upload);
        tvCurrentFile = findViewById(R.id.tv_current_file);
        
        // 初始化RecyclerView
        recyclerRecentApps = findViewById(R.id.recycler_recent_apps);
        recyclerRecentApps.setLayoutManager(new LinearLayoutManager(this));
        // 设置recyclerView不保留数据
        recyclerRecentApps.setItemViewCacheSize(0);
        // 防止Pinning内存问题
        recyclerRecentApps.setHasFixedSize(true);
        
        recentAppsAdapter = new RecentAppsAdapter(new ArrayList<>());
        recyclerRecentApps.setAdapter(recentAppsAdapter);
        
        // 初始状态
        statusIndicator.setActivated(false);
        btnStop.setEnabled(false);
        
        // 优化RecyclerView以减少内存使用
        if (recyclerRecentApps != null) {
            // 使用固定大小可以改善性能
            recyclerRecentApps.setHasFixedSize(true);
            
            // 设置布局管理器
            LinearLayoutManager layoutManager = new LinearLayoutManager(this);
            layoutManager.setInitialPrefetchItemCount(4); // 预取4个项目
            recyclerRecentApps.setLayoutManager(layoutManager);
            
            // 创建和设置RecycledViewPool，限制每种类型的ViewHolder的最大缓存数量
            RecyclerView.RecycledViewPool viewPool = new RecyclerView.RecycledViewPool();
            viewPool.setMaxRecycledViews(0, 10); // 限制默认ViewType最多缓存10个ViewHolder
            recyclerRecentApps.setRecycledViewPool(viewPool);
            
            // 设置项目动画为null可以避免一些不必要的内存使用
            recyclerRecentApps.setItemAnimator(null);
            
            // 启用嵌套滚动以支持滚动
            recyclerRecentApps.setNestedScrollingEnabled(true);
            
            // 创建适配器并设置
            recentAppsAdapter = new RecentAppsAdapter(new ArrayList<>());
            recentAppsAdapter.setHasStableIds(true); // 启用稳定ID提高性能
            recyclerRecentApps.setAdapter(recentAppsAdapter);
        }
        
        // 采样率下拉菜单已通过android:entries="@array/sampling_rates"设置
        
        // 设置采样率选择监听器
        etSamplingRate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0) {
                    int samplingRate = Integer.parseInt(parent.getItemAtPosition(position).toString());
                    if (sensorService != null) {
                        sensorService.setSamplingRateMs(samplingRate);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 什么都不做
            }
        });
    }
    
    /**
     * 加载保存的设置
     */
    private void loadSavedSettings() {
        String serverIp = preferences.getString(PREF_SERVER_IP, "10.26.68.24");
        String serverPort = preferences.getString(PREF_SERVER_PORT, "12000");
        String userId = preferences.getString(PREF_USER_ID, "test");
        int samplingRate = preferences.getInt(PREF_SAMPLING_RATE, 10);
        
        etServerIp.setText(serverIp);
        etServerPort.setText(serverPort);
        etUserId.setText(userId);
        etSamplingRate.setSelection(findSamplingRateIndex(samplingRate));
        
        // 设置DataManager的用户ID
        DataManager.setCurrentUserId(userId);
    }
    
    /**
     * 保存设置
     */
    private void saveSettings() {
        String serverIp = etServerIp.getText().toString().trim();
        String serverPort = etServerPort.getText().toString().trim();
        String userId = etUserId.getText().toString().trim();
        int samplingRate = 10; // 默认值，以防解析失败
        if (etSamplingRate != null && etSamplingRate.getSelectedItem() != null) {
            try {
                samplingRate = Integer.parseInt(etSamplingRate.getSelectedItem().toString());
            } catch (NumberFormatException e) {
                Log.e(TAG, "无效的采样率格式，使用默认值10ms: " + etSamplingRate.getSelectedItem().toString(), e);
                samplingRate = 10; // Fallback to default
            }
        } else {
            Log.w(TAG, "采样率Spinner或选中项为空，使用默认值10ms");
            samplingRate = 10; // Fallback to default
        }
        
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(PREF_SERVER_IP, serverIp);
        editor.putString(PREF_SERVER_PORT, serverPort);
        editor.putString(PREF_USER_ID, userId);
        editor.putInt(PREF_SAMPLING_RATE, samplingRate);
        editor.apply();
        
        // 设置DataManager的用户ID
        DataManager.setCurrentUserId(userId);
    }
    
    /**
     * 初始化管理器
     */
    private void initializeManagers() {
        dataManager = DataManager.getInstance();
        // 初始化DataManager的上下文，用于内存回调
        dataManager.initWithContext(this);
        
        // 确保用户ID在初始化时就设置好
        String currentUserId = preferences.getString(PREF_USER_ID, "test");
        DataManager.setCurrentUserId(currentUserId);
        
        batteryStatsManager = BatteryStatsManager.getInstance(this);
        storageManager = new StorageManager(this);
        networkManager = new NetworkManager(this);
        sensorCollector = new SensorCollector(this, dataManager);
        foregroundAppManager = ForegroundAppManager.getInstance(this);
        
        // 设置DataManager的ForegroundAppManager引用
        dataManager.setForegroundAppManager(foregroundAppManager);
        
        dataManager.addListener(this);
        dataManager.addListener(storageManager);
    }
    
    /**
     * 检查并请求使用统计（前台应用）权限
     */
    private void checkAndRequestUsageStatsPermission() {
        if (sensorService != null && !sensorService.hasUsageStatsPermission()) {
            // 显示权限按钮
            if (btnPermission != null) {
                btnPermission.setVisibility(View.VISIBLE);
            }
            
            // 显示权限提示对话框
            AlertDialog permissionDialog = new AlertDialog.Builder(this)
                .setTitle("需要使用统计权限")
                .setMessage("为了获取前台应用名称，需要授予使用统计权限。\n\n请在跳转的设置页面中，找到本应用，并开启「允许查看使用情况」权限。")
                .setPositiveButton("去授权", (d, which) -> {
                    if (foregroundAppManager != null) {
                        foregroundAppManager.openUsageAccessSettings();
                    }
                    dismissCurrentDialog(); // 正确关闭
                })
                .setNegativeButton("稍后", (d, which) -> dismissCurrentDialog())
                .setOnCancelListener(d -> dismissCurrentDialog())
                .show();
            currentDialog = permissionDialog; // 保存对话框引用
        } else {
            // 隐藏权限按钮
            if (btnPermission != null) {
                btnPermission.setVisibility(View.GONE);
            }
        }
    }
    
    /**
     * 设置监听器
     */
    private void setupListeners() {
        // 开始按钮
        btnStart.setOnClickListener(v -> {
            if (!isBound || sensorService == null) {
                Toast.makeText(this, "服务未连接，请稍后再试", Toast.LENGTH_SHORT).show();
                bindSensorService();
                return;
            }
            
            int samplingRate = 10; // 默认值
            if (etSamplingRate != null && etSamplingRate.getSelectedItem() != null) {
                try {
                    samplingRate = Integer.parseInt(etSamplingRate.getSelectedItem().toString());
                } catch (NumberFormatException e) {
                    Log.e(TAG, "启动收集时，无效的采样率格式，使用默认值10ms: " + etSamplingRate.getSelectedItem().toString(), e);
                    samplingRate = 10; // Fallback to default
                }
            } else {
                 Log.w(TAG, "启动收集时，采样率Spinner或选中项为空，使用默认值10ms");
                 samplingRate = 10; // Fallback to default
            }
            
            // 保存设置
            saveSettings();
            
            // 设置采样率并启动服务（开始记录数据）
            sensorService.startCollecting(samplingRate);
            
            // 更新UI
            updateServiceStatus();
            statusIndicator.setActivated(true);
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            tvStatus.setText("状态: 正在运行 (数据写入中)");
            
            Snackbar.make(v, "开始记录传感器数据，采样率: " + samplingRate + "ms", Snackbar.LENGTH_LONG).show();
        });
        
        // 停止按钮
        btnStop.setOnClickListener(v -> {
            if (isBound && sensorService != null) {
                // 停止记录，但继续监控
                sensorService.stopCollecting();
                
                // 刷新为仅监控状态
                startSensorMonitoringOnly();
                
                // 更新UI
                updateServiceStatus();
                statusIndicator.setActivated(false);
                btnStart.setEnabled(true);
                btnStop.setEnabled(false);
                
                Snackbar.make(v, "停止记录传感器数据", Snackbar.LENGTH_LONG).show();
            }
        });
        
        // 上传按钮
        btnUpload.setOnClickListener(v -> {
            String ip = etServerIp.getText().toString().trim();
            String port = etServerPort.getText().toString().trim();
            
            if (ip.isEmpty() || port.isEmpty()) {
                Toast.makeText(this, "请输入服务器IP和端口", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 检查IP格式
            if (!isValidIpAddress(ip) && !ip.equals("localhost")) {
                Toast.makeText(this, "IP地址格式不正确", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 检查端口范围
            try {
                int portNum = Integer.parseInt(port);
                if (portNum <= 0 || portNum > 65535) {
                    Toast.makeText(this, "端口号必须在1-65535之间", Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this, "端口号必须是数字", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 检查网络连接状态
            if (!isNetworkAvailable()) {
                AlertDialog networkDialog = new AlertDialog.Builder(this)
                    .setTitle("网络连接警告")
                    .setMessage("当前无网络连接，上传可能会失败。确定要继续吗？")
                    .setPositiveButton("继续", (d, which) -> {
                        proceedWithUpload(ip, port);
                        dismissCurrentDialog();
                    })
                    .setNegativeButton("取消", (d, which) -> dismissCurrentDialog())
                    .setOnCancelListener(d -> dismissCurrentDialog())
                    .show();
                currentDialog = networkDialog;
                return;
            }
            
            proceedWithUpload(ip, port);
        });
        
        // 长按上传按钮显示高级选项
        btnUpload.setOnLongClickListener(v -> {
            showUploadAdvancedOptions();
            return true;
        });
        
        // 添加时间戳对齐测试（长按停止按钮触发）
        btnStop.setOnLongClickListener(v -> {
            if (backgroundTaskExecutor != null && !backgroundTaskExecutor.isShutdown()) {
                backgroundTaskExecutor.submit(() -> {
                    Log.i(TAG, "开始执行时间戳对齐测试");
                    TimestampAlignmentTest.runAllTests();
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "时间戳对齐测试完成，请查看日志", Toast.LENGTH_LONG).show();
                    });
                });
            }
            return true;
        });
        
        // 查看文件按钮
        btnViewFiles.setOnClickListener(v -> {
            openFileManagerToViewFiles();
        });
        
        // 权限按钮
        btnPermission.setOnClickListener(v -> {
            if (foregroundAppManager != null) {
                foregroundAppManager.openUsageAccessSettings();
            }
        });
        
        // 清理文件按钮
        btnCleanFiles.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("确认清理文件")
                .setMessage("此操作将删除所有传感器数据文件（除了当前正在写入的文件）。\n\n确定要继续吗？")
                .setPositiveButton("确定", (d, which) -> {
                    if (backgroundTaskExecutor == null || backgroundTaskExecutor.isShutdown()) { // 安全检查
                        Log.w(TAG, "BackgroundTaskExecutor 未初始化或已关闭，无法清理文件。");
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "操作失败，请稍后重试", Toast.LENGTH_SHORT).show());
                        dismissCurrentDialog();
                        return;
                    }
                    backgroundTaskExecutor.submit(() -> {
                        try {
                            int deletedCount = storageManager.cleanAllFiles();
                            runOnUiThread(() -> {
                                String message = "文件清理完成，共删除了 " + deletedCount + " 个文件";
                                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                                updateFileInfo();
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "清理文件失败", e);
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "清理文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
                    dismissCurrentDialog();
                })
                .setNegativeButton("取消", (d, which) -> dismissCurrentDialog())
                .setOnCancelListener(d -> dismissCurrentDialog())
                .show();
            currentDialog = dialog;
        });
        
        // 保留最近10份文件按钮
        btnKeepRecentFiles.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("确认保留最近文件")
                .setMessage("此操作将保留最近的10个传感器数据文件，删除其余所有文件。\n\n确定要继续吗？")
                .setPositiveButton("确定", (d, which) -> {
                    if (backgroundTaskExecutor == null || backgroundTaskExecutor.isShutdown()) { // 安全检查
                        Log.w(TAG, "BackgroundTaskExecutor 未初始化或已关闭，无法整理文件。");
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "操作失败，请稍后重试", Toast.LENGTH_SHORT).show());
                        dismissCurrentDialog();
                        return;
                    }
                    backgroundTaskExecutor.submit(() -> {
                        try {
                            int deletedCount = storageManager.keepRecentFiles(10);
                            runOnUiThread(() -> {
                                String message = "文件整理完成，保留了最近10个文件，删除了 " + deletedCount + " 个旧文件";
                                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                                updateFileInfo();
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "整理文件失败", e);
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "整理文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
                    dismissCurrentDialog();
                })
                .setNegativeButton("取消", (d, which) -> dismissCurrentDialog())
                .setOnCancelListener(d -> dismissCurrentDialog())
                .show();
            currentDialog = dialog;
        });
    }
    
    /**
     * 开始文件上传
     */
    private void startFileUpload(List<File> filesToUpload, String ip, String port) {
        if (filesToUpload == null || filesToUpload.isEmpty()) {
            Toast.makeText(this, "没有文件需要上传", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (networkManager.isUploading()) {
            Toast.makeText(this, "当前已有上传任务正在进行", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 显示进度条
        tvUploadProgress.setVisibility(View.VISIBLE);
        progressUpload.setVisibility(View.VISIBLE);
        progressUpload.setProgress(0);
        btnUpload.setEnabled(false);
        
        // 记录上传开始时间
        final long startTime = System.currentTimeMillis();
        final int totalFiles = filesToUpload.size();
        final long totalSize = getTotalFileSize(filesToUpload);
        
        Log.i(TAG, "开始上传 " + totalFiles + " 个文件，总大小: " + formatFileSize(totalSize) + " 到服务器: " + ip + ":" + port);
        tvUploadProgress.setText("正在准备上传 " + totalFiles + " 个文件...");
        
        // 执行上传
        networkManager.uploadFiles(filesToUpload, ip, port, new NetworkManager.UploadCallback() {
            @Override
            public void onSuccess(String responseBody) {
                // 检查Activity是否存活
                if (isDestroyed() || isFinishing()) {
                    Log.w(TAG, "Upload onSuccess: MainActivity is not active, skipping UI update.");
                    return;
                }
                long duration = System.currentTimeMillis() - startTime;
                String successMessage = "上传成功: " + responseBody + " (耗时: " + formatDuration(duration) + ")";
                Log.i(TAG, successMessage);
                
                runOnUiThread(() -> {
                    tvUploadProgress.setText(successMessage);
                    progressUpload.setProgress(100);
                    btnUpload.setEnabled(true);
                    
                    // 标记文件已上传
                    storageManager.markFilesAsUploaded(filesToUpload);
                    updateFileInfo();
                    
                    // 显示成功提示
                    Snackbar.make(btnUpload, "成功上传了 " + filesToUpload.size() + " 个文件", Snackbar.LENGTH_LONG).show();
                    
                    // 5秒后隐藏进度条
                    uiUpdateHandler.postDelayed(() -> {
                        tvUploadProgress.setVisibility(View.GONE);
                        progressUpload.setVisibility(View.GONE);
                    }, 5000);
                });
            }

            @Override
            public void onFailure(String errorMessage) {
                // 检查Activity是否存活
                if (isDestroyed() || isFinishing()) {
                    Log.w(TAG, "Upload onFailure: MainActivity is not active, skipping UI update.");
                    return;
                }
                long duration = System.currentTimeMillis() - startTime;
                String failureMessage = "上传失败 (耗时: " + formatDuration(duration) + ")";
                Log.e(TAG, failureMessage + ": " + errorMessage);
                
                runOnUiThread(() -> {
                    // 在UI上显示更友好的错误信息
                    String displayErrorMsg = errorMessage;
                    if (errorMessage.length() > 100) {
                        // 截断过长的错误信息
                        displayErrorMsg = errorMessage.substring(0, 100) + "...";
                    }
                    
                    tvUploadProgress.setText("上传失败: " + displayErrorMsg);
                    btnUpload.setEnabled(true);
                    
                    // 显示完整错误信息的对话框
                    AlertDialog uploadErrorDialog = new AlertDialog.Builder(MainActivity.this)
                        .setTitle("上传失败")
                        .setMessage(errorMessage)
                        .setPositiveButton("重试", (d, which) -> {
                            // 重试上传
                            dismissCurrentDialog(); // 先关闭当前错误对话框
                            startFileUpload(filesToUpload, ip, port);
                        })
                        .setNeutralButton("查看详情", (d, which) -> {
                            // 显示更详细的错误日志或排除建议
                            dismissCurrentDialog(); // 先关闭当前错误对话框
                            showUploadTroubleshootDialog();
                        })
                        .setOnCancelListener(d -> dismissCurrentDialog())
                        .show();
                    currentDialog = uploadErrorDialog; // 保存对话框引用
                    
                    // 10秒后隐藏进度条
                    uiUpdateHandler.postDelayed(() -> {
                        tvUploadProgress.setVisibility(View.GONE);
                        progressUpload.setVisibility(View.GONE);
                    }, 10000);
                });
            }

            @Override
            public void onProgress(int overallProgress) {
                // 检查Activity是否存活
                if (isDestroyed() || isFinishing()) {
                    // Log.w(TAG, "Upload onProgress: MainActivity is not active, skipping UI update."); // 进度更新频繁，避免过多日志
                    return;
                }
                runOnUiThread(() -> {
                    // 再次检查Activity是否仍然存活，因为runOnUiThread是异步的
                    if (isDestroyed() || isFinishing()) {
                        return;
                    }
                    progressUpload.setProgress(overallProgress);
                    // 计算预计剩余时间
                    if (overallProgress > 0) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        long estimated = elapsed * 100 / overallProgress;
                        long remaining = estimated - elapsed;
                        
                        String progressText = String.format("上传进度: %d%% (%d/%d个文件)", 
                            overallProgress, 
                            Math.min((overallProgress * totalFiles) / 100, totalFiles),
                            totalFiles);
                        
                        if (remaining > 0) {
                            progressText += ", 预计剩余: " + formatDuration(remaining);
                        }
                        
                        tvUploadProgress.setText(progressText);
                    } else {
                        tvUploadProgress.setText("上传进度: " + overallProgress + "%");
                    }
                });
            }
        });
    }
    
    /**
     * 显示上传问题排除对话框
     */
    private void showUploadTroubleshootDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("上传故障排除")
            .setMessage(
                "请检查以下可能的问题：\n\n" +
                "1. 确保服务器地址和端口正确\n" +
                "2. 确保服务器已启动并可访问\n" +
                "3. 检查网络连接是否正常\n" +
                "4. 检查文件权限\n" +
                "5. 如果使用WiFi，确保连接稳定\n\n" +
                "服务器地址: " + etServerIp.getText().toString() + "\n" +
                "服务器端口: " + etServerPort.getText().toString()
            )
            .setPositiveButton("确定", (d, which) -> dismissCurrentDialog())
            .setNegativeButton("清除上传进度", (d, which) -> {
                networkManager.clearAllUploadProgress();
                Toast.makeText(MainActivity.this, "已清除所有上传进度记录", Toast.LENGTH_SHORT).show();
                dismissCurrentDialog();
            })
            .setOnCancelListener(d -> dismissCurrentDialog())
            .show();
        currentDialog = dialog;
    }
    
    /**
     * 获取文件列表的总大小
     */
    private long getTotalFileSize(List<File> files) {
        long total = 0;
        for (File file : files) {
            if (file != null && file.exists()) {
                total += file.length();
            }
        }
        return total;
    }
    
    /**
     * 格式化文件大小
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", size / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
        }
    }
    
    /**
     * 格式化时间间隔为可读形式
     */
    private String formatDuration(long milliseconds) {
        if (milliseconds < 1000) {
            return milliseconds + "毫秒";
        } else if (milliseconds < 60 * 1000) {
            return String.format("%.1f秒", milliseconds / 1000.0);
        } else {
            long minutes = milliseconds / (60 * 1000);
            long seconds = (milliseconds % (60 * 1000)) / 1000;
            return minutes + "分" + seconds + "秒";
        }
    }
    
    /**
     * 处理上传流程
     */
    private void proceedWithUpload(String ip, String port) {
        // 保存设置
        saveSettings();
        
        // 检查是否有未上传文件
        List<File> filesToUpload = storageManager.getUnuploadedFiles();
        if (filesToUpload.isEmpty()) {
            Toast.makeText(this, "没有文件需要上传", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 获取总文件大小
        long totalSizeKB = storageManager.getUnuploadedFilesTotalSizeKB();
        
        // 显示确认对话框
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("上传确认")
            .setMessage("准备上传 " + filesToUpload.size() + " 个文件，总计约 " + 
                       totalSizeKB + " KB (" + formatFileSize(totalSizeKB * 1024) + ")\n\n" +
                       "服务器: " + ip + ":" + port)
            .setPositiveButton("开始上传", (d, which) -> {
                startFileUpload(filesToUpload, ip, port);
                dismissCurrentDialog();
            })
            .setNegativeButton("取消", (d, which) -> dismissCurrentDialog())
            .setOnCancelListener(d -> dismissCurrentDialog())
            .show();
        currentDialog = dialog;
    }
    
    /**
     * 验证IP地址格式
     */
    private boolean isValidIpAddress(String ip) {
        String ipPattern = "^([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
                         "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
                         "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
                         "([01]?\\d\\d?|2[0-4]\\d|25[0-5])$";
        return ip.matches(ipPattern);
    }
    
    /**
     * 显示上传高级选项
     */
    private void showUploadAdvancedOptions() {
        String[] options = {
            "清除上传进度记录",
            "取消正在进行的上传",
            "查看未上传文件列表",
            "查看所有数据文件",
            "测试服务器连接"
        };
        
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("上传高级选项")
            .setItems(options, (d, which) -> {
                switch (which) {
                    case 0: // 清除上传进度记录
                        networkManager.clearAllUploadProgress();
                        Toast.makeText(MainActivity.this, "已清除所有上传进度记录", Toast.LENGTH_SHORT).show();
                        break;
                    case 1: // 取消正在进行的上传
                        if (networkManager.isUploading()) {
                            networkManager.cancelAllUploads();
                            Toast.makeText(MainActivity.this, "已取消所有上传任务", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "当前没有正在进行的上传任务", Toast.LENGTH_SHORT).show();
                        }
                        break;
                    case 2: // 查看未上传文件列表
                        showUnuploadedFilesList();
                        break;
                    case 3: // 查看所有数据文件
                        showAllDataFilesList();
                        break;
                    case 4: // 测试服务器连接
                        testServerConnection();
                        break;
                }
                dismissCurrentDialog();
            })
            .setNegativeButton("取消", (d,which) -> dismissCurrentDialog())
            .setOnCancelListener(d -> dismissCurrentDialog())
            .show();
        currentDialog = dialog;
    }
    
    /**
     * 显示未上传文件列表
     */
    private void showUnuploadedFilesList() {
        List<File> files = storageManager.getUnuploadedFiles();
        if (files.isEmpty()) {
            Toast.makeText(this, "没有未上传的文件", Toast.LENGTH_SHORT).show();
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(files.size()).append(" 个未上传文件:\n\n");
        
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            sb.append(i + 1).append(". ")
              .append(file.getName())
              .append(" (").append(formatFileSize(file.length())).append(")\n");
            
            // 限制显示数量，避免对话框过长
            if (i >= 19 && files.size() > 20) {
                sb.append("...以及 ").append(files.size() - 20).append(" 个其他文件");
                break;
            }
        }
        
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("未上传文件列表")
            .setMessage(sb.toString())
            .setPositiveButton("确定", (d, which) -> dismissCurrentDialog())
            .setOnCancelListener(d -> dismissCurrentDialog())
            .show();
        currentDialog = dialog;
    }
    
    /**
     * 查看数据文件内容
     */
    private void openFileManagerToViewFiles() {
        if (backgroundTaskExecutor == null || backgroundTaskExecutor.isShutdown()) {
            Log.w(TAG, "BackgroundTaskExecutor 未初始化或已关闭，无法查看文件。");
            Toast.makeText(this, "无法查看文件，请稍后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        
        backgroundTaskExecutor.submit(() -> {
            try {
                // 获取数据文件存储目录
                File storageDir = getFilesDir();
                
                if (!storageDir.exists() || !storageDir.isDirectory()) {
                    runOnUiThread(() -> Toast.makeText(this, "数据文件目录不存在", Toast.LENGTH_SHORT).show());
                    return;
                }
                
                // 获取所有数据文件
                File[] dataFiles = storageDir.listFiles((dir, name) -> 
                    name.contains("_sensor_data_") && 
                    (name.endsWith(".jsonl") || name.endsWith(".jsonl.gz")));
                
                if (dataFiles == null || dataFiles.length == 0) {
                    runOnUiThread(() -> Toast.makeText(this, "没有找到任何数据文件", Toast.LENGTH_SHORT).show());
                    return;
                }
                
                // 按修改时间排序，最新的在前面
                java.util.Arrays.sort(dataFiles, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
                
                runOnUiThread(() -> showFileSelectionDialog(dataFiles));
                
            } catch (Exception e) {
                Log.e(TAG, "获取文件列表失败", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "获取文件列表失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    showAllDataFilesList(); // 备用方案
                });
            }
        });
    }
    
    /**
     * 显示文件选择对话框
     */
    private void showFileSelectionDialog(File[] dataFiles) {
        if (dataFiles == null || dataFiles.length == 0) {
            Toast.makeText(this, "没有可查看的文件", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 创建文件名列表
        String[] fileNames = new String[dataFiles.length];
        for (int i = 0; i < dataFiles.length; i++) {
            File file = dataFiles[i];
            long sizeKB = file.length() / 1024;
            String sizeStr = sizeKB > 1024 ? String.format("%.1f MB", sizeKB / 1024.0) : sizeKB + " KB";
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            String timeStr = sdf.format(new Date(file.lastModified()));
            fileNames[i] = file.getName() + "\n" + sizeStr + " - " + timeStr;
        }
        
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("选择要查看的文件")
            .setItems(fileNames, (d, which) -> {
                dismissCurrentDialog();
                showFileContentDialog(dataFiles[which]);
            })
            .setNegativeButton("取消", (d, which) -> dismissCurrentDialog())
            .setOnCancelListener(d -> dismissCurrentDialog())
            .show();
        currentDialog = dialog;
    }
    
    /**
     * 显示文件内容对话框
     */
    private void showFileContentDialog(File file) {
        if (backgroundTaskExecutor == null || backgroundTaskExecutor.isShutdown()) {
            Toast.makeText(this, "无法读取文件，请稍后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 显示加载对话框
        AlertDialog loadingDialog = new AlertDialog.Builder(this)
            .setTitle("读取文件")
            .setMessage("正在读取文件内容，请稍候...")
            .setCancelable(false)
            .create();
        loadingDialog.show();
        currentDialog = loadingDialog;
        
        backgroundTaskExecutor.submit(() -> {
            try {
                String content = readFileContent(file);
                
                runOnUiThread(() -> {
                    if (loadingDialog.isShowing()) {
                        loadingDialog.dismiss();
                    }
                    showFileContentViewDialog(file, content);
                });
                
            } catch (Exception e) {
                Log.e(TAG, "读取文件内容失败: " + file.getName(), e);
                runOnUiThread(() -> {
                    if (loadingDialog.isShowing()) {
                        loadingDialog.dismiss();
                    }
                    Toast.makeText(this, "读取文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    
    /**
     * 显示文件内容查看对话框
     */
    private void showFileContentViewDialog(File file, String content) {
        // 创建自定义布局
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        
        // 文件信息
        TextView fileInfo = new TextView(this);
        fileInfo.setText(String.format("文件: %s\n大小: %s\n修改时间: %s", 
            file.getName(),
            formatFileSize(file.length()),
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(file.lastModified()))));
        fileInfo.setTextSize(12);
        fileInfo.setTextColor(0xFF666666);
        fileInfo.setPadding(0, 0, 0, 16);
        layout.addView(fileInfo);
        
        // 内容显示
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        TextView contentView = new TextView(this);
        contentView.setText(content);
        contentView.setTextSize(10);
        contentView.setTypeface(android.graphics.Typeface.MONOSPACE);
        contentView.setTextIsSelectable(true);
        contentView.setPadding(8, 8, 8, 8);
        contentView.setBackgroundColor(0xFFF5F5F5);
        
        scrollView.addView(contentView);
        android.widget.LinearLayout.LayoutParams scrollParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 
            600); // 固定高度
        scrollView.setLayoutParams(scrollParams);
        layout.addView(scrollView);
        
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("文件内容")
            .setView(layout)
            .setPositiveButton("关闭", (d, which) -> dismissCurrentDialog())
            .setNeutralButton("导出", (d, which) -> {
                dismissCurrentDialog();
                exportFileContent(file, content);
            })
            .setOnCancelListener(d -> dismissCurrentDialog())
            .show();
        currentDialog = dialog;
    }
    
    /**
     * 读取文件内容
     */
    private String readFileContent(File file) throws Exception {
        StringBuilder content = new StringBuilder();
        int maxLines = 1000; // 限制最大行数，避免内存问题
        int lineCount = 0;
        
        if (file.getName().endsWith(".gz")) {
            // 读取GZIP压缩文件
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
                 java.util.zip.GZIPInputStream gzis = new java.util.zip.GZIPInputStream(fis);
                 java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(gzis, "UTF-8"))) {
                
                String line;
                while ((line = reader.readLine()) != null && lineCount < maxLines) {
                    content.append(line).append("\n");
                    lineCount++;
                }
            }
        } else {
            // 读取普通文本文件
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
                 java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(fis, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null && lineCount < maxLines) {
                    content.append(line).append("\n");
                    lineCount++;
                }
            }
        }
        
        if (lineCount >= maxLines) {
            content.append("\n... (文件内容过长，仅显示前").append(maxLines).append("行)");
        }
        
        if (content.length() == 0) {
            return "文件为空或无法读取内容";
        }
        
        return content.toString();
    }
    
    /**
     * 导出文件内容
     */
    private void exportFileContent(File file, String content) {
        try {
            // 创建导出文件
            File exportDir = new File(getExternalFilesDir(null), "exports");
            if (!exportDir.exists()) {
                exportDir.mkdirs();
            }
            
            String exportFileName = "export_" + file.getName().replace(".gz", "") + "_" + 
                                   System.currentTimeMillis() + ".txt";
            File exportFile = new File(exportDir, exportFileName);
            
            // 写入内容
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(exportFile);
                 java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(fos, "UTF-8")) {
                writer.write(content);
            }
            
            Toast.makeText(this, "文件已导出到: " + exportFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            
        } catch (Exception e) {
            Log.e(TAG, "导出文件失败", e);
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 显示所有数据文件列表（保留原有功能作为备用）
     */
    private void showAllDataFilesList() {
        if (backgroundTaskExecutor == null || backgroundTaskExecutor.isShutdown()) { // 安全检查
            Log.w(TAG, "BackgroundTaskExecutor 未初始化或已关闭，无法获取文件列表。");
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "无法获取文件列表，请稍后重试", Toast.LENGTH_SHORT).show());
            return;
        }
        backgroundTaskExecutor.submit(() -> {
            List<String> fileInfoList = storageManager.getAllDataFilesInfo();
            
            runOnUiThread(() -> {
                if (fileInfoList.isEmpty()) {
                    Toast.makeText(this, "没有任何数据文件", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                StringBuilder sb = new StringBuilder();
                sb.append("共 ").append(fileInfoList.size()).append(" 个数据文件:\n\n");
                
                for (int i = 0; i < fileInfoList.size(); i++) {
                    sb.append(i + 1).append(". ").append(fileInfoList.get(i)).append("\n");
                    
                    // 限制显示数量，避免对话框过长
                    if (i >= 19 && fileInfoList.size() > 20) {
                        sb.append("...以及 ").append(fileInfoList.size() - 20).append(" 个其他文件");
                        break;
                    }
                }
                
                AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("所有数据文件")
                    .setMessage(sb.toString())
                    .setPositiveButton("确定", (d, which) -> dismissCurrentDialog())
                    .setNeutralButton("清理旧文件", (d, which) -> {
                        btnKeepRecentFiles.performClick();
                        dismissCurrentDialog();
                    })
                    .setOnCancelListener(d -> dismissCurrentDialog())
                    .show();
                currentDialog = dialog;
            });
        });
    }
    
    /**
     * 测试服务器连接
     */
    private void testServerConnection() {
        String ip = etServerIp.getText().toString().trim();
        String port = etServerPort.getText().toString().trim();
        
        if (ip.isEmpty() || port.isEmpty()) {
            Toast.makeText(this, "请输入服务器IP和端口", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 使用新的非阻塞式对话框，而不是过时的 ProgressDialog
        AlertDialog progressDialog = new AlertDialog.Builder(this)
            .setTitle("连接测试")
            .setMessage("正在测试服务器连接...")
            .setCancelable(false)
            .create();
        currentDialog = progressDialog; // 保持
        if (currentDialog != null && !currentDialog.isShowing()) { // 确保显示
             currentDialog.show();
        }
        
        // 使用 NetworkManager 的测试连接方法
        if (networkManager != null) {
            networkManager.testServerConnection(ip, port, new NetworkManager.UploadCallback() {
                @Override
                public void onSuccess(String responseBody) {
                    // 检查Activity是否存活
                    if (isDestroyed() || isFinishing()) {
                        Log.w(TAG, "TestConnection onSuccess: MainActivity is not active.");
                        // 安全关闭对话框
                        if (progressDialog != null && progressDialog.isShowing()) { 
                            progressDialog.dismiss();
                        }
                        return;
                    }
                    if (progressDialog != null && progressDialog.isShowing()) { // 安全关闭对话框
                        progressDialog.dismiss();
                    }
                    Toast.makeText(MainActivity.this, "服务器连接成功！", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onFailure(String errorMessage) {
                    // 检查Activity是否存活
                    if (isDestroyed() || isFinishing()) {
                        Log.w(TAG, "TestConnection onFailure: MainActivity is not active.");
                        if (progressDialog != null && progressDialog.isShowing()) { // 安全关闭对话框
                            progressDialog.dismiss();
                        }
                        return;
                    }
                    if (progressDialog != null && progressDialog.isShowing()) { // 安全关闭对话框
                        progressDialog.dismiss();
                    }
                    AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                        .setTitle("服务器连接失败")
                        .setMessage("无法连接到服务器: " + ip + ":" + port + "\n\n错误信息: " + errorMessage)
                        .setPositiveButton("确定", (d,which) -> dismissCurrentDialog())
                        .setOnCancelListener(d -> dismissCurrentDialog())
                        .show();
                    currentDialog = dialog;
                }

                @Override
                public void onProgress(int overallProgress) {
                    // 不需要进度回调
                }
            });
        } else {
            // 如果 NetworkManager 不可用，降级为基本连接测试
            if (backgroundTaskExecutor == null || backgroundTaskExecutor.isShutdown()) { // 安全检查
                Log.w(TAG, "BackgroundTaskExecutor 未初始化或已关闭，无法测试连接。");
                runOnUiThread(() -> {
                    if (currentDialog != null && currentDialog.isShowing()) currentDialog.dismiss(); // progressDialog 就是 currentDialog
                    Toast.makeText(MainActivity.this, "操作无法执行，请稍后重试", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            backgroundTaskExecutor.submit(() -> {
                boolean isConnected = false;
                String errorMessage = "";
                
                try {
                    java.net.Socket socket = new java.net.Socket();
                    socket.connect(new java.net.InetSocketAddress(ip, Integer.parseInt(port)), 3000);
                    isConnected = socket.isConnected();
                    socket.close();
                } catch (java.net.UnknownHostException e) {
                    errorMessage = "无法解析服务器地址: " + e.getMessage();
                } catch (java.net.ConnectException e) {
                    errorMessage = "连接被拒绝，服务器可能未启动: " + e.getMessage();
                } catch (java.net.SocketTimeoutException e) {
                    errorMessage = "连接超时: " + e.getMessage();
                } catch (java.io.IOException e) {
                    errorMessage = "连接错误: " + e.getMessage();
                } catch (Exception e) {
                    errorMessage = "未知错误: " + e.getMessage();
                }
                
                final boolean finalIsConnected = isConnected;
                final String finalErrorMessage = errorMessage;
                
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    
                    if (finalIsConnected) {
                        Toast.makeText(MainActivity.this, "服务器连接成功！", Toast.LENGTH_SHORT).show();
                    } else {
                        AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                            .setTitle("服务器连接失败")
                            .setMessage("无法连接到服务器: " + ip + ":" + port + "\n\n错误信息: " + finalErrorMessage)
                            .setPositiveButton("确定", (d,which) -> dismissCurrentDialog())
                            .setOnCancelListener(d -> dismissCurrentDialog())
                            .show();
                        currentDialog = dialog;
                    }
                });
            });
        }
    }
    
    /**
     * 检查网络是否可用
     */
    @SuppressWarnings("deprecation")
    private boolean isNetworkAvailable() {
        try {
            // 首先尝试使用 NetworkManager 的静态方法
            if (NetworkManager.isNetworkAvailable(this)) {
                return true;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "NetworkManager 检查网络状态时权限不足: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "NetworkManager 检查网络状态失败: " + e.getMessage());
        }
        
        // 作为备用方案，使用更安全的实现
        try {
            android.net.ConnectivityManager connectivityManager = (android.net.ConnectivityManager) 
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) {
                Log.w(TAG, "ConnectivityManager 不可用");
                return false;
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                try {
                    android.net.Network network = connectivityManager.getActiveNetwork();
                    if (network == null) {
                        return false;
                    }
                    
                    android.net.NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                    return capabilities != null && (
                            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET));
                } catch (SecurityException e) {
                    Log.w(TAG, "检查网络状态时权限不足，使用备用方法: " + e.getMessage());
                    // 降级到旧版本API
                    android.net.NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
                    return activeNetworkInfo != null && activeNetworkInfo.isConnected();
                }
            } else {
                // 兼容旧版本Android
                android.net.NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
                return activeNetworkInfo != null && activeNetworkInfo.isConnected();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "检查网络状态时权限不足: " + e.getMessage());
            // 如果权限不足，假设网络可用，让上传尝试进行
            return true;
        } catch (Exception e) {
            Log.e(TAG, "检查网络状态时发生异常: " + e.getMessage());
            // 发生异常时，假设网络可用
            return true;
        }
    }
    
    /**
     * 绑定传感器服务
     */
    private void bindSensorService() {
        Intent intent = new Intent(this, SensorService.class);
        
        // 首先确保服务已启动
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        
        // 然后绑定服务
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        Log.d(TAG, "绑定传感器服务");
    }
    
    /**
     * 启动数据收集
     */
    private void startDataCollection() {
        if (sensorService != null && !sensorService.isRecordingToFile()) {
            // 获取采样率
            int samplingRate = 10; // 默认值
            if (etSamplingRate != null && etSamplingRate.getSelectedItem() != null) {
                try {
                    samplingRate = Integer.parseInt(etSamplingRate.getSelectedItem().toString());
                } catch (NumberFormatException e) {
                    Log.e(TAG, "自动启动收集时，无效的采样率格式，使用默认值10ms: " + etSamplingRate.getSelectedItem().toString(), e);
                    samplingRate = 10; // Fallback to default
                }
            } else {
                Log.w(TAG, "自动启动收集时，采样率Spinner或选中项为空，使用默认值10ms");
                samplingRate = 10; // Fallback to default
            }
            
            // 保存设置
            saveSettings();
            
            // 设置采样率并启动服务
            sensorService.startCollecting(samplingRate);
            if (dataManager != null) { // 添加空检查
                dataManager.setCollecting(true);
            }
            
            // 更新UI
            updateServiceStatus();
            Log.i(TAG, "自动启动数据收集，采样率: " + samplingRate + "ms");
        }
    }
    
    /**
     * 初始化UI更新的Runnable
     */
    private void initializeUiUpdateRunnable() {
        uiUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                // 检查Activity是否还存活且可见，以及屏幕是否开启
                if (isDestroyed() || isFinishing() || !isActivityVisible || !isScreenOn) {
                    Log.d(TAG, "Activity已销毁、不可见或屏幕关闭，停止UI更新Runnable");
                    return; 
                }
                
                try {
                    // 只在屏幕开启且Activity可见时更新UI
                    updateServiceStatus();
                    updateFileInfo();
                    updateBatteryInfo();
                } catch (Exception e) {
                    Log.e(TAG, "UI更新出错", e);
                } finally {
                    // 只有在Activity仍然可见且屏幕开启时才继续调度
                    if (!isDestroyed() && !isFinishing() && isActivityVisible && isScreenOn) {
                        uiUpdateHandler.postDelayed(this, UI_UPDATE_INTERVAL_MS);
                    } else {
                        Log.d(TAG, "停止UI更新调度 - Activity状态: destroyed=" + isDestroyed() + 
                              ", finishing=" + isFinishing() + ", visible=" + isActivityVisible + 
                              ", screenOn=" + isScreenOn);
                    }
                }
            }
        };
    }
    
    /**
     * 启动定时任务，更新UI
     */
    private void startUiUpdateTimer() {
        // 停止之前的任务（如果正在运行）
        stopUiUpdateTimer();
        // 立即执行一次，然后开始定时
        if (uiUpdateRunnable != null) {
             uiUpdateHandler.post(uiUpdateRunnable);
        }
        Log.d(TAG, "UI更新任务已启动");
    }
    
    /**
     * 停止UI更新定时器
     */
    private void stopUiUpdateTimer() {
        if (uiUpdateRunnable != null) {
            uiUpdateHandler.removeCallbacks(uiUpdateRunnable);
        }
        Log.d(TAG, "UI更新任务已停止");
    }
    
    /**
     * 更新服务状态UI
     */
    private void updateServiceStatus() {
        boolean isRecording = isBound && sensorService != null && sensorService.isRecordingToFile();
        boolean isMonitoringOnly = isBound && sensorService != null && 
                                   sensorCollector != null && sensorCollector.isListening() && 
                                   dataManager != null && !dataManager.isCollecting();
        
        // 更新状态指示器
        statusIndicator.setActivated(isRecording);
        
        // 更新状态文本
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String currentTime = sdf.format(new Date());
        
        if (isRecording) {
            tvStatus.setText("状态: 正在运行 (数据写入中) (" + currentTime + ")");
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
        } else if (isMonitoringOnly) {
            tvStatus.setText("状态: 正在监控 (未写入文件) (" + currentTime + ")");
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
        } else {
            tvStatus.setText("状态: 未运行 (" + currentTime + ")");
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
        }
        
        // 更新前台应用信息
        updateForegroundAppInfo();
        
        // 检查使用统计权限
        if (foregroundAppManager != null && btnPermission != null) {
            try {
                boolean hasPermission = foregroundAppManager.hasUsageStatsPermission();
                btnPermission.setVisibility(hasPermission ? View.GONE : View.VISIBLE);
            } catch (Exception e) {
                Log.e(TAG, "Error updating permission button visibility", e);
                if (btnPermission != null) { 
                    btnPermission.setVisibility(View.VISIBLE); // Default to visible if error
                }
            }
        }
    }
    
    /**
     * 更新前台应用信息
     */
    private void updateForegroundAppInfo() {
        if (foregroundAppManager == null) {
            Log.w(TAG, "ForegroundAppManager为空，跳过更新");
            return;
        }
        
        if (backgroundTaskExecutor == null || backgroundTaskExecutor.isShutdown()) {
            Log.w(TAG, "BackgroundTaskExecutor不可用，跳过前台应用信息更新");
            return;
        }
        
        // 如果Activity不可见，跳过更新以避免后台帧事件
        if (!isActivityVisible) {
            Log.v(TAG, "Activity不可见，跳过前台应用信息更新");
            return;
        }
        
        try {
            // 使用backgroundTaskExecutor避免内存泄漏
            backgroundTaskExecutor.submit(() -> {
                try {
                    Log.d(TAG, "开始获取前台应用信息");
                    
                    // 获取前台应用信息
                    ForegroundAppManager.ForegroundAppInfo appInfo = foregroundAppManager.getForegroundAppInfo();
                    Log.d(TAG, "获取前台应用信息成功: " + appInfo.toString());
                    
                    // 获取最近应用列表（固定10个）
                    List<ForegroundAppManager.RecentAppInfo> recentApps = foregroundAppManager.getRecentApps(10);
                    Log.d(TAG, "获取最近应用列表成功，数量: " + recentApps.size());
                    
                    // 在主线程更新UI
                    runOnUiThread(() -> {
                        try {
                            // 检查Activity是否还存活
                            if (isDestroyed() || isFinishing()) {
                                Log.d(TAG, "Activity已销毁，跳过UI更新");
                                return;
                            }
                            
                            // 更新前台应用显示
                            if (tvForegroundApp != null) {
                                String appText = "前台应用: " + appInfo.toString();
                                tvForegroundApp.setText(appText);
                            }
                            
                            // 更新最近应用列表
                            updateRecentAppsList(recentApps);
                            
                            // 更新权限按钮显示
                            if (btnPermission != null && foregroundAppManager != null) {
                                boolean hasPermission = foregroundAppManager.hasUsageStatsPermission();
                                btnPermission.setVisibility(hasPermission ? View.GONE : View.VISIBLE);
                            }
                            
                            Log.d(TAG, "前台应用UI更新完成");
                            
                        } catch (Exception e) {
                            Log.e(TAG, "在主线程更新前台应用UI时出错", e);
                        }
                    });
                    
                } catch (Exception e) {
                    Log.e(TAG, "获取前台应用信息时出错", e);
                    
                    // 在主线程显示错误信息
                    runOnUiThread(() -> {
                        if (isDestroyed() || isFinishing()) {
                            return;
                        }
                        if (tvForegroundApp != null) {
                            tvForegroundApp.setText("前台应用: 获取失败 - " + e.getMessage());
                        }
                        updateRecentAppsList(new ArrayList<>()); // 显示空列表
                    });
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "启动前台应用信息更新任务失败", e);
        }
    }
    
    /**
     * 更新最近应用列表
     */
    private void updateRecentAppsList(List<ForegroundAppManager.RecentAppInfo> recentApps) {
        try {
            if (recyclerRecentApps != null && recentAppsAdapter != null) {
                // 确保数据不为空
                if (recentApps == null) {
                    recentApps = new ArrayList<>();
                }
                
                // 限制列表大小，避免UI过度拥挤
                if (recentApps.size() > 10) {
                    recentApps = new ArrayList<>(recentApps.subList(0, 10));
                }
                
                // 更新适配器数据
                recentAppsAdapter.updateData(recentApps);
                
                // 滚动到顶部
                if (recentApps.size() > 0) {
                    recyclerRecentApps.scrollToPosition(0);
                }
                
                Log.d(TAG, "更新最近应用列表，包含 " + recentApps.size() + " 个应用");
            }
        } catch (Exception e) {
            Log.e(TAG, "更新最近应用列表时出错", e);
        }
    }
    
    /**
     * 更新文件信息UI
     */
    private void updateFileInfo() {
        if (storageManager != null) {
            tvFilePath.setText(storageManager.getDataFilePath());
            int fileCount = storageManager.getUnuploadedFilesCount();
            long totalSizeKB = storageManager.getUnuploadedFilesTotalSizeKB();
            tvPendingFiles.setText(fileCount + "个文件 (" + totalSizeKB + " KB)");
            tvLastUploadTime.setText(storageManager.getLastUploadTimeString());
            if (tvStorageSize != null) {
                tvStorageSize.setText(totalSizeKB + " KB");
            }
            if (tvCurrentFile != null) {
                tvCurrentFile.setText(storageManager.getCurrentFileInfo());
            }
        }
    }
    
    /**
     * 更新电池信息UI
     */
    private void updateBatteryInfo() {
        if (batteryStatsManager != null) {
            String batteryInfo = batteryStatsManager.getBatteryStatsInfo();
            tvBatteryStats.setText(batteryInfo);
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "权限未授权: " + permission);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                Toast.makeText(this, "权限已授权", Toast.LENGTH_SHORT).show();
                // 权限已授权，绑定服务
                bindSensorService();
            } else {
                Toast.makeText(this, "需要权限才能正常运行", Toast.LENGTH_LONG).show();
                finish(); // 如果权限是必要的，则关闭应用
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isScreenOn = true;
        isActivityVisible = true;
        
        // 恢复RecyclerView的正常状态
        if (recyclerRecentApps != null && recentAppsAdapter != null) {
            recentAppsAdapter.setPaused(false);
        }
        
        if (!isBound) {
            bindSensorService();
        } else {
            // 恢复监控状态
            if (sensorService != null && !sensorService.isRecordingToFile() && 
                sensorCollector != null && !sensorCollector.isListening()) {
                startSensorMonitoringOnly();
            }
        }
        // 确保在UI可见时启动/恢复UI更新
        startUiUpdateTimer();
        
        // 立即更新一次UI，避免延迟
        updateServiceStatus();
        updateFileInfo();
        updateBatteryInfo();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityVisible = false;
        
        // 停止UI更新以节省资源
        stopUiUpdateTimer();
        
        // 停止RecyclerView的所有动画和滚动，避免后台帧事件错误
        if (recyclerRecentApps != null) {
            recyclerRecentApps.clearAnimation();
            recyclerRecentApps.stopScroll();
            // 暂停适配器的数据更新
            if (recentAppsAdapter != null) {
                recentAppsAdapter.setPaused(true);
            }
        }
        
        // 关闭可能存在的对话框
        dismissCurrentDialog();
        try {
            saveSettings();
        } catch (Exception e) {
            Log.e(TAG, "保存设置失败", e);
        }
    }
    
    @Override
    protected void onStop() {
        super.onStop();
        isScreenOn = false;
        try {
            saveSettings();
        } catch (Exception e) {
            Log.e(TAG, "保存设置失败", e);
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "开始销毁MainActivity");
        
        super.onDestroy();
        
        // 停止UI更新任务并移除回调
        stopUiUpdateTimer();
        
        // 关闭后台任务执行器
        if (backgroundTaskExecutor != null && !backgroundTaskExecutor.isShutdown()) {
            try {
                backgroundTaskExecutor.shutdown();
                if (!backgroundTaskExecutor.awaitTermination(2, TimeUnit.SECONDS)) { //确保 TimeUnit 被导入
                    backgroundTaskExecutor.shutdownNow();
                    Log.w(TAG, "后台任务执行器被强制关闭");
                }
            } catch (InterruptedException e) {
                if (backgroundTaskExecutor != null) { // Null check before calling on potentially nulled reference
                    backgroundTaskExecutor.shutdownNow();
                }
                Thread.currentThread().interrupt();
                Log.w(TAG, "关闭后台任务执行器时被中断", e);
            } catch (Exception e) {
                Log.e(TAG, "关闭后台任务执行器失败", e);
            }
        }
        
        // 关闭任何可能残留的对话框
        dismissCurrentDialog();
        
        // 取消注册屏幕状态监听器
        unregisterScreenStateReceiver();
        
        // 取消注册内存回调
        try {
            getApplicationContext().unregisterComponentCallbacks(this);
            Log.d(TAG, "已取消注册内存回调");
        } catch (Exception e) {
            Log.w(TAG, "取消注册内存回调失败", e);
        }
        
        // 解绑服务
        if (isBound) {
            try {
                unbindService(serviceConnection);
                isBound = false;
                Log.d(TAG, "传感器服务已解绑");
            } catch (Exception e) {
                Log.e(TAG, "解绑传感器服务失败", e);
            }
        }
        
        // 释放管理器资源 - 注意：不要关闭DataManager，因为服务可能还在使用
        if (dataManager != null) {
            try {
                dataManager.removeListener(this);
                // 不调用 dataManager.shutdown()，因为服务可能还在使用
                Log.d(TAG, "已从数据管理器移除监听器");
            } catch (Exception e) {
                Log.e(TAG, "从数据管理器移除监听器失败", e);
            }
        }
        
        if (storageManager != null && dataManager != null) { // 确保 dataManager 不为 null
            try {
                // 移除监听器但不关闭StorageManager，因为服务可能还在使用
                dataManager.removeListener(storageManager);
                Log.d(TAG, "已从存储管理器移除监听器 (DataManager->StorageManager)");
            } catch (Exception e) {
                Log.e(TAG, "从数据管理器移除存储管理器监听器失败", e);
            }
        }
        
        if (networkManager != null) {
            try {
                networkManager.shutdown();
                Log.d(TAG, "网络管理器已关闭");
            } catch (Exception e) {
                Log.e(TAG, "关闭网络管理器失败", e);
            }
        }
        
        if (batteryStatsManager != null) {
            try {
                batteryStatsManager.shutdown();
                Log.d(TAG, "电池统计管理器已关闭");
            } catch (Exception e) {
                Log.e(TAG, "关闭电池统计管理器失败", e);
            }
        }
        
        // 不要释放sensorCollector，因为服务可能还在使用
        // sensorCollector由服务管理，不应在Activity中释放
        Log.d(TAG, "传感器收集器由服务管理，Activity不释放");
        
        if (foregroundAppManager != null) {
            try {
                foregroundAppManager.shutdown();
                Log.d(TAG, "前台应用管理器已关闭");
            } catch (Exception e) {
                Log.e(TAG, "关闭前台应用管理器失败", e);
            }
        }
        
        Log.d(TAG, "MainActivity已完全销毁");
    }

    /**
     * 此方法已被删除，不再处理触摸事件
     */

    @Override
    public void onNewDataRecord(final DataRecord dataRecord) {
        // 只在屏幕开启且Activity可见时更新UI显示
        if (!isActivityVisible || !isScreenOn || dataRecord == null) return;
        // 使用uiUpdateHandler替代旧的uiHandler，确保一致性
        uiUpdateHandler.post(() -> {
            String commonSuffix = " Timestamp: " + dataRecord.timestampMs;
            if ("sensor".equals(dataRecord.type)) {
                String sensorDataStr = String.format(Locale.getDefault(),
                    "X: %.2f, Y: %.2f, Z: %.2f\n%s",
                    dataRecord.sensorX, dataRecord.sensorY, dataRecord.sensorZ, commonSuffix);
                switch (dataRecord.sensorName) {
                    case "accelerometer":
                        if (tvAccelerometerData != null) tvAccelerometerData.setText("加速度: " + sensorDataStr);
                        break;
                    case "magnetometer":
                        if (tvMagnetometerData != null) tvMagnetometerData.setText("磁力: " + sensorDataStr);
                        break;
                    case "gyroscope":
                        if (tvGyroscopeData != null) tvGyroscopeData.setText("陀螺仪: " + sensorDataStr);
                        break;
                }
            }
        });
    }
    
    /**
     * 启动传感器监听但不记录数据
     */
    private void startSensorMonitoringOnly() {
        if (sensorService != null) {
            try {
                // 获取采样率
                int samplingRate = 10; // 默认值
                if (etSamplingRate != null && etSamplingRate.getSelectedItem() != null) {
                    try {
                        samplingRate = Integer.parseInt(etSamplingRate.getSelectedItem().toString());
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "启动监控时，无效的采样率格式，使用默认值10ms: " + etSamplingRate.getSelectedItem().toString(), e);
                        samplingRate = 10; // Fallback to default
                    }
                } else {
                    Log.w(TAG, "启动监控时，采样率Spinner或选中项为空，使用默认值10ms");
                    samplingRate = 10; // Fallback to default
                }
                
                // 启动传感器监听，但不设置DataManager为收集状态
                sensorService.startSensorMonitoring(samplingRate);
                
                // 更新UI为状态
                updateServiceStatus();
                
                Log.i(TAG, "传感器监听已启动，采样率: " + samplingRate + "ms (仅监控不记录)");
            } catch (Exception e) {
                Log.e(TAG, "启动传感器监听失败", e);
            }
        }
    }

    /**
     * 最近应用适配器
     */
    private class RecentAppsAdapter extends RecyclerView.Adapter<RecentAppsAdapter.ViewHolder> {
        private List<ForegroundAppManager.RecentAppInfo> recentApps;
        private volatile boolean isPaused = false;
        
        public RecentAppsAdapter(List<ForegroundAppManager.RecentAppInfo> recentApps) {
            this.recentApps = recentApps != null ? recentApps : new ArrayList<>();
        }
        
        public void setPaused(boolean paused) {
            this.isPaused = paused;
            if (paused) {
                Log.d(TAG, "RecentAppsAdapter已暂停更新");
            } else {
                Log.d(TAG, "RecentAppsAdapter已恢复更新");
            }
        }
        
        public void updateData(List<ForegroundAppManager.RecentAppInfo> newData) {
            try {
                // 如果适配器已暂停，跳过更新以避免后台帧事件
                if (isPaused) {
                    Log.v(TAG, "RecentAppsAdapter已暂停，跳过数据更新");
                    return;
                }
                
                // 创建新列表，避免引用旧数据
                this.recentApps = new ArrayList<>(newData != null ? newData : new ArrayList<>());
                notifyDataSetChanged();
                Log.d(TAG, "RecentAppsAdapter数据已更新，包含 " + this.recentApps.size() + " 个应用");
            } catch (Exception e) {
                Log.e(TAG, "更新RecentAppsAdapter数据时出错", e);
                this.recentApps = new ArrayList<>(); // 确保有一个有效的空列表
                if (!isPaused) {
                    notifyDataSetChanged();
                }
            }
        }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            try {
                View view = LayoutInflater.from(parent.getContext())
                        .inflate(android.R.layout.simple_list_item_2, parent, false);
                return new ViewHolder(view);
            } catch (Exception e) {
                Log.e(TAG, "创建ViewHolder时出错", e);
                // 创建一个简单的备用View
                TextView fallbackView = new TextView(parent.getContext());
                fallbackView.setText("加载失败");
                fallbackView.setPadding(16, 8, 16, 8);
                return new ViewHolder(fallbackView);
            }
        }
        
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            try {
                if (recentApps.isEmpty()) {
                    // 显示空状态
                    if (holder.text1 != null) {
                        holder.text1.setText("暂无最近使用的应用");
                        holder.text1.setTextColor(0xFF666666); // 灰色
                    }
                    if (holder.text2 != null) {
                        holder.text2.setText("请授予应用使用统计权限");
                        holder.text2.setTextColor(0xFF999999); // 浅灰色
                    }
                    return;
                }
                
                if (position < 0 || position >= recentApps.size()) {
                    Log.w(TAG, "无效的position: " + position + ", 数据大小: " + recentApps.size());
                    return;
                }
                
                ForegroundAppManager.RecentAppInfo appInfo = recentApps.get(position);
                if (appInfo != null) {
                    if (holder.text1 != null) {
                        holder.text1.setText(appInfo.getAppName());
                        holder.text1.setTextColor(0xFF333333); // 深灰色
                    }
                    if (holder.text2 != null) {
                        holder.text2.setText(appInfo.toString());
                        holder.text2.setTextColor(0xFF666666); // 中灰色
                    }
                } else {
                    if (holder.text1 != null) {
                        holder.text1.setText("应用信息获取失败");
                        holder.text1.setTextColor(0xFF999999);
                    }
                    if (holder.text2 != null) {
                        holder.text2.setText("");
                    }
                }
                
                // 清除View的tag，避免内存泄漏
                holder.itemView.setTag(null);
                
            } catch (Exception e) {
                Log.e(TAG, "绑定ViewHolder数据时出错, position=" + position, e);
                // 设置错误状态
                if (holder.text1 != null) {
                    holder.text1.setText("加载失败");
                    holder.text1.setTextColor(0xFFFF0000); // 红色
                }
                if (holder.text2 != null) {
                    holder.text2.setText("");
                }
            }
        }
        
        @Override
        public int getItemCount() {
            try {
                // 如果没有数据，返回1来显示空状态；否则返回实际数据数量
                return recentApps.isEmpty() ? 1 : recentApps.size();
            } catch (Exception e) {
                Log.e(TAG, "获取item数量时出错", e);
                return 1; // 返回1显示错误信息
            }
        }
        
        // 实现getItemId和setHasStableIds可以改善RecyclerView的性能
        @Override
        public long getItemId(int position) {
            try {
                if (recentApps.isEmpty() || position < 0 || position >= recentApps.size()) {
                    return -1L; // 空状态或无效位置的ID，使用长整型
                }
                ForegroundAppManager.RecentAppInfo appInfo = recentApps.get(position);
                if (appInfo != null) {
                    String packageName = appInfo.getPackageName();
                    if (packageName != null) {
                        return packageName.hashCode();
                    }
                }
                return -1L; // 如果appInfo或packageName为null，返回默认ID
            } catch (Exception e) {
                Log.w(TAG, "获取item ID时出错, position: " + position, e);
                return position; // 备用ID，或考虑返回 -1L
            }
        }
        
        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            super.onViewRecycled(holder);
            try {
                // 清除引用，帮助GC
                if (holder.text1 != null) {
                    holder.text1.setText(null);
                }
                if (holder.text2 != null) {
                    holder.text2.setText(null);
                }
                holder.itemView.setTag(null);
            } catch (Exception e) {
                Log.w(TAG, "回收ViewHolder时出错", e);
            }
        }
        
        // 当适配器中的数据变化时，新添加此方法清理旧数据
        public void clearData() {
            try {
                if (recentApps != null) {
                    recentApps.clear();
                    recentApps = new ArrayList<>(0); // 重新分配最小容量的列表
                    notifyDataSetChanged();
                    Log.d(TAG, "RecentAppsAdapter数据已清空");
                }
            } catch (Exception e) {
                Log.e(TAG, "清空RecentAppsAdapter数据时出错", e);
            }
        }
        
        @Override
        public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
            super.onDetachedFromRecyclerView(recyclerView);
            try {
                // 当从RecyclerView中移除时，清理所有数据
                clearData();
                Log.d(TAG, "RecentAppsAdapter已从RecyclerView分离");
            } catch (Exception e) {
                Log.e(TAG, "RecentAppsAdapter分离时出错", e);
            }
        }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView text1, text2;
            
            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                try {
                    if (itemView instanceof TextView) {
                        // 处理fallback view的情况
                        text1 = (TextView) itemView;
                        text2 = null;
                    } else {
                        text1 = itemView.findViewById(android.R.id.text1);
                        text2 = itemView.findViewById(android.R.id.text2);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "初始化ViewHolder时出错", e);
                    text1 = null;
                    text2 = null;
                }
            }
        }
    }

    /**
     * 实现ComponentCallbacks2接口方法
     * 这个方法会在系统内存不足时被调用
     * @param level 内存紧张等级
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level); // 调用父类方法
        Log.d(TAG, "onTrimMemory called with level: " + level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            Log.i(TAG, "内存严重不足，MainActivity执行紧急资源释放");
            if (recyclerRecentApps != null) {
                // 释放Adapter持有的数据和View
                if (recentAppsAdapter != null) {
                    recentAppsAdapter.clearData(); // 清空数据
                }
                recyclerRecentApps.setAdapter(null); // 先置空Adapter以释放View
                // 可以考虑延迟重新设置Adapter，或者在onResume中处理
                // 如果需要立即恢复，确保recentAppsAdapter是新的空实例
                if (recentAppsAdapter != null && isActivityVisible) { // 仅在Activity可见时尝试恢复
                     new Handler(Looper.getMainLooper()).post(() -> { // 确保在主线程
                         if (recyclerRecentApps != null) { // 再次检查，因为可能是异步
                             recentAppsAdapter = new RecentAppsAdapter(new ArrayList<>());
                             recyclerRecentApps.setAdapter(recentAppsAdapter);
                             Log.d(TAG, "onTrimMemory: RecyclerView Adapter re-set after clearing.");
                         }
                     });
                }
            }
            // 也可以考虑在这里调用 StorageManager 和 DataManager 的内存清理方法
            if (storageManager != null) storageManager.onTrimMemory(level);
            if (dataManager != null) dataManager.onTrimMemory(level);

        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            Log.i(TAG, "内存中等紧张，MainActivity释放部分资源");
            if (recyclerRecentApps != null) {
                recyclerRecentApps.getRecycledViewPool().clear(); // 清理View缓存池
            }
            if (storageManager != null) storageManager.onTrimMemory(level);
            if (dataManager != null) dataManager.onTrimMemory(level);
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            Log.i(TAG, "应用在后台，释放可释放的UI资源");
            if (recyclerRecentApps != null && recentAppsAdapter != null) {
                recentAppsAdapter.clearData(); // 清空数据，但不移除Adapter
            }
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.i(TAG, "系统内存极度不足，MainActivity执行全面资源释放 (onLowMemory)");
        // 尝试释放所有非关键资源
        if (recyclerRecentApps != null) {
            if (recentAppsAdapter != null) {
                recentAppsAdapter.clearData();
            }
            recyclerRecentApps.setAdapter(null);
            // 延迟重新设置新的空Adapter
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (recyclerRecentApps != null && isActivityVisible) { // 仅在Activity可见时尝试恢复
                    recentAppsAdapter = new RecentAppsAdapter(new ArrayList<>());
                    recyclerRecentApps.setAdapter(recentAppsAdapter);
                    Log.d(TAG, "onLowMemory: RecyclerView Adapter re-set after clearing.");
                }
            }, 100); // 延迟一点时间，给系统喘息机会
        }
        
        // 通知其他组件释放内存
        if (dataManager != null) dataManager.onLowMemory();
        if (storageManager != null) storageManager.onLowMemory(); // 假设StorageManager有此方法
        if (networkManager != null) networkManager.onLowMemory(); // 假设NetworkManager有此方法
        
        // 其他如图片缓存等也应在此清理
        // Glide.get(this).clearMemory(); // 如果使用Glide
    }
    
    /**
     * 查找采样率在数组中的索引位置
     */
    private int findSamplingRateIndex(int samplingRate) {
        String[] samplingRates = getResources().getStringArray(R.array.sampling_rates);
        for (int i = 0; i < samplingRates.length; i++) {
            try {
                 if (Integer.parseInt(samplingRates[i]) == samplingRate) {
                    return i;
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "解析采样率数组中的值失败: '" + samplingRates[i] + "'", e);
                // 继续尝试下一个，或者如果这是预期的格式，可能需要抛出或处理错误
            }
        }
        // 默认返回第一个位置(通常是10ms，取决于sampling_rates数组)
        Log.w(TAG, "未找到匹配的采样率索引: " + samplingRate + "，返回默认索引0");
        return 0;
    }
    
    /**
     * 安全地关闭当前显示的对话框
     */
    private void dismissCurrentDialog() {
        if (currentDialog != null && currentDialog.isShowing()) {
            try {
                currentDialog.dismiss();
            } catch (Exception e) {
                Log.w(TAG, "关闭对话框时出错", e);
            }
        }
        currentDialog = null;
    }
    
    /**
     * 注册屏幕状态监听器
     */
    private void registerScreenStateReceiver() {
        if (screenStateReceiver == null) {
            screenStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        isScreenOn = false;
                        Log.d(TAG, "屏幕关闭，停止UI更新");
                        // 停止UI更新以避免FrameEvents错误
                        stopUiUpdateTimer();
                        // 暂停RecyclerView适配器
                        if (recentAppsAdapter != null) {
                            recentAppsAdapter.setPaused(true);
                        }
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        isScreenOn = true;
                        Log.d(TAG, "屏幕开启，恢复UI更新");
                        // 延迟恢复UI更新，避免与系统状态切换冲突
                        uiUpdateHandler.postDelayed(() -> {
                            if (isActivityVisible && isScreenOn) {
                                startUiUpdateTimer();
                            }
                            // 恢复RecyclerView适配器
                            if (recentAppsAdapter != null) {
                                recentAppsAdapter.setPaused(false);
                            }
                        }, 1000); // 延迟1秒恢复
                    }
                }
            };
            
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenStateReceiver, filter);
            Log.d(TAG, "已注册屏幕状态监听器");
        }
    }
    
    /**
     * 取消注册屏幕状态监听器
     */
    private void unregisterScreenStateReceiver() {
        if (screenStateReceiver != null) {
            try {
                unregisterReceiver(screenStateReceiver);
                screenStateReceiver = null;
                Log.d(TAG, "已取消注册屏幕状态监听器");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "屏幕状态监听器未注册或已取消注册", e);
            } catch (Exception e) {
                Log.e(TAG, "取消注册屏幕状态监听器失败", e);
            }
        }
    }
}

