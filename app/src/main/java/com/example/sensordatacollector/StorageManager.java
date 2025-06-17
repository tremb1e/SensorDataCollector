package com.example.sensordatacollector;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

public class StorageManager implements DataManager.DataRecordListener, ComponentCallbacks2 {
    private static final String TAG = "StorageManager";
    private static final String FILENAME_PREFIX = "sensor_data_"; // 保持原有前缀，稍后动态添加用户ID
    private static final String FILENAME_EXTENSION = ".jsonl";
    private static final String FILENAME_EXTENSION_COMPRESSED = ".jsonl.gz";
    private static final String PREFS_NAME = "storage_manager_prefs";
    private static final String PREF_LAST_UPLOAD_TIME = "last_upload_time";
    
    // 文件切分相关常量 - 修改为1GB
    private static final long DEFAULT_MAX_FILE_SIZE_BYTES = 1024L * 1024 * 1024; // 1GB
    private static final long DEFAULT_FILE_ROTATION_INTERVAL_MS = 60 * 60 * 1000; // 默认每小时切换一次文件
    
    // 文件写入缓冲区大小
    private static final int BUFFER_SIZE = 8 * 1024; // 8KB的缓冲区大小
    
    private final File storageDir;
    private final Context context;
    private File currentDataFile;
    private boolean useCompression = true; // 默认使用压缩
    private long maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE_BYTES;
    private long fileRotationIntervalMs = DEFAULT_FILE_ROTATION_INTERVAL_MS;
    
    // 使用原子类型确保线程安全
    private final AtomicLong bytesWrittenCurrentFile = new AtomicLong(0);
    
    private ExecutorService fileWriterExecutor; // 文件写入线程 - 移除final，允许重建
    private ScheduledExecutorService scheduledExecutor; // 定时切换文件线程 - 移除final，允许重建
    
    // 添加线程池状态监控
    private final AtomicBoolean isFileWriterRunning = new AtomicBoolean(true);
    
    private final List<File> completedFiles = new ArrayList<>(); // 已完成的文件列表，用于增量上传
    private long lastUploadTimestamp = 0; // 上次上传时间戳
    private final SharedPreferences prefs;
    private DataManager dataManagerInstance;
    
    // 添加持久的输出流，用于GZIP压缩
    private FileOutputStream currentFileOutputStream;
    private GZIPOutputStream currentGzipOutputStream;
    private BufferedWriter currentBufferedWriter; // 引用 DataManager

    public StorageManager(Context context) {
        this.context = context;
        
        // 使用内部存储，简单且不需要特殊权限
        storageDir = context.getFilesDir();
        
        // 初始化SharedPreferences
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        lastUploadTimestamp = prefs.getLong(PREF_LAST_UPLOAD_TIME, 0);
        
        // 获取DataManager实例
        this.dataManagerInstance = DataManager.getInstance();
        
        // 初始化线程池
        createFileWriterExecutor();
        createScheduledExecutor();
        
        // 首先加载所有现有文件，这会设置currentDataFile为最新的未完成文件（如果有的话）
        loadExistingFiles();
        
        // 如果没有找到未完成的文件，则创建新文件
        if (currentDataFile == null) {
            createNewDataFile();
        }
        
        // 启动定时文件切换任务
        startFileRotationTask();
        
        // 注册内存回调
        context.registerComponentCallbacks(this);
        
        Log.i(TAG, "StorageManager初始化完成，存储目录: " + storageDir.getAbsolutePath());
    }
    
    /**
     * 创建文件写入线程池
     */
    private synchronized void createFileWriterExecutor() {
        // 如果已存在执行器，先关闭
        if (fileWriterExecutor != null && !fileWriterExecutor.isShutdown()) {
            try {
                fileWriterExecutor.shutdown();
                if (!fileWriterExecutor.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                    fileWriterExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                fileWriterExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // 使用单线程执行器，避免创建过多线程
        fileWriterExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "StorageManager-FileWriter");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.setDaemon(true); // 设置为守护线程
            return thread;
        });
        
        isFileWriterRunning.set(true);
        Log.d(TAG, "创建了新的文件写入执行器(单线程)");
    }
    
    /**
     * 创建定时任务线程池
     */
    private synchronized void createScheduledExecutor() {
        // 如果已存在执行器，先关闭
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            try {
                scheduledExecutor.shutdown();
                if (!scheduledExecutor.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                    scheduledExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduledExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // 使用单线程定时执行器
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "StorageManager-Scheduler");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.setDaemon(true); // 设置为守护线程
            return thread;
        });
        
        Log.d(TAG, "创建了新的定时任务执行器(单线程)");
    }
    
    /**
     * 加载目录中所有现有的数据文件
     * 查找最新的未达到1GB的文件作为当前文件
     */
    private void loadExistingFiles() {
        File[] files = storageDir.listFiles((dir, name) -> {
            // 匹配新的命名格式：用户ID_sensor_data_时间戳.jsonl[.gz]
            boolean matchesNewFormat = name.contains("_sensor_data_") && 
                                     (name.endsWith(FILENAME_EXTENSION) || name.endsWith(FILENAME_EXTENSION_COMPRESSED));
            // 兼容旧的命名格式：sensor_data_时间戳.jsonl[.gz]
            boolean matchesOldFormat = name.startsWith(FILENAME_PREFIX) && 
                                     (name.endsWith(FILENAME_EXTENSION) || name.endsWith(FILENAME_EXTENSION_COMPRESSED));
            
            return (matchesNewFormat || matchesOldFormat);
        });
        
        if (files != null && files.length > 0) {
            // 按最后修改时间排序，最新的在最前面
            java.util.Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
            
            File latestIncompleteFile = null;
            
            synchronized (completedFiles) {
                for (File file : files) {
                    if (file.length() == 0) {
                        // 删除空文件
                        if (file.delete()) {
                            Log.d(TAG, "已删除空文件: " + file.getName());
                        }
                        continue;
                    }
                    
                    // 检查文件大小
                    if (file.length() < maxFileSizeBytes) {
                        // 找到第一个（最新的）未完成文件
                        if (latestIncompleteFile == null) {
                            latestIncompleteFile = file;
                            Log.i(TAG, "找到未完成的文件: " + file.getName() + 
                                  ", 大小: " + (file.length() / 1024 / 1024) + " MB，将继续写入");
                        } else {
                            // 其他未完成文件也加入已完成列表（避免混乱）
                            completedFiles.add(file);
                            Log.i(TAG, "已加载其他未完成文件: " + file.getName() + 
                                  ", 大小: " + (file.length() / 1024 / 1024) + " MB");
                        }
                    } else {
                        // 已达到1GB的文件
                        completedFiles.add(file);
                        Log.i(TAG, "已加载完成文件: " + file.getName() + 
                              ", 大小: " + (file.length() / 1024 / 1024) + " MB");
                    }
                }
                
                Log.i(TAG, "共加载 " + completedFiles.size() + " 个已完成文件");
            }
            
            // 设置当前文件
            if (latestIncompleteFile != null) {
                currentDataFile = latestIncompleteFile;
                bytesWrittenCurrentFile.set(latestIncompleteFile.length());
                Log.i(TAG, "设置当前文件: " + currentDataFile.getName() + 
                      ", 当前大小: " + (currentDataFile.length() / 1024 / 1024) + " MB");
            }
        }
    }
    
    /**
     * 创建新的数据文件或重用现有文件
     * 如果上一个文件小于1GB，则继续写入该文件
     */
    private synchronized void createNewDataFile() {
        // 先关闭当前的输出流
        closeCurrentStreams();
        
        // 检查是否需要重用现有的文件
        if (currentDataFile != null && currentDataFile.exists()) {
            long currentFileSize = currentDataFile.length();
            
            // 如果当前文件小于1GB，继续使用它
            if (currentFileSize < maxFileSizeBytes) {
                Log.i(TAG, "当前文件 " + currentDataFile.getName() + " 大小为 " + 
                      (currentFileSize / 1024 / 1024) + " MB，继续写入");
                // 更新字节计数器为当前文件大小
                bytesWrittenCurrentFile.set(currentFileSize);
                // 重新打开输出流
                openCurrentStreams();
                return;
            }
            
            // 文件已达到1GB，将其添加到完成列表
            if (currentFileSize > 0) {
                synchronized (completedFiles) {
                    completedFiles.add(currentDataFile);
                    Log.i(TAG, "文件 " + currentDataFile.getName() + " 已达到1GB，大小: " 
                          + (currentFileSize / 1024 / 1024) + " MB");
                }
            } else {
                // 如果文件为空，则删除
                boolean deleted = currentDataFile.delete();
                if (!deleted) {
                    Log.w(TAG, "无法删除空文件: " + currentDataFile.getAbsolutePath());
                }
            }
        }
        
        // 如果之前的步骤没有返回，说明需要创建新文件
        String userId = dataManagerInstance.getCurrentUserId();
        if (userId == null || userId.trim().isEmpty()) {
            Log.w(TAG, "用户ID为空或无效，将使用默认文件名");
            userId = "default_user"; // 或者其他处理方式
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(new Date());
        String filenameBase = userId + "_" + FILENAME_PREFIX + timestamp;
        String fileName = filenameBase + (useCompression ? FILENAME_EXTENSION_COMPRESSED : FILENAME_EXTENSION);
        
        currentDataFile = new File(storageDir, fileName);
        bytesWrittenCurrentFile.set(0); // 重置写入字节计数
        
        // 打开新文件的输出流
        openCurrentStreams();
        
        Log.i(TAG, "创建新数据文件: " + currentDataFile.getAbsolutePath());
    }
    
    /**
     * 打开当前文件的输出流
     */
    private synchronized void openCurrentStreams() {
        if (currentDataFile == null) {
            return;
        }
        
        try {
            // 关闭之前的流（如果存在）
            closeCurrentStreams();
            
            if (useCompression) {
                // 对于压缩文件，检查是否是新文件
                boolean isNewFile = !currentDataFile.exists() || currentDataFile.length() == 0;
                
                if (isNewFile) {
                    // 新文件，创建新的GZIP流
                    currentFileOutputStream = new FileOutputStream(currentDataFile, false);
                    currentGzipOutputStream = new GZIPOutputStream(currentFileOutputStream, BUFFER_SIZE);
                    Log.d(TAG, "为新GZIP文件创建输出流: " + currentDataFile.getName());
                } else {
                    // 现有GZIP文件，不能追加，需要重新创建
                    Log.w(TAG, "检测到现有GZIP文件，无法追加，将重命名并创建新文件");
                    
                    // 重命名现有文件
                    String oldFileName = currentDataFile.getName() + ".old_" + System.currentTimeMillis();
                    File oldFile = new File(currentDataFile.getParent(), oldFileName);
                    
                    if (currentDataFile.renameTo(oldFile)) {
                        synchronized (completedFiles) {
                            completedFiles.add(oldFile);
                            Log.i(TAG, "已重命名现有GZIP文件: " + oldFile.getName());
                        }
                    }
                    
                    // 创建新的GZIP文件
                    currentFileOutputStream = new FileOutputStream(currentDataFile, false);
                    currentGzipOutputStream = new GZIPOutputStream(currentFileOutputStream, BUFFER_SIZE);
                    bytesWrittenCurrentFile.set(0);
                    Log.d(TAG, "为重新创建的GZIP文件创建输出流: " + currentDataFile.getName());
                }
            } else {
                // 非压缩文件，可以直接追加
                currentFileOutputStream = new FileOutputStream(currentDataFile, true);
                currentBufferedWriter = new BufferedWriter(new OutputStreamWriter(currentFileOutputStream, "UTF-8"), BUFFER_SIZE);
                Log.d(TAG, "为非压缩文件创建输出流: " + currentDataFile.getName() + " (追加模式)");
            }
            
            Log.d(TAG, "已打开文件输出流: " + currentDataFile.getName() + " (压缩: " + useCompression + ")");
            
        } catch (IOException e) {
            Log.e(TAG, "打开文件输出流失败", e);
            closeCurrentStreams();
        }
    }
    
    /**
     * 关闭当前的输出流
     */
    private synchronized void closeCurrentStreams() {
        try {
            if (currentBufferedWriter != null) {
                currentBufferedWriter.flush();
                currentBufferedWriter.close();
                currentBufferedWriter = null;
            }
            
            if (currentGzipOutputStream != null) {
                currentGzipOutputStream.finish();
                currentGzipOutputStream.close();
                currentGzipOutputStream = null;
            }
            
            if (currentFileOutputStream != null) {
                currentFileOutputStream.close();
                currentFileOutputStream = null;
            }
            
            Log.d(TAG, "已关闭文件输出流");
            
        } catch (IOException e) {
            Log.e(TAG, "关闭文件输出流失败", e);
        }
    }
    
    /**
     * 启动定时文件切换任务
     */
    private void startFileRotationTask() {
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            try {
                scheduledExecutor.scheduleAtFixedRate(() -> {
                    try {
                        if (dataManagerInstance.isRecordingToFile()) { // 仅在记录时切换文件
                            Log.i(TAG, "执行定时文件切换");
                            createNewDataFile();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "定时文件切换失败", e);
                    }
                }, fileRotationIntervalMs, fileRotationIntervalMs, TimeUnit.MILLISECONDS);
                
                Log.d(TAG, "定时文件切换任务已启动，间隔: " + (fileRotationIntervalMs / (60 * 1000)) + "分钟");
            } catch (java.util.concurrent.RejectedExecutionException e) {
                Log.e(TAG, "启动定时文件切换任务失败: " + e.getMessage());
                // 尝试重建线程池
                createScheduledExecutor();
                try {
                    scheduledExecutor.scheduleAtFixedRate(() -> {
                        try {
                            if (dataManagerInstance.isRecordingToFile()) {
                                Log.i(TAG, "执行定时文件切换");
                                createNewDataFile();
                            }
                        } catch (Exception ex) {
                            Log.e(TAG, "定时文件切换失败", ex);
                        }
                    }, fileRotationIntervalMs, fileRotationIntervalMs, TimeUnit.MILLISECONDS);
                } catch (Exception retryEx) {
                    Log.e(TAG, "重建后启动定时任务仍然失败", retryEx);
                }
            } catch (Exception e) {
                Log.e(TAG, "启动定时文件切换任务时发生未知异常", e);
            }
        } else {
            Log.w(TAG, "定时任务线程池不可用，无法启动文件切换任务");
            // 尝试重建线程池
            createScheduledExecutor();
            if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
                startFileRotationTask(); // 递归调用，但只调用一次
            }
        }
    }

    @Override
    public void onNewDataRecord(DataRecord dataRecord) {
        // 只有在DataManager指示记录到文件时才写入
        if (!dataManagerInstance.isRecordingToFile()) {
            return;
        }

        // 使用同步块确保文件创建的原子性
        synchronized (this) {
            if (currentDataFile == null) {
                createNewDataFile();
            }
        }
        
        // 检查线程池状态并提交任务
        if (fileWriterExecutor != null && !fileWriterExecutor.isShutdown()) {
            try {
                fileWriterExecutor.submit(() -> {
                    try {
                        // 将数据写入文件
                        long bytesWritten = writeDataToFile(dataRecord);
                        long totalBytes = bytesWrittenCurrentFile.addAndGet(bytesWritten);
                        
                        // 检查文件大小，如果超过最大值，则创建新文件
                        if (totalBytes > maxFileSizeBytes) {
                            Log.i(TAG, "文件大小超过限制，执行文件切换");
                            createNewDataFile();
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "写入数据到文件失败", e);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                Log.w(TAG, "文件写入任务被拒绝: " + e.getMessage());
                handleFileWriterRejection(dataRecord);
            } catch (Exception e) {
                Log.e(TAG, "提交文件写入任务失败", e);
                handleFileWriterRejection(dataRecord);
            }
        } else {
            // 线程池不可用，尝试重建或直接处理
            Log.w(TAG, "文件写入线程池不可用，尝试重建或直接写入");
            handleFileWriterRejection(dataRecord);
        }
    }
    
    /**
     * 处理文件写入任务被拒绝的情况
     */
    private void handleFileWriterRejection(DataRecord dataRecord) {
        // 如果线程池已关闭且应该运行，尝试重建
        if ((fileWriterExecutor == null || fileWriterExecutor.isShutdown()) && isFileWriterRunning.get()) {
            Log.d(TAG, "尝试重建文件写入线程池");
            try {
                createFileWriterExecutor();
                
                // 重建后尝试重新提交任务
                if (fileWriterExecutor != null && !fileWriterExecutor.isShutdown()) {
                    try {
                        fileWriterExecutor.submit(() -> {
                            try {
                                long bytesWritten = writeDataToFile(dataRecord);
                                long totalBytes = bytesWrittenCurrentFile.addAndGet(bytesWritten);
                                
                                if (totalBytes > maxFileSizeBytes) {
                                    Log.i(TAG, "文件大小超过限制，执行文件切换");
                                    createNewDataFile();
                                }
                            } catch (IOException e) {
                                Log.e(TAG, "重建后写入数据到文件失败", e);
                            }
                        });
                        return; // 成功提交，直接返回
                    } catch (Exception retryEx) {
                        Log.e(TAG, "重建后重新提交任务失败", retryEx);
                    }
                }
            } catch (Exception rebuildEx) {
                Log.e(TAG, "重建文件写入线程池失败", rebuildEx);
            }
        }
        
        // 如果重建失败或其他情况，直接在当前线程写入，确保数据不丢失
        Log.d(TAG, "在当前线程中直接写入数据");
        try {
            long bytesWritten = writeDataToFile(dataRecord);
            long totalBytes = bytesWrittenCurrentFile.addAndGet(bytesWritten);
            
            if (totalBytes > maxFileSizeBytes) {
                Log.i(TAG, "文件大小超过限制，执行文件切换");
                createNewDataFile();
            }
        } catch (IOException e) {
            Log.e(TAG, "直接写入数据到文件失败", e);
        }
    }
    
    /**
     * 将数据写入文件
     * 使用持久的输出流，避免重复创建GZIP头部
     * @return 写入的字节数
     */
    private synchronized long writeDataToFile(DataRecord dataRecord) throws IOException {
        String jsonLine = dataRecord.toJson().toString() + "\n";
        long bytesWritten = 0;
        
        try {
            // 确保输出流已打开
            if ((useCompression && currentGzipOutputStream == null) || 
                (!useCompression && currentBufferedWriter == null)) {
                openCurrentStreams();
            }
            
            if (useCompression) {
                if (currentGzipOutputStream != null) {
                    byte[] data = jsonLine.getBytes("UTF-8");
                    currentGzipOutputStream.write(data);
                    currentGzipOutputStream.flush();
                    bytesWritten = data.length;
                } else {
                    throw new IOException("GZIP输出流未初始化");
                }
            } else {
                if (currentBufferedWriter != null) {
                    currentBufferedWriter.write(jsonLine);
                    currentBufferedWriter.flush();
                    bytesWritten = jsonLine.getBytes("UTF-8").length;
                } else {
                    throw new IOException("缓冲写入器未初始化");
                }
            }
            
            return bytesWritten;
            
        } catch (IOException e) {
            Log.e(TAG, "写入数据到文件失败", e);
            // 尝试重新打开流
            try {
                openCurrentStreams();
                // 重试一次
                if (useCompression && currentGzipOutputStream != null) {
                    byte[] data = jsonLine.getBytes("UTF-8");
                    currentGzipOutputStream.write(data);
                    currentGzipOutputStream.flush();
                    return data.length;
                } else if (!useCompression && currentBufferedWriter != null) {
                    currentBufferedWriter.write(jsonLine);
                    currentBufferedWriter.flush();
                    return jsonLine.getBytes("UTF-8").length;
                }
            } catch (IOException retryException) {
                Log.e(TAG, "重试写入数据失败", retryException);
            }
            throw e;
        }
    }

    /**
     * 获取当前数据文件路径
     */
    public String getDataFilePath() {
        return currentDataFile != null ? currentDataFile.getAbsolutePath() : "未创建文件";
    }

    /**
     * 获取当前数据文件
     */
    public File getCurrentDataFile() {
        return currentDataFile;
    }
    
    /**
     * 获取所有未上传的文件列表
     */
    public synchronized List<File> getUnuploadedFiles() {
        List<File> filesToUpload = new ArrayList<>();
        
        // 添加已完成的文件
        synchronized (completedFiles) {
            filesToUpload.addAll(completedFiles);
        }
        
        // 对于当前正在写入的文件，需要特殊处理
        if (currentDataFile != null && currentDataFile.exists() && currentDataFile.length() > 0) {
            try {
                // 为当前文件创建一个完整的副本用于上传
                File uploadFile = createUploadCopyOfCurrentFile();
                if (uploadFile != null) {
                    filesToUpload.add(uploadFile);
                    Log.d(TAG, "创建当前文件的上传副本: " + uploadFile.getName());
                }
            } catch (Exception e) {
                Log.e(TAG, "创建当前文件副本失败", e);
            }
        }
        
        return filesToUpload;
    }
    
    /**
     * 为当前文件创建一个完整的上传副本
     * 对于GZIP文件，需要正确关闭流以确保文件完整性
     */
    private synchronized File createUploadCopyOfCurrentFile() {
        if (currentDataFile == null || !currentDataFile.exists() || currentDataFile.length() == 0) {
            return null;
        }
        
        try {
            // 创建临时文件名
            String tempFileName = "upload_" + System.currentTimeMillis() + "_" + currentDataFile.getName();
            // 避免重复的temp前缀
            if (tempFileName.contains("temp_")) {
                tempFileName = tempFileName.replace("temp_", "");
            }
            File tempFile = new File(storageDir, tempFileName);
            
            // 等待所有写入操作完成
            if (fileWriterExecutor != null && !fileWriterExecutor.isShutdown()) {
                try {
                    fileWriterExecutor.submit(() -> {
                        // 确保所有写入操作完成
                    }).get(2, TimeUnit.SECONDS);
                } catch (Exception e) {
                    Log.w(TAG, "等待文件写入完成时出现异常: " + e.getMessage());
                }
            }
            
            if (useCompression && currentGzipOutputStream != null) {
                // 对于GZIP文件，需要创建一个完整的副本
                createCompleteGzipCopy(tempFile);
            } else {
                // 对于非压缩文件，直接复制
                flushCurrentFile();
                if (copyFile(currentDataFile, tempFile)) {
                    return tempFile;
                } else {
                    return null;
                }
            }
            
            return tempFile;
            
        } catch (Exception e) {
            Log.e(TAG, "创建上传副本失败", e);
            return null;
        }
    }
    
    /**
     * 创建GZIP文件的完整副本
     * 通过临时关闭并重新打开流来确保文件完整性
     */
    private synchronized void createCompleteGzipCopy(File tempFile) throws IOException {
        // 保存当前状态
        File originalFile = currentDataFile;
        long originalBytesWritten = bytesWrittenCurrentFile.get();
        
        try {
            // 临时关闭当前流以确保GZIP文件完整
            closeCurrentStreams();
            
            // 复制完整的GZIP文件
            if (!copyFile(originalFile, tempFile)) {
                throw new IOException("复制GZIP文件失败");
            }
            
            Log.d(TAG, "成功创建GZIP文件的完整副本: " + tempFile.getName());
            
        } finally {
            // 重新打开流继续写入
            currentDataFile = originalFile;
            bytesWrittenCurrentFile.set(originalBytesWritten);
            openCurrentStreams();
        }
    }
    
    /**
     * 刷新当前文件的缓冲区
     */
    private synchronized void flushCurrentFile() {
        try {
            if (currentBufferedWriter != null) {
                currentBufferedWriter.flush();
            }
            if (currentGzipOutputStream != null) {
                currentGzipOutputStream.flush();
            }
            if (currentFileOutputStream != null) {
                currentFileOutputStream.flush();
            }
            Log.d(TAG, "已刷新当前文件缓冲区");
        } catch (IOException e) {
            Log.e(TAG, "刷新文件缓冲区失败", e);
        }
    }
    
    /**
     * 复制文件
     * 使用NIO通道方式，避免大量内存使用
     */
    private boolean copyFile(File source, File dest) {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest)) {
            
            FileChannel sourceChannel = fis.getChannel();
            FileChannel destChannel = fos.getChannel();
            
            // 分块传输，而不是一次性传输整个文件
            // 这样可以避免内存Pinning问题
            long size = sourceChannel.size();
            long position = 0;
            
            // 使用更小的缓冲区，确保每次只处理小块数据
            long chunkSize = 32 * 1024; // 进一步减小到32KB，更安全
            
            // 使用循环方式分块传输，避免长时间占用内存
            while (position < size) {
                long count = Math.min(chunkSize, size - position);
                position += sourceChannel.transferTo(position, count, destChannel);
                
                // 每传输一块就刷新输出通道，确保数据立即写出
                destChannel.force(false);
                
                // 定期释放资源
                if (position % (512 * 1024) == 0) { // 每512KB
                    // 让出CPU时间给GC
                    Thread.yield();
                    
                    // 提示系统可以进行GC（轻量级提示） - 移除显式GC调用
                    // System.gc(); 
                }
            }
            
            return true;
        } catch (IOException e) {
            Log.e(TAG, "复制文件失败: " + source.getAbsolutePath(), e);
            return false;
        }
    }
    
    /**
     * 标记文件已上传
     * @param files 已上传的文件列表
     */
    public synchronized void markFilesAsUploaded(List<File> files) {
        if (files == null || files.isEmpty()) return;
        
        for (File file : files) {
            // 如果是上传临时文件，直接删除
            if (file.getName().startsWith("upload_") || file.getName().startsWith("temp_")) {
                if (file.delete()) {
                    Log.d(TAG, "已删除上传临时文件: " + file.getName());
                } else {
                    Log.w(TAG, "无法删除上传临时文件: " + file.getName());
                }
                continue;
            }
            
            // 从未上传列表中移除
            synchronized (completedFiles) {
                if (completedFiles.remove(file)) {
                    Log.d(TAG, "从未上传列表中移除文件: " + file.getName());
                }
            }
            
            // 将文件移动到已上传目录或标记
            // 这里简单起见，我们直接删除已上传的文件
            // 在实际应用中，可能需要保留一段时间或移动到另一个目录
            if (file.delete()) {
                Log.d(TAG, "已删除已上传文件: " + file.getName());
            } else {
                Log.w(TAG, "无法删除已上传文件: " + file.getName());
            }
        }
        
        lastUploadTimestamp = System.currentTimeMillis();
        
        // 保存上传时间到SharedPreferences
        prefs.edit().putLong(PREF_LAST_UPLOAD_TIME, lastUploadTimestamp).apply();
        
        Log.i(TAG, "已标记 " + files.size() + " 个文件为已上传");
    }
    
    /**
     * 获取上次上传时间（格式化的字符串）
     */
    public String getLastUploadTimeString() {
        if (lastUploadTimestamp == 0) {
            return "从未上传";
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date(lastUploadTimestamp));
    }
    

    
    /**
     * 设置最大文件大小（字节）
     */
    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        if (maxFileSizeBytes < 1024 * 1024) { // 最小1MB
            maxFileSizeBytes = 1024 * 1024;
        }
        this.maxFileSizeBytes = maxFileSizeBytes;
        Log.i(TAG, "设置最大文件大小: " + (maxFileSizeBytes / (1024 * 1024)) + "MB");
    }
    
    /**
     * 设置文件切换间隔（毫秒）
     */
    public void setFileRotationIntervalMs(long fileRotationIntervalMs) {
        if (fileRotationIntervalMs < 60 * 1000) { // 最小1分钟
            fileRotationIntervalMs = 60 * 1000;
        }
        this.fileRotationIntervalMs = fileRotationIntervalMs;
        
        // 重新创建定时任务线程池
        createScheduledExecutor();
        
        // 启动新的定时任务
        startFileRotationTask();
        
        Log.i(TAG, "设置文件切换间隔: " + (fileRotationIntervalMs / (60 * 1000)) + "分钟");
    }
    
    /**
     * 获取未上传文件数量
     */
    public int getUnuploadedFilesCount() {
        synchronized (completedFiles) {
            return completedFiles.size() + (currentDataFile != null && currentDataFile.exists() && currentDataFile.length() > 0 ? 1 : 0);
        }
    }
    
    /**
     * 获取未上传文件总大小（KB）
     */
    public long getUnuploadedFilesTotalSizeKB() {
        long totalSize = 0;
        
        synchronized (completedFiles) {
            for (File file : completedFiles) {
                totalSize += file.length();
            }
        }
        
        if (currentDataFile != null && currentDataFile.exists()) {
            totalSize += currentDataFile.length();
        }
        
        return totalSize / 1024; // 转换为KB
    }
    
    /**
     * 获取是否使用压缩
     */
    public boolean isUsingCompression() {
        return useCompression;
    }
    
    /**
     * 立即切换到新文件
     */
    public void forceRotateFile() {
        if (dataManagerInstance.isRecordingToFile()) {
            createNewDataFile();
        }
    }
    
    /**
     * 清理所有文件（除了当前正在写入的文件）
     * @return 删除的文件数量
     */
    public synchronized int cleanAllFiles() {
        int deletedCount = 0;
        
        synchronized (completedFiles) {
            // 删除所有已完成的文件
            for (File file : new ArrayList<>(completedFiles)) {
                if (file.exists() && file.delete()) {
                    deletedCount++;
                    Log.d(TAG, "已删除文件: " + file.getName());
                }
            }
            completedFiles.clear();
        }
        
        // 删除存储目录中其他符合命名规则的文件（排除当前文件）
        File[] allFiles = storageDir.listFiles((dir, name) -> {
            boolean matchesPattern = name.contains("_sensor_data_") && 
                                   (name.endsWith(FILENAME_EXTENSION) || name.endsWith(FILENAME_EXTENSION_COMPRESSED));
            boolean isNotCurrent = currentDataFile == null || !name.equals(currentDataFile.getName());
            return matchesPattern && isNotCurrent;
        });
        
        if (allFiles != null) {
            for (File file : allFiles) {
                if (file.delete()) {
                    deletedCount++;
                    Log.d(TAG, "已删除额外文件: " + file.getName());
                }
            }
        }
        
        Log.i(TAG, "清理文件完成，共删除 " + deletedCount + " 个文件");
        return deletedCount;
    }
    
    /**
     * 保留最近的N个文件，删除其余文件
     * @param keepCount 要保留的文件数量
     * @return 删除的文件数量
     */
    public synchronized int keepRecentFiles(int keepCount) {
        if (keepCount <= 0) {
            return cleanAllFiles();
        }
        
        // 获取所有符合命名规则的文件（包括已完成的和存储目录中的）
        List<File> allDataFiles = new ArrayList<>();
        
        synchronized (completedFiles) {
            allDataFiles.addAll(completedFiles);
        }
        
        // 扫描存储目录中的其他文件
        File[] directoryFiles = storageDir.listFiles((dir, name) -> {
            boolean matchesPattern = name.contains("_sensor_data_") && 
                                   (name.endsWith(FILENAME_EXTENSION) || name.endsWith(FILENAME_EXTENSION_COMPRESSED));
            boolean isNotCurrent = currentDataFile == null || !name.equals(currentDataFile.getName());
            boolean notInCompletedList = completedFiles.stream().noneMatch(f -> f.getName().equals(name));
            return matchesPattern && isNotCurrent && notInCompletedList;
        });
        
        if (directoryFiles != null) {
            for (File file : directoryFiles) {
                allDataFiles.add(file);
            }
        }
        
        // 按最后修改时间排序（新到旧）
        allDataFiles.sort((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        
        int deletedCount = 0;
        
        // 删除超出保留数量的文件
        if (allDataFiles.size() > keepCount) {
            List<File> filesToDelete = allDataFiles.subList(keepCount, allDataFiles.size());
            
            for (File file : filesToDelete) {
                if (file.exists() && file.delete()) {
                    deletedCount++;
                    Log.d(TAG, "已删除旧文件: " + file.getName());
                    
                    // 从已完成文件列表中移除
                    synchronized (completedFiles) {
                        completedFiles.remove(file);
                    }
                }
            }
        }
        
        Log.i(TAG, "保留最近 " + keepCount + " 个文件，删除了 " + deletedCount + " 个旧文件");
        return deletedCount;
    }
    
    /**
     * 获取当前数据文件信息
     * @return 当前文件的信息字符串
     */
    public String getCurrentFileInfo() {
        if (currentDataFile == null) {
            return "当前文件: 无";
        }
        
        String fileName = currentDataFile.getName();
        long sizeKB = currentDataFile.length() / 1024;
        long sizeMB = sizeKB / 1024;
        
        if (sizeMB > 0) {
            return String.format("当前文件: %s (%d MB)", fileName, sizeMB);
        } else {
            return String.format("当前文件: %s (%d KB)", fileName, sizeKB);
        }
    }
    
    /**
     * 获取所有数据文件列表（按时间排序）
     * @return 文件列表信息
     */
    public List<String> getAllDataFilesInfo() {
        List<File> allFiles = new ArrayList<>();
        
        // 添加当前文件
        if (currentDataFile != null && currentDataFile.exists()) {
            allFiles.add(currentDataFile);
        }
        
        // 添加已完成的文件
        synchronized (completedFiles) {
            allFiles.addAll(completedFiles);
        }
        
        // 扫描目录中的其他文件
        File[] directoryFiles = storageDir.listFiles((dir, name) -> 
            name.contains("_sensor_data_") && 
            (name.endsWith(FILENAME_EXTENSION) || name.endsWith(FILENAME_EXTENSION_COMPRESSED)));
        
        if (directoryFiles != null) {
            for (File file : directoryFiles) {
                if (!allFiles.contains(file)) {
                    allFiles.add(file);
                }
            }
        }
        
        // 按最后修改时间排序（新到旧）
        allFiles.sort((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        
        List<String> fileInfoList = new ArrayList<>();
        for (File file : allFiles) {
            long sizeKB = file.length() / 1024;
            long sizeMB = sizeKB / 1024;
            String sizeStr = sizeMB > 0 ? sizeMB + " MB" : sizeKB + " KB";
            
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            String timeStr = sdf.format(new Date(file.lastModified()));
            
            String status = file.equals(currentDataFile) ? " (当前)" : "";
            fileInfoList.add(String.format("%s - %s - %s%s", file.getName(), sizeStr, timeStr, status));
        }
        
        return fileInfoList;
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
            Log.i(TAG, "内存严重不足，执行紧急资源释放");
            // 强制切换日志文件，关闭旧文件
            if (currentDataFile != null && dataManagerInstance.isRecordingToFile()) {
                createNewDataFile();
            }
            
            // 清理不必要的缓存
            Runtime.getRuntime().gc();
            
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            // 内存较为紧张，可以释放一些非关键资源
            Log.i(TAG, "内存较为紧张，释放部分资源");
            // 这里可以加入适当的资源释放逻辑
            
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // 内存有些紧张，可以考虑释放缓存
            Log.i(TAG, "内存略有紧张，执行轻度资源释放");
            // 暂不执行具体操作
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
        Log.i(TAG, "系统内存极度不足，执行全面资源释放");
        
        // 强制切换日志文件，关闭旧文件
        if (currentDataFile != null && dataManagerInstance.isRecordingToFile()) {
            createNewDataFile();
        }
        
        // 建议系统执行GC
        Runtime.getRuntime().gc();
    }
    
    /**
     * 关闭资源
     */
    public void shutdown() {
        Log.d(TAG, "开始关闭StorageManager");
        
        // 标记为不再运行
        isFileWriterRunning.set(false);
        
        // 先关闭文件输出流
        closeCurrentStreams();
        
        // 取消注册内存回调
        if (context != null) {
            try {
                context.unregisterComponentCallbacks(this);
            } catch (Exception e) {
                Log.w(TAG, "取消注册内存回调失败", e);
            }
        }
        
        // 关闭文件写入线程池
        if (fileWriterExecutor != null && !fileWriterExecutor.isShutdown()) {
            try {
                fileWriterExecutor.shutdown();
                if (!fileWriterExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    Log.w(TAG, "文件写入线程池未能在2秒内正常关闭，强制关闭");
                    fileWriterExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "等待文件写入线程池关闭被中断");
                fileWriterExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e(TAG, "关闭文件写入线程池失败", e);
            }
        }
        
        // 关闭定时任务线程池
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            try {
                scheduledExecutor.shutdown();
                if (!scheduledExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    Log.w(TAG, "定时任务线程池未能在2秒内正常关闭，强制关闭");
                    scheduledExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "等待定时任务线程池关闭被中断");
                scheduledExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e(TAG, "关闭定时任务线程池失败", e);
            }
        }
        
        Log.d(TAG, "StorageManager已关闭");
    }
}


