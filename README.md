# SensorDataCollector (传感器数据收集器)

## English Version

### Overview

SensorDataCollector is an Android application designed to collect various sensor data from the device, store it locally, and upload it to a specified server. It focuses on efficient data collection, robust storage management, and reliable network transmission.

### Features

1.  **Sensor Data Collection:**
    *   Collects data from Accelerometer, Gyroscope, and Magnetometer.
    *   Adjustable sampling rate for sensor data collection.
    *   Associates sensor data with the foreground application name and package name.
    *   Records a user-defined User ID with each data entry.

2.  **Data Storage:**
    *   Stores collected data in local files in JSONL (JSON Lines) format.
    *   Supports GZIP compression for stored files to save space (default).
    *   Automatic file rotation based on file size (configurable, default 1GB) or time interval (configurable, default 1 hour).
    *   Manages data files, allowing users to view, clean up old files, or keep a specific number of recent files.
    *   Displays current storage usage and pending file information.

3.  **Network Upload:**
    *   Uploads collected data files to a user-specified server (IP address and port).
    *   Supports uploading multiple files.
    *   Displays upload progress and status.
    *   Includes a feature to test server connectivity.
    *   Advanced options for managing uploads, such as clearing upload progress or canceling ongoing uploads.
    *   Handles network availability checks and provides warnings.

4.  **User Interface:**
    *   Displays real-time sensor data (Accelerometer, Magnetometer, Gyroscope).
    *   Shows current file path, collection status, and battery statistics.
    *   Allows users to configure server IP, port, User ID, and sampling rate.
    *   Provides buttons to start/stop data collection, initiate uploads, and manage files.
    *   Indicates service running status.
    *   Displays the current foreground application.
    *   Shows a list of recently used applications.
    *   Includes a permission request flow for accessing usage statistics (required for foreground app detection).

5.  **Background Operation:**
    *   Utilizes a foreground service to ensure continuous data collection even when the app is not in the foreground.
    *   Handles screen on/off events: pauses data collection when the screen is off to save battery and resumes when the screen is on.
    *   Can be configured to start automatically on device boot (via BootCompletedReceiver).

6.  **Permissions:**
    *   Requires permissions for Internet, Foreground Service, Post Notifications (for Android 13+), Receive Boot Completed, and Package Usage Stats.
    *   Guides the user to grant necessary permissions, especially for usage statistics.

7.  **Battery and Resource Management:**
    *   Monitors battery level and temperature.
    *   Automatically adjusts sampling rate to a lower frequency (e.g., 20ms) if battery level is low (e.g., <= 15%) to conserve power.
    *   Implements `ComponentCallbacks2` to handle low memory situations by releasing resources.
    *   Optimized thread management for background tasks to reduce resource consumption.

### Optimizations

1.  **Efficient Data Handling:**
    *   Uses a dedicated `DataManager` to centralize data processing and distribution to listeners (like `StorageManager`).
    *   Employs background threads for sensor event processing, file writing, and network operations to keep the UI responsive.
    *   Uses `LinkedBlockingQueue` with `ThreadPoolExecutor` for managing tasks, with strategies like `DiscardOldestPolicy` for sensor data processing if the queue is full, ensuring the app remains stable under high load.

2.  **Storage Optimization:**
    *   GZIP compression for data files significantly reduces storage footprint.
    *   File rotation prevents individual files from becoming excessively large, making them easier to manage and upload.
    *   Efficiently loads existing files on startup, resuming writing to the last incomplete file if it's under the size limit.

3.  **Network Optimization:**
    *   Uses OkHttp library for robust and efficient network requests.
    *   Configured with connection, read, and write timeouts.
    *   Supports retries for failed uploads.
    *   (Conceptual) Placeholder for resumable uploads by tracking uploaded bytes (though the server-side implementation for actual resume is not part of this client).
    *   Uploads are handled in a separate thread pool.

4.  **UI Responsiveness:**
    *   All long-running operations (file I/O, network) are performed off the main UI thread.
    *   UI updates are posted back to the main thread using `Handler` or `runOnUiThread`.
    *   RecyclerView for recent apps is optimized with `setHasFixedSize(true)`, a `RecycledViewPool`, and by disabling item animators to reduce memory usage and improve performance.

5.  **Resource Management & Stability:**
    *   Uses `WeakReference` for `Context` in some managers to prevent memory leaks.
    *   `SensorService` and other managers implement `ComponentCallbacks2` to react to low memory and trim memory events, releasing resources as needed.
    *   Careful management of service lifecycle and broadcast receivers.
    *   Graceful handling of permission denials and unavailable sensors.
    *   Screen state (on/off) is monitored to pause/resume sensor collection, optimizing battery usage.
    *   Background task executor in `MainActivity` for operations like file cleanup to avoid blocking UI.
    *   Dialogs are managed to prevent leaks, ensuring they are dismissed properly.

6.  **Timestamp Accuracy:**
    *   (Conceptual) Includes a `TimestampManager` and `TimestampAlignmentTest` which suggest an effort towards ensuring accurate and synchronized timestamps for collected data, although the detailed implementation of advanced synchronization techniques (like NTP or sensor fusion for timestamp correction) is not fully elaborated in the provided snippets. The `TimestampManager` aims to provide a unified timestamp for sensor events.

7.  **Code Structure and Maintainability:**
    *   Modular design with separate classes for different responsibilities (e.g., `SensorCollector`, `DataManager`, `StorageManager`, `NetworkManager`, `ForegroundAppManager`, `BatteryStatsManager`).
    *   Singleton pattern used for manager classes to ensure a single instance.
    *   Clear logging for debugging and monitoring.

## 中文版本

### 项目概述

SensorDataCollector是一款Android应用程序，旨在从设备收集各种传感器数据，将其本地存储，并上传到指定的服务器。该应用专注于高效的数据收集、健壮的存储管理和可靠的网络传输。

### 功能特性

1.  **传感器数据收集:**
    *   收集来自加速度计、陀螺仪和磁力计的数据。
    *   可调节的传感器数据收集采样率。
    *   将传感器数据与前台应用程序名称和包名相关联。
    *   记录用户定义的User ID到每条数据中。

2.  **数据存储:**
    *   以JSONL (JSON Lines)格式将收集的数据存储在本地文件中。
    *   支持对存储文件进行GZIP压缩以节省空间（默认）。
    *   基于文件大小（可配置，默认1GB）或时间间隔（可配置，默认1小时）自动进行文件轮换。
    *   管理数据文件，允许用户查看、清理旧文件或保留特定数量的最近文件。
    *   显示当前存储使用情况和待处理文件信息。

3.  **网络上传:**
    *   将收集的数据文件上传到用户指定的服务器（IP地址和端口）。
    *   支持上传多个文件。
    *   显示上传进度和状态。
    *   包含测试服务器连接的功能。
    *   提供高级上传管理选项，如清除上传进度或取消正在进行的上传。
    *   处理网络可用性检查并提供警告。

4.  **用户界面:**
    *   实时显示传感器数据（加速度计、磁力计、陀螺仪）。
    *   显示当前文件路径、收集状态和电池统计信息。
    *   允许用户配置服务器IP、端口、User ID和采样率。
    *   提供启动/停止数据收集、发起上传和管理文件的按钮。
    *   指示服务运行状态。
    *   显示当前前台应用程序。
    *   显示最近使用的应用程序列表。
    *   包含获取使用情况统计权限的请求流程（用于前台应用检测）。

5.  **后台运行:**
    *   利用前台服务确保即使应用不在前台也能持续收集数据。
    *   处理屏幕亮/灭事件：屏幕关闭时暂停数据收集以节省电池，屏幕点亮时恢复。
    *   可配置为在设备启动时自动启动（通过BootCompletedReceiver）。

6.  **权限管理:**
    *   需要网络、前台服务、发布通知（Android 13+）、开机启动和获取应用使用情况统计的权限。
    *   引导用户授予必要的权限，特别是使用情况统计权限。

7.  **电池与资源管理:**
    *   监控电池电量和温度。
    *   如果电池电量低（例如 <= 15%），自动将采样率调整到较低频率（例如20ms）以节省电量。
    *   实现 `ComponentCallbacks2` 接口以处理低内存情况并释放资源。
    *   优化的后台任务线程管理，以减少资源消耗。

### 优化点

1.  **高效数据处理:**
    *   使用专门的 `DataManager` 集中处理数据并将数据分发给监听器（如 `StorageManager`）。
    *   为传感器事件处理、文件写入和网络操作采用后台线程，以保持UI响应。
    *   使用 `LinkedBlockingQueue` 和 `ThreadPoolExecutor` 管理任务，并采用如 `DiscardOldestPolicy` 这样的策略处理传感器数据（如果队列已满），确保应用在高负载下保持稳定。

2.  **存储优化:**
    *   对数据文件进行GZIP压缩，显著减少存储占用。
    *   文件轮换机制防止单个文件变得过大，使其更易于管理和上传。
    *   启动时高效加载现有文件，如果最后一个未完成文件小于大小限制，则继续写入该文件。

3.  **网络优化:**
    *   使用OkHttp库进行健壮高效的网络请求。
    *   配置了连接、读取和写入超时。
    *   支持上传失败重试。
    *   （概念性）通过跟踪已上传字节为断点续传提供支持（尽管实际的服务器端断点续传实现不包含在此客户端中）。
    *   上传操作在单独的线程池中处理。

4.  **UI响应性:**
    *   所有长时间运行的操作（文件I/O、网络）都在非UI主线程上执行。
    *   UI更新通过 `Handler` 或 `runOnUiThread` 回到主线程执行。
    *   用于显示最近应用的RecyclerView通过 `setHasFixedSize(true)`、共享 `RecycledViewPool` 以及禁用项目动画来进行优化，以减少内存使用并提高性能。

5.  **资源管理与稳定性:**
    *   在一些管理器中使用 `WeakReference` 引用 `Context` 以防止内存泄漏。
    *   `SensorService` 和其他管理器实现 `ComponentCallbacks2` 接口，以响应低内存和内存整理事件，并根据需要释放资源。
    *   谨慎管理服务生命周期和广播接收器。
    *   优雅处理权限拒绝和传感器不可用的情况。
    *   监控屏幕状态（亮/灭）以暂停/恢复传感器收集，优化电池使用。
    *   `MainActivity` 中的后台任务执行器用于执行文件清理等操作，避免阻塞UI。
    *   妥善管理对话框以防止泄漏，确保它们被正确关闭。

6.  **时间戳准确性:**
    *   （概念性）包含 `TimestampManager` 和 `TimestampAlignmentTest`，表明项目致力于确保收集数据的准确和同步时间戳，尽管在提供的代码片段中未完全详细说明高级同步技术（如NTP或用于时间戳校正的传感器融合）的实现。`TimestampManager` 旨在为传感器事件提供统一的时间戳。

7.  **代码结构与可维护性:**
    *   模块化设计，不同职责由不同类处理（例如 `SensorCollector`, `DataManager`, `StorageManager`, `NetworkManager`, `ForegroundAppManager`, `BatteryStatsManager`）。
    *   管理器类使用单例模式确保单一实例。
    *   清晰的日志记录，便于调试和监控。
