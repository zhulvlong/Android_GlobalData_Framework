package com.oem.globaldata.contracts;

import com.oem.globaldata.client.Codecs;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.ipc.Wire;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App1/App2 共用的数据地址表。方法名按 Store＋数据格式＋业务含义排列，
 * 例如 memoryIntOperationCount、fileObjectDeviceConfig 和 databaseMapStringIntModuleRetryCounts。
 * 读写双方使用一致的 Store、Scope、owner、Key 名和 Codec。
 */
public final class DeviceDataKeys {
    /** 地址定义由静态工厂方法提供，不创建本类对象。 */
    private DeviceDataKeys() {}
    /** 普通共享数据的 owner 包名；服务端仍会以 Binder UID 验证实际调用身份。 */
    public static final String APP1_PACKAGE = "com.oem.globaldata.app1";
    /** 授权示例中的接收方包名，用于 App1 授予 App2 权限。 */
    public static final String APP2_PACKAGE = "com.oem.globaldata.app2";

    /** 两端构建变体可能不同，设备级公开案例必须固定到同一个 DEVICE_DE Key。 */
    public static DataKey<String> fileStringDeviceAudienceDemo() {
        return DataKey.of(Wire.FILE, Wire.DEVICE_DE, APP1_PACKAGE,
                "demo_audience_device_all", Codecs.STRING);
    }

    /** 本用户所有 App 读取或监听的内存示例。 */
    public static DataKey<String> memoryStringUserAllAudienceDemo() {
        return DataKey.of(Wire.MEMORY, Wire.USER_CE, APP1_PACKAGE,
                "demo_audience_user_all", Codecs.STRING);
    }

    /** 本用户预装 App 读取或监听的数据库示例。 */
    public static DataKey<String> databaseStringUserPreinstalledAudienceDemo() {
        return DataKey.of(Wire.DATABASE, Wire.USER_CE, APP1_PACKAGE,
                "demo_audience_user_preinstalled", Codecs.STRING);
    }

    /** 本用户同签名 App 读取或监听的文件示例。 */
    public static DataKey<String> fileStringUserSameSignatureAudienceDemo() {
        return DataKey.of(Wire.FILE, Wire.USER_CE, APP1_PACKAGE,
                "demo_audience_user_same_signature", Codecs.STRING);
    }

    /** 内存中的操作计数；原 9999 演示使用此地址，服务重启后值消失。 */
    public static DataKey<Integer> memoryIntOperationCount(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "operation_count", Codecs.INT);
    }
    /** 文件中的持久化操作计数。 */
    public static DataKey<Integer> fileIntOperationCount(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "operation_count", Codecs.INT);
    }
    /** SQLite 中的持久化操作计数。 */
    public static DataKey<Integer> databaseIntOperationCount(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "operation_count", Codecs.INT);
    }

    /** 内存中的累计传输字节数；long 可表示超过 32 位范围的计数。 */
    public static DataKey<Long> memoryLongTotalTransferBytes(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "total_transfer_bytes", Codecs.LONG);
    }
    /** 文件中持久化的累计传输字节数。 */
    public static DataKey<Long> fileLongTotalTransferBytes(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "total_transfer_bytes", Codecs.LONG);
    }
    /** 数据库中持久化的累计传输字节数。 */
    public static DataKey<Long> databaseLongTotalTransferBytes(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "total_transfer_bytes", Codecs.LONG);
    }

    /** 内存中的下载进度比例；float 演示单精度 IEEE 754 值。 */
    public static DataKey<Float> memoryFloatDownloadProgressRatio(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "download_progress_ratio", Codecs.FLOAT);
    }
    /** 文件中持久化的下载进度比例。 */
    public static DataKey<Float> fileFloatDownloadProgressRatio(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "download_progress_ratio", Codecs.FLOAT);
    }
    /** 数据库中持久化的下载进度比例。 */
    public static DataKey<Float> databaseFloatDownloadProgressRatio(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "download_progress_ratio", Codecs.FLOAT);
    }

    /** 内存中的传感器摄氏温度；double 演示双精度 IEEE 754 值。 */
    public static DataKey<Double> memoryDoubleSensorTemperatureCelsius(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "sensor_temperature_celsius", Codecs.DOUBLE);
    }
    /** 文件中持久化的传感器摄氏温度。 */
    public static DataKey<Double> fileDoubleSensorTemperatureCelsius(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "sensor_temperature_celsius", Codecs.DOUBLE);
    }
    /** 数据库中持久化的传感器摄氏温度。 */
    public static DataKey<Double> databaseDoubleSensorTemperatureCelsius(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "sensor_temperature_celsius", Codecs.DOUBLE);
    }

    /** 内存中的通知开关。 */
    public static DataKey<Boolean> memoryBooleanNotificationsEnabled(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "notifications_enabled", Codecs.BOOLEAN);
    }
    /** 文件中的通知开关。 */
    public static DataKey<Boolean> fileBooleanNotificationsEnabled(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "notifications_enabled", Codecs.BOOLEAN);
    }
    /** SQLite 中的通知开关。 */
    public static DataKey<Boolean> databaseBooleanNotificationsEnabled(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "notifications_enabled", Codecs.BOOLEAN);
    }

    /** 内存中的欢迎消息。 */
    public static DataKey<String> memoryStringWelcomeMessage(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "welcome_message", Codecs.STRING);
    }
    /** 文件中的欢迎消息。 */
    public static DataKey<String> fileStringWelcomeMessage(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "welcome_message", Codecs.STRING);
    }
    /** SQLite 中的欢迎消息。 */
    public static DataKey<String> databaseStringWelcomeMessage(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "welcome_message", Codecs.STRING);
    }

    /** 内存中的业务配置对象；Codec 只在 App 内执行。 */
    public static DataKey<DeviceConfig> memoryObjectDeviceConfig(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "device_config", DeviceConfigCodec.INSTANCE);
    }
    /** 文件中的业务配置对象。 */
    public static DataKey<DeviceConfig> fileObjectDeviceConfig(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "device_config", DeviceConfigCodec.INSTANCE);
    }
    /** SQLite 中的业务配置对象。 */
    public static DataKey<DeviceConfig> databaseObjectDeviceConfig(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "device_config", DeviceConfigCodec.INSTANCE);
    }

    /** 内存中的最近标签列表。 */
    public static DataKey<List<String>> memoryListStringRecentTags(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "recent_tags", DeviceCollections.STRING_LIST);
    }
    /** 文件中的最近标签列表。 */
    public static DataKey<List<String>> fileListStringRecentTags(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "recent_tags", DeviceCollections.STRING_LIST);
    }
    /** SQLite 中的最近标签列表。 */
    public static DataKey<List<String>> databaseListStringRecentTags(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "recent_tags", DeviceCollections.STRING_LIST);
    }

    /** 内存中的显示选项映射。 */
    public static DataKey<Map<String, String>> memoryMapStringStringDisplayOptions(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "display_options", DeviceCollections.STRING_MAP);
    }
    /** 文件中的显示选项映射。 */
    public static DataKey<Map<String, String>> fileMapStringStringDisplayOptions(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "display_options", DeviceCollections.STRING_MAP);
    }
    /** SQLite 中的显示选项映射。 */
    public static DataKey<Map<String, String>> databaseMapStringStringDisplayOptions(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "display_options", DeviceCollections.STRING_MAP);
    }

    /** 内存中的启用功能标记集合；元素唯一，服务重启后需要重新写入。 */
    public static DataKey<Set<String>> memorySetStringEnabledFeatureFlags(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "enabled_feature_flags", DeviceCollections.STRING_SET);
    }
    /** 文件中持久化的启用功能标记集合。 */
    public static DataKey<Set<String>> fileSetStringEnabledFeatureFlags(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "enabled_feature_flags", DeviceCollections.STRING_SET);
    }
    /** 数据库中持久化的启用功能标记集合。 */
    public static DataKey<Set<String>> databaseSetStringEnabledFeatureFlags(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "enabled_feature_flags", DeviceCollections.STRING_SET);
    }

    /** MEMORY 中的最近错误码列表；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<List<Integer>> memoryListIntRecentErrorCodes(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "recent_error_codes", DeviceCollections.INT_LIST);
    }

    /** FILE 中的最近错误码列表；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<List<Integer>> fileListIntRecentErrorCodes(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "recent_error_codes", DeviceCollections.INT_LIST);
    }

    /** DATABASE 中的最近错误码列表；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<List<Integer>> databaseListIntRecentErrorCodes(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "recent_error_codes", DeviceCollections.INT_LIST);
    }

    /** MEMORY 中的模块重试次数映射；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Map<String, Integer>> memoryMapStringIntModuleRetryCounts(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "module_retry_counts", DeviceCollections.STRING_INT_MAP);
    }

    /** FILE 中的模块重试次数映射；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Map<String, Integer>> fileMapStringIntModuleRetryCounts(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "module_retry_counts", DeviceCollections.STRING_INT_MAP);
    }

    /** DATABASE 中的模块重试次数映射；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Map<String, Integer>> databaseMapStringIntModuleRetryCounts(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "module_retry_counts", DeviceCollections.STRING_INT_MAP);
    }

    /** MEMORY 中的支持的通道编号集合；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Set<Integer>> memorySetIntSupportedChannelIds(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "supported_channel_ids", DeviceCollections.INT_SET);
    }

    /** FILE 中的支持的通道编号集合；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Set<Integer>> fileSetIntSupportedChannelIds(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "supported_channel_ids", DeviceCollections.INT_SET);
    }

    /** DATABASE 中的支持的通道编号集合；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Set<Integer>> databaseSetIntSupportedChannelIds(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "supported_channel_ids", DeviceCollections.INT_SET);
    }

    /** MEMORY 中的传输检查点字节数列表；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<List<Long>> memoryListLongTransferCheckpointsBytes(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "transfer_checkpoints_bytes", DeviceCollections.LONG_LIST);
    }

    /** FILE 中的传输检查点字节数列表；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<List<Long>> fileListLongTransferCheckpointsBytes(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "transfer_checkpoints_bytes", DeviceCollections.LONG_LIST);
    }

    /** DATABASE 中的传输检查点字节数列表；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<List<Long>> databaseListLongTransferCheckpointsBytes(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "transfer_checkpoints_bytes", DeviceCollections.LONG_LIST);
    }

    /** MEMORY 中的设备累计传输字节数映射；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Map<String, Long>> memoryMapStringLongDeviceTransferTotalsBytes(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "device_transfer_totals_bytes", DeviceCollections.STRING_LONG_MAP);
    }

    /** FILE 中的设备累计传输字节数映射；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Map<String, Long>> fileMapStringLongDeviceTransferTotalsBytes(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "device_transfer_totals_bytes", DeviceCollections.STRING_LONG_MAP);
    }

    /** DATABASE 中的设备累计传输字节数映射；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Map<String, Long>> databaseMapStringLongDeviceTransferTotalsBytes(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "device_transfer_totals_bytes", DeviceCollections.STRING_LONG_MAP);
    }

    /** MEMORY 中的活跃会话编号集合；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Set<Long>> memorySetLongActiveSessionIds(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "active_session_ids", DeviceCollections.LONG_SET);
    }

    /** FILE 中的活跃会话编号集合；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Set<Long>> fileSetLongActiveSessionIds(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "active_session_ids", DeviceCollections.LONG_SET);
    }

    /** DATABASE 中的活跃会话编号集合；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Set<Long>> databaseSetLongActiveSessionIds(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "active_session_ids", DeviceCollections.LONG_SET);
    }

    /** MEMORY 中的下载进度样本列表；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<List<Float>> memoryListFloatDownloadProgressSamples(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "download_progress_samples", DeviceCollections.FLOAT_LIST);
    }

    /** FILE 中的下载进度样本列表；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<List<Float>> fileListFloatDownloadProgressSamples(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "download_progress_samples", DeviceCollections.FLOAT_LIST);
    }

    /** DATABASE 中的下载进度样本列表；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<List<Float>> databaseListFloatDownloadProgressSamples(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "download_progress_samples", DeviceCollections.FLOAT_LIST);
    }

    /** MEMORY 中的功能权重比例映射；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Map<String, Float>> memoryMapStringFloatFeatureWeightRatios(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "feature_weight_ratios", DeviceCollections.STRING_FLOAT_MAP);
    }

    /** FILE 中的功能权重比例映射；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Map<String, Float>> fileMapStringFloatFeatureWeightRatios(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "feature_weight_ratios", DeviceCollections.STRING_FLOAT_MAP);
    }

    /** DATABASE 中的功能权重比例映射；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Map<String, Float>> databaseMapStringFloatFeatureWeightRatios(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "feature_weight_ratios", DeviceCollections.STRING_FLOAT_MAP);
    }

    /** MEMORY 中的阈值比例集合；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Set<Float>> memorySetFloatThresholdRatios(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "threshold_ratios", DeviceCollections.FLOAT_SET);
    }

    /** FILE 中的阈值比例集合；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Set<Float>> fileSetFloatThresholdRatios(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "threshold_ratios", DeviceCollections.FLOAT_SET);
    }

    /** DATABASE 中的阈值比例集合；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Set<Float>> databaseSetFloatThresholdRatios(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "threshold_ratios", DeviceCollections.FLOAT_SET);
    }

    /** MEMORY 中的摄氏温度样本列表；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<List<Double>> memoryListDoubleTemperatureSamplesCelsius(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "temperature_samples_celsius", DeviceCollections.DOUBLE_LIST);
    }

    /** FILE 中的摄氏温度样本列表；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<List<Double>> fileListDoubleTemperatureSamplesCelsius(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "temperature_samples_celsius", DeviceCollections.DOUBLE_LIST);
    }

    /** DATABASE 中的摄氏温度样本列表；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<List<Double>> databaseListDoubleTemperatureSamplesCelsius(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "temperature_samples_celsius", DeviceCollections.DOUBLE_LIST);
    }

    /** MEMORY 中的传感器校准偏移量映射；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Map<String, Double>> memoryMapStringDoubleSensorCalibrationOffsets(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "sensor_calibration_offsets", DeviceCollections.STRING_DOUBLE_MAP);
    }

    /** FILE 中的传感器校准偏移量映射；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Map<String, Double>> fileMapStringDoubleSensorCalibrationOffsets(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "sensor_calibration_offsets", DeviceCollections.STRING_DOUBLE_MAP);
    }

    /** DATABASE 中的传感器校准偏移量映射；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Map<String, Double>> databaseMapStringDoubleSensorCalibrationOffsets(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "sensor_calibration_offsets", DeviceCollections.STRING_DOUBLE_MAP);
    }

    /** MEMORY 中的温度告警阈值集合；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Set<Double>> memorySetDoubleTemperatureAlarmThresholdsCelsius(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "temperature_alarm_thresholds_celsius", DeviceCollections.DOUBLE_SET);
    }

    /** FILE 中的温度告警阈值集合；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Set<Double>> fileSetDoubleTemperatureAlarmThresholdsCelsius(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "temperature_alarm_thresholds_celsius", DeviceCollections.DOUBLE_SET);
    }

    /** DATABASE 中的温度告警阈值集合；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Set<Double>> databaseSetDoubleTemperatureAlarmThresholdsCelsius(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "temperature_alarm_thresholds_celsius", DeviceCollections.DOUBLE_SET);
    }

    /** MEMORY 中的最近健康检查结果列表；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<List<Boolean>> memoryListBooleanRecentHealthChecks(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "recent_health_checks", DeviceCollections.BOOLEAN_LIST);
    }

    /** FILE 中的最近健康检查结果列表；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<List<Boolean>> fileListBooleanRecentHealthChecks(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "recent_health_checks", DeviceCollections.BOOLEAN_LIST);
    }

    /** DATABASE 中的最近健康检查结果列表；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<List<Boolean>> databaseListBooleanRecentHealthChecks(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "recent_health_checks", DeviceCollections.BOOLEAN_LIST);
    }

    /** MEMORY 中的模块启用状态映射；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Map<String, Boolean>> memoryMapStringBooleanModuleEnabledStates(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "module_enabled_states", DeviceCollections.STRING_BOOLEAN_MAP);
    }

    /** FILE 中的模块启用状态映射；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Map<String, Boolean>> fileMapStringBooleanModuleEnabledStates(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "module_enabled_states", DeviceCollections.STRING_BOOLEAN_MAP);
    }

    /** DATABASE 中的模块启用状态映射；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Map<String, Boolean>> databaseMapStringBooleanModuleEnabledStates(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "module_enabled_states", DeviceCollections.STRING_BOOLEAN_MAP);
    }

    /** MEMORY 中的已观察到的布尔状态集合；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Set<Boolean>> memorySetBooleanObservedBinaryStates(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "observed_binary_states", DeviceCollections.BOOLEAN_SET);
    }

    /** FILE 中的已观察到的布尔状态集合；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Set<Boolean>> fileSetBooleanObservedBinaryStates(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "observed_binary_states", DeviceCollections.BOOLEAN_SET);
    }

    /** DATABASE 中的已观察到的布尔状态集合；独立 Key 避免与其他元素类型误读。 */
    public static DataKey<Set<Boolean>> databaseSetBooleanObservedBinaryStates(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "observed_binary_states", DeviceCollections.BOOLEAN_SET);
    }

    /** 内存中的原始诊断字节；App1/App2 使用同一个 BYTES Codec。 */
    public static DataKey<byte[]> memoryBytesDiagnosticBytes(boolean system) {
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "diagnostic_bytes", Codecs.BYTES);
    }
    /** 文件中的原始诊断字节，与其他 Store 的同名 Key 隔离。 */
    public static DataKey<byte[]> fileBytesDiagnosticBytes(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "diagnostic_bytes", Codecs.BYTES);
    }
    /** SQLite 中的原始诊断字节。 */
    public static DataKey<byte[]> databaseBytesDiagnosticBytes(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "diagnostic_bytes", Codecs.BYTES);
    }

    /**
     * 构造模式整数的地址，用于演示不同 scope 下的 Memory Store。
     *
     * @param system true 选择受信任平台身份可用的 DEVICE_DE/sys.*；false 选择普通 App 的 USER_CE
     * @return mode 的类型安全地址
     */
    public static DataKey<Integer> memoryIntMode(boolean system) {
        // 同名 key 在不同 scope 下属于不同逻辑数据。
        return DataKey.of(Wire.MEMORY, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE, "mode", Codecs.INT);
    }

    /**
     * 构造文件后端的设备配置地址；与数据库后端的 config 不混用。
     *
     * @param system true 为 DEVICE_DE，false 为 USER_CE
     * @return File Store 中 config 的地址及 Codec
     */
    public static DataKey<DeviceConfig> fileObjectConfig(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "config", DeviceConfigCodec.INSTANCE);
    }

    /**
     * 构造 SQLite 后端的设备配置地址，证明 Store 类型也参与数据隔离。
     *
     * @param system true 为 DEVICE_DE，false 为 USER_CE
     * @return Database Store 中 config 的地址及 Codec
     */
    public static DataKey<DeviceConfig> databaseObjectConfig(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "config", DeviceConfigCodec.INSTANCE);
    }

    /**
     * 构造 SQLite 中字符串列表的地址，示范可为集合对象指定独立 Codec。
     *
     * @param system true 为 DEVICE_DE，false 为 USER_CE
     * @return tags 的类型安全地址
     */
    public static DataKey<List<String>> databaseListStringTags(boolean system) {
        return DataKey.of(Wire.DATABASE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "tags", DeviceCollections.STRING_LIST);
    }

    /**
     * 构造文件后端中字符串映射的地址。
     *
     * @param system true 为 DEVICE_DE，false 为 USER_CE
     * @return options 的类型安全地址
     */
    public static DataKey<Map<String, String>> fileMapStringStringOptions(boolean system) {
        return DataKey.of(Wire.FILE, system ? Wire.DEVICE_DE : Wire.USER_CE,
                APP1_PACKAGE,
                "options", DeviceCollections.STRING_MAP);
    }
}
