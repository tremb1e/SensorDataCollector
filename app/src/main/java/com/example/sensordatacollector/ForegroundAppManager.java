package com.example.sensordatacollector;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 前台应用管理类
 * 用于获取当前屏幕显示的前台应用信息
 */
public class ForegroundAppManager implements ComponentCallbacks2 {
    private static final String TAG = "ForegroundAppManager";
    
    private Context context;
    private UsageStatsManager usageStatsManager;
    private PackageManager packageManager;
    
    // 单例模式
    private static volatile ForegroundAppManager instance;
    
    // 缓存包名到应用名称的映射
    private HashMap<String, String> packageNameToAppNameMap = new HashMap<>();
    
    // 上一次检测到的前台应用
    private String lastForegroundPackage = "";
    private String lastForegroundAppName = "未知应用";
    
    // 最近应用列表的最大数量
    private static final int MAX_RECENT_APPS = 10;

    // 私有构造函数
    private ForegroundAppManager(Context context) {
        this.context = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            this.usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        }
        this.packageManager = context.getPackageManager();
        
        // 注册内存回调
        if (this.context != null) {
            this.context.registerComponentCallbacks(this);
            Log.d(TAG, "注册了内存回调");
        }
    }
    
    /**
     * 获取实例
     * 使用ApplicationContext避免内存泄漏
     */
    public static ForegroundAppManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ForegroundAppManager.class) {
                if (instance == null) {
                    // 使用ApplicationContext避免Activity内存泄漏
                    Context appContext = context.getApplicationContext();
                    instance = new ForegroundAppManager(appContext);
                }
            }
        }
        return instance;
    }
    
    /**
     * 检查是否有使用统计权限
     */
    public boolean hasUsageStatsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        
        try {
            AppOpsManager appOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOpsManager == null) {
                Log.w(TAG, "AppOpsManager不可用");
                return false;
            }
            
            int mode = appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.getPackageName()
            );
            
            boolean hasPermission = mode == AppOpsManager.MODE_ALLOWED;
            //Log.d(TAG, "使用统计权限检查结果: " + hasPermission);
            
            // 简单验证权限
            if (hasPermission && usageStatsManager != null) {
                try {
                    long endTime = System.currentTimeMillis();
                    long startTime = endTime - 1000; // 查询1秒
                    UsageEvents events = usageStatsManager.queryEvents(startTime, endTime);
                    return events != null;
                } catch (Exception e) {
                    Log.w(TAG, "权限验证失败", e);
                    return false;
                }
            }
            
            return hasPermission;
            
        } catch (Exception e) {
            Log.e(TAG, "检查使用统计权限时出错", e);
            return false;
        }
    }
    
    /**
     * 打开使用统计权限设置界面
     */
    public void openUsageAccessSettings() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * 获取前台应用信息
     * @return 包含应用名称和包名的对象
     */
    public ForegroundAppInfo getForegroundAppInfo() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || usageStatsManager == null) {
            return new ForegroundAppInfo(lastForegroundAppName, lastForegroundPackage);
        }
        
        if (!hasUsageStatsPermission()) {
            return new ForegroundAppInfo(context.getString(R.string.need_usage_stats_permission), "");
        }
        
        try {
            String foregroundPackageName = getForegroundPackageName();
            String appName = getApplicationNameFromPackage(foregroundPackageName);
            
            // 更新上次检测的应用信息
            if (foregroundPackageName != null && !foregroundPackageName.isEmpty()) {
                lastForegroundPackage = foregroundPackageName;
                lastForegroundAppName = appName;
            }
            
            return new ForegroundAppInfo(appName, foregroundPackageName);
        } catch (Exception e) {
            Log.e(TAG, "获取前台应用信息失败", e);
            return new ForegroundAppInfo(lastForegroundAppName, lastForegroundPackage);
        }
    }
    
    /**
     * 获取最近使用的应用列表
     * @param limit 限制返回的应用数量（最多10个）
     * @return 最近使用的应用列表
     */
    public List<RecentAppInfo> getRecentApps(int limit) {
        // 限制在1-10之间
        limit = Math.min(Math.max(limit, 1), MAX_RECENT_APPS);
        
        // 检查基本条件
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || usageStatsManager == null) {
            Log.w(TAG, "设备不支持使用统计API");
            return getSimpleFallbackApps(limit);
        }
        
        if (!hasUsageStatsPermission()) {
            Log.w(TAG, "没有使用统计权限");
            return getSimpleFallbackApps(limit);
        }
        
        try {
            return getSimpleRecentApps(limit);
        } catch (Exception e) {
            Log.e(TAG, "获取最近应用列表失败", e);
            return getSimpleFallbackApps(limit);
        }
    }
    
    /**
     * 简单的最近应用获取方法
     */
    private List<RecentAppInfo> getSimpleRecentApps(int limit) {
        List<RecentAppInfo> apps = new ArrayList<>();
        
        try {
            long endTime = System.currentTimeMillis();
            long startTime = endTime - (24 * 60 * 60 * 1000L); // 查询最近1天
            
            UsageEvents usageEvents = usageStatsManager.queryEvents(startTime, endTime);
            if (usageEvents == null) {
                return getSimpleFallbackApps(limit);
            }
            
            Map<String, Long> packageTimeMap = new HashMap<>();
            UsageEvents.Event event = new UsageEvents.Event();
            
            // 处理事件，只关注前台事件
            while (usageEvents.hasNextEvent()) {
                if (usageEvents.getNextEvent(event)) {
                    if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        String packageName = event.getPackageName();
                        if (isValidUserApp(packageName)) {
                            packageTimeMap.put(packageName, event.getTimeStamp());
                        }
                    }
                }
            }
            
            // 转换为应用信息列表
            for (Map.Entry<String, Long> entry : packageTimeMap.entrySet()) {
                String packageName = entry.getKey();
                String appName = getApplicationNameFromPackage(packageName);
                if (!appName.equals("未知应用") && !appName.equals(packageName)) {
                    apps.add(new RecentAppInfo(this.context, appName, packageName, entry.getValue()));
                }
            }
            
            // 按时间排序
            Collections.sort(apps, (a, b) -> Long.compare(b.getLastUsedTime(), a.getLastUsedTime()));
            
            // 限制数量
            if (apps.size() > limit) {
                apps = new ArrayList<>(apps.subList(0, limit));
            }
            
            Log.d(TAG, "获取到 " + apps.size() + " 个最近使用的应用");
            return apps;
            
        } catch (Exception e) {
            Log.e(TAG, "获取简单最近应用失败", e);
            return getSimpleFallbackApps(limit);
        }
    }
    
    /**
     * 简单的备选应用列表
     */
    private List<RecentAppInfo> getSimpleFallbackApps(int limit) {
        List<RecentAppInfo> apps = new ArrayList<>();
        long currentTime = System.currentTimeMillis();
        
        // 添加当前应用
        apps.add(new RecentAppInfo(this.context, this.context.getString(R.string.sensor_data_collector_app), this.context.getPackageName(), currentTime));
        
        // 如果有上次检测到的前台应用，也加入
        if (!lastForegroundPackage.isEmpty() && !lastForegroundPackage.equals(this.context.getPackageName())) {
            apps.add(new RecentAppInfo(this.context, lastForegroundAppName, lastForegroundPackage, currentTime - 60000));
        }
        
        return apps.subList(0, Math.min(limit, apps.size()));
    }
    
    /**
     * 检查是否为有效的用户应用（简化版）
     */
    private boolean isValidUserApp(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        
        // 过滤系统应用
        return !packageName.startsWith("com.android.") &&
               !packageName.startsWith("android.") &&
               !packageName.contains("launcher") &&
               !packageName.contains("home") &&
               !packageName.contains("systemui") &&
               !packageName.equals("com.google.android.gms");
    }
    
    /**
     * 获取当前前台应用包名
     * 使用Usage Stats API获取最近的应用
     */
    private String getForegroundPackageName() {
        if (usageStatsManager == null) {
            Log.w(TAG, "UsageStatsManager不可用");
            return context.getString(R.string.unknown_app);
        }

        try {
            // 检查权限
            if (!hasUsageStatsPermission()) {
                Log.w(TAG, "没有使用统计权限");
                return context.getString(R.string.unknown_app) + "(权限不足)";
            }

            long endTime = System.currentTimeMillis();
            long startTime = endTime - 60 * 1000; // 查看最近1分钟
            
            // 安全地获取使用事件，避免触发ContentCatcher相关问题
            UsageEvents usageEvents;
            try {
                usageEvents = usageStatsManager.queryEvents(startTime, endTime);
            } catch (SecurityException e) {
                Log.w(TAG, "查询使用事件时权限不足", e);
                return context.getString(R.string.unknown_app) + "(安全异常)";
            } catch (Exception e) {
                Log.w(TAG, "查询使用事件失败", e);
                return context.getString(R.string.unknown_app) + "(查询失败)";
            }
            
            if (usageEvents == null) {
                return lastForegroundPackage.isEmpty() ? context.getString(R.string.unknown_app) : lastForegroundPackage;
            }

            // 收集最近的事件
            List<EventData> events = new ArrayList<>();
            UsageEvents.Event event = new UsageEvents.Event();
            
            while (usageEvents.hasNextEvent()) {
                try {
                    if (usageEvents.getNextEvent(event)) {
                        // 只关注应用移到前台的事件
                        if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                            events.add(new EventData(event.getPackageName(), event.getEventType(), event.getTimeStamp()));
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "处理使用事件时出错", e);
                    break; // 跳出循环，避免无限循环
                }
            }

            if (events.isEmpty()) {
                return lastForegroundPackage.isEmpty() ? "未知应用" : lastForegroundPackage;
            }

            // 按时间戳排序，获取最新的前台应用
            Collections.sort(events, (e1, e2) -> Long.compare(e2.timeStamp, e1.timeStamp));
            
            String currentForegroundPackage = events.get(0).packageName;
            
            // 过滤掉launcher
            if (currentForegroundPackage != null && 
                !currentForegroundPackage.contains("launcher") && 
                !currentForegroundPackage.contains("home")) {
                
                lastForegroundPackage = currentForegroundPackage;
                return currentForegroundPackage;
            }
            
            return lastForegroundPackage.isEmpty() ? context.getString(R.string.unknown_app) : lastForegroundPackage;
            
        } catch (SecurityException e) {
            Log.w(TAG, "获取前台应用时权限不足", e);
            return context.getString(R.string.unknown_app) + "(权限异常)";
        } catch (Exception e) {
            Log.e(TAG, "获取前台应用信息时发生未知异常", e);
            return context.getString(R.string.unknown_app) + "(异常)";
        }
    }
    
    /**
     * 根据包名获取应用名称
     * 使用PackageManager获取应用信息
     */
    private String getApplicationNameFromPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return context.getString(R.string.unknown_app);
        }
        
        // 检查缓存
        if (packageNameToAppNameMap.containsKey(packageName)) {
            return packageNameToAppNameMap.get(packageName);
        }
        
        try {
            // 使用PackageManager获取应用信息
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
            
            // 获取应用标签(名称)
            String appName = packageManager.getApplicationLabel(applicationInfo).toString();
            
            // 保存到缓存
            packageNameToAppNameMap.put(packageName, appName);
            
            Log.d(TAG, "获取应用名称成功: " + packageName + " -> " + appName);
            return appName;
            
        } catch (PackageManager.NameNotFoundException e) {
            // 应用不存在，尝试获取卸载的应用信息
            try {
                int flags = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    flags |= PackageManager.MATCH_UNINSTALLED_PACKAGES;
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    flags |= PackageManager.GET_UNINSTALLED_PACKAGES;
                }
                
                ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, flags);
                String appName = packageManager.getApplicationLabel(applicationInfo).toString();
                
                packageNameToAppNameMap.put(packageName, appName);
                Log.d(TAG, "获取卸载应用名称成功: " + packageName + " -> " + appName);
                return appName;
                
            } catch (PackageManager.NameNotFoundException e2) {
                // 尝试从包名生成一个友好的名称
                String defaultName = packageName;
                
                if (packageName.contains(".")) {
                    String[] parts = packageName.split("\\.");
                    defaultName = parts[parts.length - 1];
                    defaultName = defaultName.substring(0, 1).toUpperCase() + 
                                (defaultName.length() > 1 ? defaultName.substring(1) : "");
                }
                
                Log.d(TAG, "无法找到应用信息: " + packageName + "，使用生成名称: " + defaultName);
                packageNameToAppNameMap.put(packageName, defaultName);
                return defaultName;
            } catch (Exception e2) {
                Log.e(TAG, "获取卸载应用信息异常", e2);
                String fallbackName = packageName;
                packageNameToAppNameMap.put(packageName, fallbackName);
                return fallbackName;
            }
        } catch (Exception e) {
            // 其他异常情况，记录日志并返回包名作为备用
            Log.e(TAG, "获取应用信息异常: " + packageName, e);
            
            String fallbackName = packageName;
            if (packageName.startsWith("android") || packageName.startsWith("com.android")) {
                fallbackName = context.getString(R.string.system_app);
            }
            
            packageNameToAppNameMap.put(packageName, fallbackName);
            return fallbackName;
        }
    }
    
    /**
     * 用于存储UsageEvents.Event信息的简单数据类
     */
    private static class EventData {
        String packageName;
        int eventType;
        long timeStamp;
        
        EventData(String packageName, int eventType, long timeStamp) {
            this.packageName = packageName;
            this.eventType = eventType;
            this.timeStamp = timeStamp;
        }
    }
    
    /**
     * 前台应用信息类
     */
    public static class ForegroundAppInfo {
        private String appName;
        private String packageName;
        
        public ForegroundAppInfo(String appName, String packageName) {
            this.appName = appName;
            this.packageName = packageName;
        }
        
        public String getAppName() {
            return appName;
        }
        
        public String getPackageName() {
            return packageName;
        }
        
        @Override
        public String toString() {
            return appName + (packageName.isEmpty() ? "" : " (" + packageName + ")");
        }
    }
    
    /**
     * 最近使用的应用信息类
     */
    public static class RecentAppInfo {
        private String appName;
        private String packageName;
        private long lastUsedTime;
        private Context context; // Added context field
        
        public RecentAppInfo(Context context, String appName, String packageName, long lastUsedTime) { // Added context parameter
            this.context = context;
            this.appName = appName;
            this.packageName = packageName;
            this.lastUsedTime = lastUsedTime;
        }
        
        public String getAppName() {
            return appName;
        }
        
        public String getPackageName() {
            return packageName;
        }
        
        public long getLastUsedTime() {
            return lastUsedTime;
        }
        
        @Override
        public String toString() {
            return appName + " (" + formatTime(lastUsedTime) + ")";
        }
        
        /**
         * 格式化时间为可读形式
         */
        private String formatTime(long timeMillis) {
            long currentTime = System.currentTimeMillis();
            long diffMinutes = (currentTime - timeMillis) / (60 * 1000);
            
            if (diffMinutes < 1) {
                return this.context.getString(R.string.time_ago_just_now); // Use this.context
            } else if (diffMinutes < 60) {
                return this.context.getString(R.string.time_ago_minutes, (int)diffMinutes); // Use this.context
            } else if (diffMinutes < 24 * 60) {
                return this.context.getString(R.string.time_ago_hours, (int)(diffMinutes / 60)); // Use this.context
            } else {
                return this.context.getString(R.string.time_ago_days, (int)(diffMinutes / (24 * 60))); // Use this.context
            }
        }
    }
    
    /**
     * 根据内存情况优化缓存
     * 当内存紧张时，尽可能地释放更多资源
     */
    @Override
    public void onTrimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            // 内存紧张，清理一些缓存
            Log.d(TAG, "内存压力: MODERATE，清理部分缓存");
            trimPackageNameCache(30); // 保留30个最近的
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // 内存更紧张，进一步清理
            Log.d(TAG, "内存压力: RUNNING_LOW，清理更多缓存");
            trimPackageNameCache(10); // 只保留10个最近的
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            // 极度紧张，清理所有缓存
            Log.d(TAG, "内存压力: COMPLETE，清理所有缓存");
            packageNameToAppNameMap.clear();
        }
    }
    
    /**
     * 系统内存极度不足时调用
     */
    @Override
    public void onLowMemory() {
        // 内存极度不足，清理所有缓存
        Log.d(TAG, "系统内存不足，清除所有缓存");
        packageNameToAppNameMap.clear();
    }
    
    /**
     * 裁剪包名缓存到指定大小
     * @param maxEntries 最大保留条目数
     */
    private void trimPackageNameCache(int maxEntries) {
        if (packageNameToAppNameMap.size() <= maxEntries) {
            return; // 如果当前缓存小于等于限制，不需要裁剪
        }
        
        try {
            Log.d(TAG, "裁剪包名缓存，从" + packageNameToAppNameMap.size() + "到" + maxEntries);
            
            // 简化裁剪逻辑：如果超过太多，则重建为一个较小的Map
            // 或者，如果需要保留部分，可以创建一个新的map并迁移数据
            if (packageNameToAppNameMap.size() > maxEntries * 2) { // 例如，如果缓存超过目标大小的两倍
                HashMap<String, String> newMap = new HashMap<>(maxEntries);
                int count = 0;
                // 简单地从旧map中取maxEntries个元素到新map，这仍然不是LRU
                for (Map.Entry<String, String> entry : packageNameToAppNameMap.entrySet()) {
                    if (count < maxEntries) {
                        newMap.put(entry.getKey(), entry.getValue());
                        count++;
                    } else {
                        break;
                    }
                }
                packageNameToAppNameMap = newMap;
            } else {
                // 如果只是略微超过，可以考虑更精细的移除策略，但目前简单处理：
                // 仅当缓存大小显著超过maxEntries时才进行大的清理
                // 否则，让它自然增长，直到下一次更激进的清理
            }
            
            // 手动通知GC可以清理不再使用的条目 - 移除
            // System.gc();
        } catch (Exception e) {
            Log.e(TAG, "裁剪缓存出错", e);
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
     * 关闭资源
     */
    public void shutdown() {
        Log.d(TAG, "开始关闭 ForegroundAppManager");
        
        // 取消注册内存回调
        if (context != null) {
            try {
                context.unregisterComponentCallbacks(this);
                Log.d(TAG, "已取消注册内存回调");
            } catch (Exception e) {
                Log.w(TAG, "取消注册内存回调失败", e);
            }
        }
        
        // 清空缓存
        if (packageNameToAppNameMap != null) {
            packageNameToAppNameMap.clear();
            packageNameToAppNameMap = null; // 明确释放引用
            Log.d(TAG, "packageNameToAppNameMap 已清空");
        }
        
        // 重置上次前台应用信息
        lastForegroundPackage = "";
        lastForegroundAppName = "未知应用";
        
        // 将单例引用设为null
        instance = null;
        
        // 关闭任何线程池（如果此类中有的话，当前没有）
        // if (appStatsExecutor != null && !appStatsExecutor.isShutdown()) {
        //     try {
        //         appStatsExecutor.shutdown();
        //         if (!appStatsExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
        //             appStatsExecutor.shutdownNow();
        //         }
        //     } catch (InterruptedException e) {
        //         appStatsExecutor.shutdownNow();
        //         Thread.currentThread().interrupt();
        //     }
        //     appStatsExecutor = null;
        //     Log.d(TAG, "appStatsExecutor 已关闭");
        // }
        
        context = null; // 释放上下文引用
        usageStatsManager = null;
        packageManager = null;
        
        Log.d(TAG, "ForegroundAppManager 已关闭");
    }
}