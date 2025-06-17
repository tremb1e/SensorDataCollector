package com.example.sensordatacollector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机完成广播接收器，用于在设备启动时自动启动传感器服务
 */
public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "BootCompletedReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "收到广播: " + action);
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
            (Intent.ACTION_PACKAGE_REPLACED.equals(action) && 
             context.getPackageName().equals(intent.getDataString()))) {
            
            Log.i(TAG, "设备启动完成或应用更新，准备启动传感器服务");
            
            // 添加安全检查，确保不是恶意调用
            if (isValidBootContext(context)) {
                // 创建启动服务的意图
                Intent serviceIntent = new Intent(context, SensorService.class);
                
                // 使用适当的方式启动服务，基于Android版本
                startServiceBasedOnAndroidVersion(context, serviceIntent);
                
                Log.i(TAG, "传感器服务已在设备启动时自动启动");
            } else {
                Log.w(TAG, "无效的启动上下文，跳过服务启动");
            }
        }
    }
    
    /**
     * 验证启动上下文是否有效，防止恶意调用
     */
    private boolean isValidBootContext(Context context) {
        try {
            // 检查是否是应用自己的包名
            String packageName = context.getPackageName();
            if (packageName == null || !packageName.contains("sensordatacollector")) {
                return false;
            }
            
            // 检查应用是否已安装且可用
            android.content.pm.PackageManager pm = context.getPackageManager();
            android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return appInfo != null && appInfo.enabled;
            
        } catch (Exception e) {
            Log.w(TAG, "验证启动上下文失败", e);
            return false;
        }
    }
    
    /**
     * 根据Android版本启动服务
     */
    private void startServiceBasedOnAndroidVersion(Context context, Intent serviceIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0及以上必须使用startForegroundService
                context.startForegroundService(serviceIntent);
                Log.d(TAG, "使用startForegroundService启动服务");
            } else {
                // Android 8.0以下可以直接启动服务
                context.startService(serviceIntent);
                Log.d(TAG, "使用startService启动服务");
            }
        } catch (Exception e) {
            Log.e(TAG, "启动服务失败", e);
        }
    }
}
