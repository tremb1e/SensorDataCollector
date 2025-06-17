package com.example.sensordatacollector;

import org.json.JSONObject;
import org.json.JSONException;

public class DataRecord {
    public long timestampMs;
    public String type; // "sensor"

    // 用户ID字段
    public String userId;

    // 前台应用信息
    public String foregroundAppName;
    public String foregroundPackageName;

    // Sensor event specific
    public String sensorName; // "gravity", "accelerometer", "magnetometer", "gyroscope"
    public float sensorX, sensorY, sensorZ;
    public int sensorAccuracy;

    // Constructor for sensor events
    public DataRecord(long timestampMs, String sensorName, float sensorX, float sensorY, float sensorZ, int sensorAccuracy,
                     String foregroundAppName, String foregroundPackageName, String userId) {
        this.timestampMs = timestampMs;
        this.type = "sensor";
        this.sensorName = sensorName;
        this.sensorX = sensorX;
        this.sensorY = sensorY;
        this.sensorZ = sensorZ;
        this.sensorAccuracy = sensorAccuracy;
        this.foregroundAppName = foregroundAppName;
        this.foregroundPackageName = foregroundPackageName;
        this.userId = userId;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("timestamp_ms", this.timestampMs);
            json.put("type", this.type);
            json.put("user_id", this.userId);
            
            // 添加前台应用信息
            json.put("foreground_app_name", this.foregroundAppName);
            json.put("foreground_package_name", this.foregroundPackageName);

            if ("sensor".equals(this.type)) {
                json.put("sensor_name", this.sensorName);
                JSONObject values = new JSONObject();
                values.put("x", this.sensorX);
                values.put("y", this.sensorY);
                values.put("z", this.sensorZ);
                json.put("values", values);
                json.put("accuracy", this.sensorAccuracy);
            }
        } catch (JSONException e) {
            e.printStackTrace(); // Or handle more gracefully
        }
        return json;
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}

