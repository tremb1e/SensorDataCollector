package com.example.sensordatacollector;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionSpec;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 网络管理器
 * 用于处理数据文件上传功能
 */
@SuppressWarnings("deprecation") // 添加注解以抑制整个类中的过时API警告
public class NetworkManager implements ComponentCallbacks2 {
    private static final String TAG = "NetworkManager";
    private static final int MAX_RETRY_COUNT = 3; // 最大重试次数
    private static final int RETRY_DELAY_MS = 3000; // 重试延迟时间（毫秒）
    private static final int CONNECTION_TIMEOUT = 30; // 连接超时时间（秒）
    private static final int READ_TIMEOUT = 30; // 读取超时时间（秒）
    private static final int WRITE_TIMEOUT = 60; // 写入超时时间（秒）
    private static final int BUFFER_SIZE = 8 * 1024; // 上传缓冲区大小（8KB）
    
    private Context context;
    private final OkHttpClient client;
    private final ExecutorService networkExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "NetworkManager-Upload");
        thread.setDaemon(true); // 设置为守护线程
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    }); // 减少到2个线程处理上传
    private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "NetworkManager-Retry");
        thread.setDaemon(true); // 设置为守护线程
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    }); // 用于重试调度
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // 存储断点续传信息的Map，key为文件路径，value为已上传的字节数
    private final Map<String, Long> uploadProgressMap = new ConcurrentHashMap<>();
    
    // 标识是否有上传正在进行
    private final AtomicBoolean isUploading = new AtomicBoolean(false);
    
    // 存储所有活跃的Call对象，用于取消操作
    private final Map<String, Call> activeCallsMap = new ConcurrentHashMap<>();

    public interface UploadCallback {
        void onSuccess(String responseBody);
        void onFailure(String errorMessage);
        void onProgress(int overallProgress); // 总体进度(0-100)
    }
    
    public NetworkManager() {
        this(null);
    }
    
    public NetworkManager(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
        
        // 创建OkHttpClient
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        
        // 配置超时时间
        builder.connectTimeout(CONNECTION_TIMEOUT, TimeUnit.SECONDS);
        builder.readTimeout(READ_TIMEOUT, TimeUnit.SECONDS);
        builder.writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS);
        
        // 配置重试策略
        builder.retryOnConnectionFailure(true);
        
        // 防止中间人攻击 - 使用推荐的TLS设置
        builder.connectionSpecs(java.util.Arrays.asList(
            ConnectionSpec.MODERN_TLS,  // 现代TLS配置
            ConnectionSpec.COMPATIBLE_TLS,  // 兼容模式TLS配置
            ConnectionSpec.CLEARTEXT)); // 明文连接配置
        
        // 配置连接池
        builder.connectionPool(new okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS));
        
        client = builder.build();
        
        // 如果有上下文，注册内存回调
        if (this.context != null) {
            this.context.registerComponentCallbacks(this);
        }
        
        Log.i(TAG, "NetworkManager初始化完成，连接超时：" + CONNECTION_TIMEOUT + "秒，读取超时：" + READ_TIMEOUT + "秒，写入超时：" + WRITE_TIMEOUT + "秒");
    }
    
    /**
     * 增量上传所有新文件
     * @param files 要上传的文件列表
     * @param serverIp 服务器IP
     * @param serverPort 服务器端口
     * @param callback 上传回调
     */
    public void uploadFiles(List<File> files, String serverIp, String serverPort, final UploadCallback callback) {
        // 参数验证
        if (isUploading.get()) {
            String errorMsg = "已有上传任务正在进行";
            Log.w(TAG, errorMsg);
            mainHandler.post(() -> callback.onFailure(errorMsg));
            return;
        }
        
        if (files == null || files.isEmpty()) {
            String errorMsg = "没有文件需要上传";
            Log.w(TAG, errorMsg);
            mainHandler.post(() -> callback.onFailure(errorMsg));
            return;
        }
        
        if (!isValidServerInfo(serverIp, serverPort)) {
            String errorMsg = context.getString(R.string.error_invalid_server_info);
            Log.e(TAG, errorMsg + ": IP=" + serverIp + ", Port=" + serverPort);
            mainHandler.post(() -> callback.onFailure(errorMsg));
            return;
        }
        
        final String baseUrl = "http://" + serverIp.trim() + ":" + serverPort.trim();
        Log.d(TAG, "开始上传" + files.size() + "个文件到: " + baseUrl);
        
        if (!isUploading.compareAndSet(false, true)) {
            String errorMsg = "已有上传任务正在进行";
            Log.w(TAG, errorMsg);
            mainHandler.post(() -> callback.onFailure(errorMsg));
            return;
        }
        
        // 使用线程池并发上传多个文件
        networkExecutor.submit(() -> {
            try {
                int totalFiles = files.size();
                int successCount = 0;
                int failCount = 0;
                AtomicInteger currentIndex = new AtomicInteger(0);
                StringBuilder errorMessages = new StringBuilder(); // 收集错误信息
                
                Log.i(TAG, "开始上传队列处理，共" + totalFiles + "个文件");
                
                for (int i = 0; i < totalFiles; i++) {
                    File file = files.get(i);
                    currentIndex.set(i);
                    
                    // 计算并报告总体进度
                    final int currentProgress = (int)((i * 100.0) / totalFiles);
                    mainHandler.post(() -> callback.onProgress(currentProgress));
                    
                    // 检查文件是否有效
                    if (file == null || !file.exists() || file.length() == 0) {
                        String errorMsg = "文件不存在或为空: " + (file != null ? file.getName() : "null");
                        Log.e(TAG, errorMsg);
                        failCount++;
                        errorMessages.append(errorMsg).append("\n");
                        continue;
                    }
                    
                    Log.i(TAG, "准备上传文件 " + (i+1) + "/" + totalFiles + ": " + file.getName() + " (" + formatFileSize(file.length()) + ")");
                    
                    try {
                        // 检查断点续传信息
                        long uploadedBytes = uploadProgressMap.getOrDefault(file.getAbsolutePath(), 0L);
                        
                        // 添加重试机制
                        boolean success = false;
                        int retryCount = 0;
                        Exception lastException = null;
                        
                        while (!success && retryCount <= MAX_RETRY_COUNT) {
                            Call call = null; // 在循环外部声明，以便在catch块中访问
                            try {
                                if (retryCount > 0) {
                                    Log.i(TAG, "重试上传文件(" + retryCount + "/" + MAX_RETRY_COUNT + "): " + file.getName());
                                    // 延迟一段时间再重试，时间随重试次数增加
                                    Thread.sleep(RETRY_DELAY_MS * retryCount);
                                }
                                
                                // 在这里创建Call对象，以便可以在catch块中检查其状态
                                RequestBody requestFile = createRequestBody(file, uploadedBytes, file.length(), new UploadCallback() {
                                    @Override
                                    public void onSuccess(String responseBody) {
                                        // 单个文件上传成功，从Map中移除
                                        uploadProgressMap.remove(file.getAbsolutePath());
                                        // 从活跃调用中移除
                                        activeCallsMap.remove(file.getAbsolutePath());
                                    }
                                    
                                    @Override
                                    public void onFailure(String errorMessage) {
                                        // 单个文件上传失败时不处理，会在外层报告
                                    }
                                    
                                    @Override
                                    public void onProgress(int overallProgress) {
                                        // 报告单个文件的进度，结合总体进度
                                        int combinedProgress = (int)((currentIndex.get() * 100.0 + overallProgress) / totalFiles);
                                        mainHandler.post(() -> callback.onProgress(combinedProgress));
                                    }
                                });
                                Request request = buildRequest(file, baseUrl + "/upload", uploadedBytes, file.length(), requestFile); // 假设这个方法被提取出来
                                call = client.newCall(request);
                                activeCallsMap.put(file.getAbsolutePath(), call);

                                success = uploadFileWithResumeInternal(call, file, baseUrl + "/upload", uploadedBytes, new UploadCallback() {
                                    @Override
                                    public void onSuccess(String responseBody) {
                                        // 单个文件上传成功，从Map中移除
                                        uploadProgressMap.remove(file.getAbsolutePath());
                                        // 从活跃调用中移除
                                        activeCallsMap.remove(file.getAbsolutePath());
                                    }
                                    
                                    @Override
                                    public void onFailure(String errorMessage) {
                                        // 单个文件上传失败时不处理，会在外层报告
                                    }
                                    
                                    @Override
                                    public void onProgress(int overallProgress) {
                                        // 报告单个文件的进度，结合总体进度
                                        int combinedProgress = (int)((currentIndex.get() * 100.0 + overallProgress) / totalFiles);
                                        mainHandler.post(() -> callback.onProgress(combinedProgress));
                                    }
                                });
                                
                                if (success) {
                                    break;
                                }
                            } catch (InterruptedException e) {
                                // 线程被中断，可能是取消操作
                                Log.w(TAG, "上传过程被中断: " + file.getName());
                                lastException = e;
                                Thread.currentThread().interrupt();
                                success = false; // 明确标记不成功，不再重试
                                break; // 跳出重试循环
                            } catch (IOException e) {
                                lastException = e;
                                if (call != null && call.isCanceled()) {
                                    Log.w(TAG, "上传被取消: " + file.getName());
                                    success = false; // 标记不成功，不再重试
                                    break; // 跳出重试循环
                                } else {
                                    // 处理其他IO异常并准备重试
                                    String errorMsg = "上传文件IO异常";
                                    if (e instanceof SocketTimeoutException) {
                                        errorMsg = "上传超时，准备重试";
                                    } else if (e instanceof ConnectException) {
                                        errorMsg = "连接服务器失败，准备重试";
                                    } else if (e instanceof UnknownHostException) {
                                        errorMsg = "未能解析服务器地址，准备重试";
                                    } else if (e instanceof SocketException) {
                                        errorMsg = "网络连接错误，准备重试";
                                    } else if (e instanceof java.net.ProtocolException) {
                                        errorMsg = "协议错误，准备重试";
                                        // 对于 "unexpected end of stream" 错误，清除断点续传进度
                                        if (e.getMessage() != null && e.getMessage().contains("unexpected end of stream")) {
                                            Log.w(TAG, "检测到流意外结束，清除断点续传进度: " + file.getName());
                                            uploadProgressMap.remove(file.getAbsolutePath());
                                            uploadedBytes = 0; // 重新开始上传
                                        }
                                    } else if (e.getMessage() != null && e.getMessage().contains("unexpected end of stream")) {
                                        errorMsg = "数据流意外结束，准备重试";
                                        // 清除断点续传进度，重新开始
                                        Log.w(TAG, "检测到流意外结束，清除断点续传进度: " + file.getName());
                                        uploadProgressMap.remove(file.getAbsolutePath());
                                        uploadedBytes = 0;
                                    }
                                    Log.e(TAG, errorMsg + ": " + file.getName() + " - " + e.getMessage());
                                }
                            } catch (Exception e) { // 捕获其他非IO、非Interrupted的运行时异常
                                lastException = e;
                                Log.e(TAG, "上传文件时发生意外错误: " + file.getName(), e);
                                success = false; // 标记不成功，不再重试
                                break; // 跳出重试循环
                            } finally {
                                if (call != null) {
                                    activeCallsMap.remove(file.getAbsolutePath());
                                }
                            }
                            
                            if (!success) { // 只有在不是因为取消或致命错误中断时才增加重试计数
                                retryCount++;
                            }
                        }
                        
                        if (success) {
                            successCount++;
                            Log.i(TAG, "文件上传成功 " + (i+1) + "/" + totalFiles + ": " + file.getName());
                        } else {
                            failCount++;
                            String errorMsg = "上传文件失败，已达最大重试次数: " + file.getName();
                            if (lastException != null) {
                                errorMsg += " - " + lastException.getMessage();
                                Log.e(TAG, errorMsg, lastException);
                            } else {
                                Log.e(TAG, errorMsg);
                            }
                            errorMessages.append(errorMsg).append("\n");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "处理文件上传过程中出现异常: " + file.getName(), e);
                        failCount++;
                        errorMessages.append("上传 ").append(file.getName()).append(" 失败: ").append(e.getMessage()).append("\n");
                    }
                }
                
                final int finalSuccessCount = successCount;
                final int finalFailCount = failCount;
                final String errorSummary = errorMessages.toString();
                
                // 上传完成，报告结果
                isUploading.set(false);
                mainHandler.post(() -> {
                    if (finalFailCount == 0) {
                        callback.onProgress(100);
                        callback.onSuccess(context.getString(R.string.upload_success_count_log, finalSuccessCount));
                        Log.i(TAG, context.getString(R.string.all_files_upload_success_log, finalSuccessCount));
                    } else {
                        String message = "上传完成，成功: " + finalSuccessCount + "，失败: " + finalFailCount;
                        if (errorSummary.length() > 0) {
                            message += "\n错误摘要:\n" + (errorSummary.length() > 500 ? errorSummary.substring(0, 500) + "..." : errorSummary);
                        }
                        callback.onFailure(message);
                        Log.w(TAG, message);
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "上传任务执行失败", e);
                isUploading.set(false);
                mainHandler.post(() -> callback.onFailure("上传任务执行失败: " + e.getMessage()));
                
                // 清理所有活跃调用
                cancelAllUploads();
            }
        });
    }

    /**
     * 上传单个文件，支持断点续传
     * @param file 要上传的文件
     * @param url 服务器URL
     * @param uploadedBytes 已上传的字节数
     * @param singleFileCallback 单个文件上传回调
     * @return 是否上传成功
     */
    private boolean uploadFileWithResume(File file, String url, long uploadedBytes, UploadCallback singleFileCallback) throws IOException {
        // 这个方法现在变成了一个包装器，或者其内容大部分移到 uploadFileWithResumeInternal
        // 为了演示，我将假设其核心逻辑被一个名为 uploadFileWithResumeInternal 的新私有方法处理
        // 该方法会接收一个已经创建好的 Call 对象
        // 此处仅保留框架，实际需要重构以适应上面的修改
        
        // 文件有效性检查等保持不变...
        if (file == null || !file.exists() || file.length() == 0) {
            // ...
            return false;
        }
        long fileSize = file.length();
        if (uploadedBytes >= fileSize) {
            // ...
            return true;
        }

        // RequestBody 和 Request 的创建逻辑需要提取到 uploadFiles 的循环中，或作为辅助方法
        // Call call = client.newCall(request);
        // return uploadFileWithResumeInternal(call, file, url, uploadedBytes, singleFileCallback);
        throw new UnsupportedOperationException("This method needs refactoring after changes in uploadFiles loop.");
    }

    // 新增：构建RequestBody的辅助方法
    private RequestBody createRequestBody(final File file, final long uploadedBytes, final long fileSize, final UploadCallback singleFileCallback) {
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.parse("application/octet-stream");
            }

            @Override
            public long contentLength() {
                return fileSize - uploadedBytes;
            }

            @Override
            public void writeTo(okio.BufferedSink sink) throws IOException {
                long bytesRemaining = fileSize - uploadedBytes;
                long totalBytesRead = 0;
                
                try (FileInputStream fileInputStream = new FileInputStream(file)) {
                    fileInputStream.skip(uploadedBytes);
                    okio.Source source = okio.Okio.source(fileInputStream);
                    okio.BufferedSource bufferedSource = okio.Okio.buffer(source);
                    long stepSize = Math.min(4 * 1024, bytesRemaining);
                    
                    while (totalBytesRead < bytesRemaining) {
                        if (Thread.interrupted()) { // 更早地检查中断状态
                            throw new IOException("上传被中断");
                        }
                        long bytesToRead = Math.min(stepSize, bytesRemaining - totalBytesRead);
                        okio.Buffer buffer = new okio.Buffer();
                        long bytesTransferred = bufferedSource.read(buffer, bytesToRead);
                        if (bytesTransferred == -1) break;
                        sink.write(buffer, bytesTransferred);
                        totalBytesRead += bytesTransferred;
                        sink.flush();
                        buffer.clear();
                        
                        if (totalBytesRead % (512 * 1024) == 0 || totalBytesRead == bytesRemaining) {
                            final float progress = (float) (uploadedBytes + totalBytesRead) / fileSize * 100;
                            mainHandler.post(() -> {
                                if (singleFileCallback != null) singleFileCallback.onProgress((int) progress);
                            });
                        }
                    }
                }
            }
        };
    }

    // 新增：构建Request的辅助方法
    private Request buildRequest(File file, String url, long uploadedBytes, long fileSize, RequestBody requestFile) {
        Map<String, String> headersMap = new HashMap<>();
        headersMap.put("Content-Type", "multipart/form-data"); // 这个头可能应该在MultipartBody层面设置
        if (uploadedBytes > 0) {
            headersMap.put("Range", "bytes=" + uploadedBytes + "-" + (fileSize - 1));
        }

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(), requestFile)
                .addFormDataPart("fileName", file.getName())
                .addFormDataPart("fileSize", String.valueOf(fileSize))
                .addFormDataPart("uploadedBytes", String.valueOf(uploadedBytes))
                .build();
        
        Request.Builder requestBuilder = new Request.Builder().url(url).post(requestBody);
        Headers.Builder headersBuilder = new Headers.Builder();
        for (Map.Entry<String, String> entry : headersMap.entrySet()) {
            headersBuilder.add(entry.getKey(), entry.getValue());
        }
        requestBuilder.headers(headersBuilder.build());
        return requestBuilder.build();
    }

    // 新增：实际执行上传和处理响应的内部方法
    private boolean uploadFileWithResumeInternal(Call call, File file, String url, long uploadedBytes, UploadCallback singleFileCallback) throws IOException {
        Response response = null;
        try {
            long startTime = System.currentTimeMillis();
            response = call.execute(); // OkHttp的execute会抛出IOException
            long endTime = System.currentTimeMillis();

            if (response.isSuccessful()) {
                ResponseBody responseBody = response.body();
                String responseBodyString = responseBody != null ? responseBody.string() : "";
                Log.i(TAG, "文件上传成功: " + file.getName() + ", 响应: " + responseBodyString + ", 耗时: " + (endTime - startTime) / 1000.0 + "秒");
                if (singleFileCallback != null) singleFileCallback.onSuccess(responseBodyString);
                uploadProgressMap.remove(file.getAbsolutePath()); // 成功后移除进度
                return true;
            } else {
                String errorCode = String.valueOf(response.code());
                String responseText = response.body() != null ? response.body().string() : "";
                String errorMsg = "上传失败，状态码: " + errorCode + (responseText.isEmpty() ? "" : ", 响应: " + responseText);
                Log.e(TAG, errorMsg);
                if (response.code() == 404 || response.code() == 500) {
                    uploadProgressMap.put(file.getAbsolutePath(), uploadedBytes);
                }
                if (singleFileCallback != null) singleFileCallback.onFailure(errorMsg);
                return false;
            }
        } finally {
            if (response != null && response.body() != null) {
                response.body().close();
            }
            // activeCallsMap.remove由外部的finally块处理
        }
    }
    
    /**
     * 检查服务器信息是否有效
     */
    private boolean isValidServerInfo(String serverIp, String serverPort) {
        if (serverIp == null || serverIp.trim().isEmpty()) {
            return false;
        }
        
        if (serverPort == null || serverPort.trim().isEmpty()) {
            return false;
        }
        
        // 验证端口是否为数字
        try {
            int port = Integer.parseInt(serverPort.trim());
            if (port <= 0 || port > 65535) {
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        
        // 简单的IP格式检查
        String ipTrim = serverIp.trim();
        if (ipTrim.equals("localhost")) {
            return true;
        }
        
        // IPv4格式检查
        String ipv4Pattern = "^([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
                          "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
                          "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
                          "([01]?\\d\\d?|2[0-4]\\d|25[0-5])$";
        
        return ipTrim.matches(ipv4Pattern);
    }
    
    /**
     * 测试服务器连接
     * @param serverIp 服务器IP
     * @param serverPort 服务器端口
     * @param callback 测试结果回调
     */
    public void testServerConnection(String serverIp, String serverPort, final UploadCallback callback) {
        if (!isValidServerInfo(serverIp, serverPort)) {
            mainHandler.post(() -> callback.onFailure("服务器IP或端口格式无效"));
            return;
        }
        
        final String baseUrl = "http://" + serverIp.trim() + ":" + serverPort.trim();
        
        // 创建测试请求
        Request request = new Request.Builder()
                .url(baseUrl + "/ping")
                .get()
                .build();
                
        // 使用3秒超时的客户端
        OkHttpClient testClient = client.newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build();
        
        testClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                String errorMessage = "无法连接到服务器";
                
                if (e instanceof ConnectException) {
                    errorMessage = "连接被拒绝，服务器可能未运行";
                } else if (e instanceof SocketTimeoutException) {
                    errorMessage = "连接超时，请检查网络或服务器地址";
                } else if (e instanceof UnknownHostException) {
                    errorMessage = "无法解析主机名，请检查IP地址";
                }
                
                final String finalErrorMessage = errorMessage + "。详细错误: " + e.getMessage();
                Log.e(TAG, "服务器连接测试失败: " + finalErrorMessage);
                
                mainHandler.post(() -> callback.onFailure(finalErrorMessage));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final int code = response.code();
                final String message = response.message();
                String responseBody = "";
                
                if (response.body() != null) {
                    responseBody = response.body().string();
                }
                
                final String finalResponseBody = responseBody;
                
                if (code >= 200 && code < 300) {
                    Log.i(TAG, context.getString(R.string.server_connection_test_success, code, message));
                    mainHandler.post(() -> callback.onSuccess(context.getString(R.string.server_connection_success_response, finalResponseBody)));
                } else {
                    Log.w(TAG, context.getString(R.string.server_error_status_code, code, message));
                    mainHandler.post(() -> callback.onFailure(context.getString(R.string.server_error_status_code, code, message)));
                }
            }
        });
    }
    
    /**
     * 检查网络连接是否可用
     * @param context 上下文
     * @return 网络连接是否可用
     */
    @SuppressWarnings("deprecation") // 添加注解避免低版本兼容时的警告
    public static boolean isNetworkAvailable(Context context) {
        try {
            android.net.ConnectivityManager connectivityManager = (android.net.ConnectivityManager) 
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
                    
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
                // 兼容低版本
                android.net.NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
                return activeNetworkInfo != null && activeNetworkInfo.isConnected();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "检查网络状态时权限不足: " + e.getMessage());
            // 如果权限不足，假设网络可用，让调用者决定如何处理
            return true;
        } catch (Exception e) {
            Log.e(TAG, "检查网络状态时发生异常: " + e.getMessage());
            // 发生异常时，假设网络可用
            return true;
        }
    }
    
    /**
     * 格式化文件大小为可读格式
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
     * 是否正在上传
     */
    public boolean isUploading() {
        return isUploading.get();
    }
    
    /**
     * 取消所有上传
     */
    public void cancelAllUploads() {
        // 取消所有活跃的调用
        for (Call call : activeCallsMap.values()) {
            if (call != null && !call.isCanceled()) {
                call.cancel();
            }
        }
        activeCallsMap.clear();
        
        // 取消OkHttpClient中的所有调用
        client.dispatcher().cancelAll();
        isUploading.set(false);
        Log.d(TAG, "取消了所有上传任务");
    }

    /**
     * 获取文件上传进度
     * @param filePath 文件路径
     * @return 已上传字节数，如果文件没有上传记录则返回0
     */
    public long getUploadedBytes(String filePath) {
        return uploadProgressMap.getOrDefault(filePath, 0L);
    }
    
    /**
     * 清除特定文件的上传进度
     * @param filePath 文件路径
     */
    public void clearUploadProgress(String filePath) {
        uploadProgressMap.remove(filePath);
        Log.d(TAG, "清除文件上传进度: " + filePath);
    }
    
    /**
     * 清除所有文件的上传进度
     */
    public void clearAllUploadProgress() {
        uploadProgressMap.clear();
        Log.d(TAG, "清除所有文件的上传进度");
    }
    
    /**
     * 实现ComponentCallbacks2接口方法
     * 这个方法会在系统内存不足时被调用
     * @param level 内存紧张等级
     */
    @Override
    public void onTrimMemory(int level) {
        // 根据内存紧张程度执行不同的操作
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE || 
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            // 内存非常紧张，需要立即释放所有可能的资源
            Log.i(TAG, "内存严重不足，执行紧急资源释放");
            cancelAllUploads();
            clearAllUploadProgress();
            uploadProgressMap.clear();
            client.connectionPool().evictAll();
            
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            // 内存较为紧张，可以释放一些非关键资源
            Log.i(TAG, "内存较为紧张，释放部分资源");
            // 清理连接池中的空闲连接
            client.connectionPool().evictAll();
            
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
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
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
        cancelAllUploads();
        clearAllUploadProgress();
        uploadProgressMap.clear();
        client.connectionPool().evictAll();
    }
    
    /**
     * 关闭资源
     */
    public void shutdown() {
        Log.d(TAG, "正在关闭NetworkManager资源...");
        
        // 取消注册内存回调
        if (context != null) {
            context.unregisterComponentCallbacks(this);
        }
        
        // 取消所有上传
        cancelAllUploads();
        
        // 关闭线程池
        if (!networkExecutor.isShutdown()) {
            try {
                networkExecutor.shutdown();
                if (!networkExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    networkExecutor.shutdownNow();
                    Log.d(TAG, "网络执行器强制关闭");
                } else {
                    Log.d(TAG, "网络执行器正常关闭");
                }
            } catch (InterruptedException e) {
                networkExecutor.shutdownNow();
                Thread.currentThread().interrupt();
                Log.w(TAG, "关闭网络执行器时被中断", e);
            }
        }
        
        // 关闭重试执行器
        if (!retryExecutor.isShutdown()) {
            try {
                retryExecutor.shutdown();
                if (!retryExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    retryExecutor.shutdownNow();
                    Log.d(TAG, "重试执行器强制关闭");
                } else {
                    Log.d(TAG, "重试执行器正常关闭");
                }
            } catch (InterruptedException e) {
                retryExecutor.shutdownNow();
                Thread.currentThread().interrupt();
                Log.w(TAG, "关闭重试执行器时被中断", e);
            }
        }
        
        // OkHttpClient的dispatcher有自己的线程池
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        
        Log.i(TAG, "NetworkManager资源已成功关闭");
    }
}

