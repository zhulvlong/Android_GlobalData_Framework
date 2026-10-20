package com.oem.globaldata.contracts;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.oem.globaldata.client.Codecs;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.client.ValueCodec;
import com.oem.globaldata.ipc.Wire;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** 新增的每一种类型化集合格式都单独验证，而不是只测试一个通用生成器。 */
public final class CollectionContractsTest {
    private static <T> void roundTrip(ValueCodec<T> codec, T value, T empty) throws Exception {
        assertEquals(1, codec.schemaVersion());
        assertEquals("length-prefixed-be-v1", codec.encoding());
        assertEquals(value, codec.decode(codec.encode(value), 1));
        assertEquals(empty, codec.decode(codec.encode(empty), 1));
        byte[] encoded = codec.encode(value);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(encoded, 2));
        assertThrows(Exception.class, () -> codec.decode(new byte[]{0, 0, 0}, 1));
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(trailing, 1));
        // 每个样例都非空；把首个元素的长度改成超出剩余载荷的值。
        byte[] invalidLength = Arrays.copyOf(encoded, encoded.length);
        invalidLength[4] = 0x7f;
        invalidLength[5] = (byte) 0xff;
        invalidLength[6] = (byte) 0xff;
        invalidLength[7] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(invalidLength, 1));
    }

    private static <T> Map<String, T> map(T first, T second) {
        Map<String, T> out = new LinkedHashMap<>();
        out.put("a", first);
        out.put("b", second);
        return out;
    }

    private static <T> Set<T> set(T first, T second) {
        return new LinkedHashSet<>(Arrays.asList(first, second));
    }

    @Test public void everyNewCodecRoundTripsAndRejectsWrongVersionOrFrame() throws Exception {
        roundTrip(DeviceCollections.INT_LIST, Arrays.asList(404, 500), Collections.emptyList());
        roundTrip(DeviceCollections.LONG_LIST, Arrays.asList(4294967296L, Long.MAX_VALUE), Collections.emptyList());
        roundTrip(DeviceCollections.FLOAT_LIST, Arrays.asList(Float.NaN, Float.NEGATIVE_INFINITY), Collections.emptyList());
        roundTrip(DeviceCollections.DOUBLE_LIST, Arrays.asList(-0.0d, Double.POSITIVE_INFINITY), Collections.emptyList());
        roundTrip(DeviceCollections.BOOLEAN_LIST, Arrays.asList(true, false), Collections.emptyList());

        roundTrip(DeviceCollections.STRING_INT_MAP, map(1, 2), Collections.emptyMap());
        roundTrip(DeviceCollections.STRING_LONG_MAP, map(1L, 2L), Collections.emptyMap());
        roundTrip(DeviceCollections.STRING_FLOAT_MAP, map(0.25f, Float.NaN), Collections.emptyMap());
        roundTrip(DeviceCollections.STRING_DOUBLE_MAP, map(-0.0d, Double.POSITIVE_INFINITY), Collections.emptyMap());
        roundTrip(DeviceCollections.STRING_BOOLEAN_MAP, map(true, false), Collections.emptyMap());

        roundTrip(DeviceCollections.INT_SET, set(1, 2), Collections.emptySet());
        roundTrip(DeviceCollections.LONG_SET, set(1L, 2L), Collections.emptySet());
        roundTrip(DeviceCollections.FLOAT_SET, set(-0.0f, 0.0f), Collections.emptySet());
        roundTrip(DeviceCollections.DOUBLE_SET, set(Double.NaN, Double.NEGATIVE_INFINITY), Collections.emptySet());
        roundTrip(DeviceCollections.BOOLEAN_SET, set(true, false), Collections.emptySet());
    }

    @Test public void mapsAndSetsEncodeDeterministically() throws Exception {
        assertArrayEquals(DeviceCollections.INT_SET.encode(set(1, 2)),
                DeviceCollections.INT_SET.encode(set(2, 1)));
        Map<String, Integer> reversed = new LinkedHashMap<>();
        reversed.put("b", 2); reversed.put("a", 1);
        assertArrayEquals(DeviceCollections.STRING_INT_MAP.encode(map(1, 2)),
                DeviceCollections.STRING_INT_MAP.encode(reversed));
        float floatNan = Float.intBitsToFloat(0x7fc01234);
        float result = DeviceCollections.FLOAT_LIST.decode(
                DeviceCollections.FLOAT_LIST.encode(Collections.singletonList(floatNan)), 1).get(0);
        assertEquals(Float.floatToRawIntBits(floatNan), Float.floatToRawIntBits(result));
        double signedZero = DeviceCollections.DOUBLE_LIST.decode(
                DeviceCollections.DOUBLE_LIST.encode(Collections.singletonList(-0.0d)), 1).get(0);
        assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits(signedZero));
    }

    private static byte[] framed(byte[]... frames) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(frames.length);
        for (byte[] frame : frames) { out.writeInt(frame.length); out.write(frame); }
        return bytes.toByteArray();
    }

    @Test public void malformedAndDuplicateElementsAreRejected() throws Exception {
        byte[] one = Codecs.INT.encode(1);
        assertThrows(IllegalArgumentException.class,
                () -> DeviceCollections.INT_SET.decode(framed(one, one), 1));
        assertThrows(IllegalArgumentException.class,
                () -> DeviceCollections.INT_LIST.decode(new byte[]{0, 0, 0, 1, 0, 0, 1, 0}, 1));
        assertThrows(IllegalArgumentException.class,
                () -> DeviceCollections.BOOLEAN_LIST.decode(framed(new byte[]{2}), 1));
        byte[] key = "same".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(2);
        for (int i = 0; i < 2; i++) {
            out.writeInt(key.length); out.write(key);
            out.writeInt(one.length); out.write(one);
        }
        assertThrows(IllegalArgumentException.class,
                () -> DeviceCollections.STRING_INT_MAP.decode(bytes.toByteArray(), 1));
        duplicateSet(DeviceCollections.LONG_SET, Codecs.LONG.encode(1L));
        duplicateSet(DeviceCollections.FLOAT_SET, Codecs.FLOAT.encode(Float.NaN));
        duplicateSet(DeviceCollections.DOUBLE_SET, Codecs.DOUBLE.encode(-0.0d));
        duplicateSet(DeviceCollections.BOOLEAN_SET, Codecs.BOOLEAN.encode(true));
        duplicateMap(DeviceCollections.STRING_LONG_MAP, Codecs.LONG.encode(1L));
        duplicateMap(DeviceCollections.STRING_FLOAT_MAP, Codecs.FLOAT.encode(1.0f));
        duplicateMap(DeviceCollections.STRING_DOUBLE_MAP, Codecs.DOUBLE.encode(1.0d));
        duplicateMap(DeviceCollections.STRING_BOOLEAN_MAP, Codecs.BOOLEAN.encode(true));
    }

    private static void duplicateSet(ValueCodec<?> codec, byte[] element) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(framed(element, element), 1));
    }

    private static void duplicateMap(ValueCodec<?> codec, byte[] value) throws Exception {
        byte[] key = "same".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(2);
        for (int i = 0; i < 2; i++) {
            out.writeInt(key.length); out.write(key);
            out.writeInt(value.length); out.write(value);
        }
        assertThrows(IllegalArgumentException.class, () -> codec.decode(bytes.toByteArray(), 1));
    }

    @Test public void fortyFiveNewAddressesHaveExpectedStoreScopeAndCodec() throws Exception {
        String[] suffixes = {"ListIntRecentErrorCodes", "MapStringIntModuleRetryCounts", "SetIntSupportedChannelIds",
                "ListLongTransferCheckpointsBytes", "MapStringLongDeviceTransferTotalsBytes", "SetLongActiveSessionIds",
                "ListFloatDownloadProgressSamples", "MapStringFloatFeatureWeightRatios", "SetFloatThresholdRatios",
                "ListDoubleTemperatureSamplesCelsius", "MapStringDoubleSensorCalibrationOffsets", "SetDoubleTemperatureAlarmThresholdsCelsius",
                "ListBooleanRecentHealthChecks", "MapStringBooleanModuleEnabledStates", "SetBooleanObservedBinaryStates"};
        String[] keys = {"recent_error_codes", "module_retry_counts", "supported_channel_ids",
                "transfer_checkpoints_bytes", "device_transfer_totals_bytes", "active_session_ids",
                "download_progress_samples", "feature_weight_ratios", "threshold_ratios",
                "temperature_samples_celsius", "sensor_calibration_offsets", "temperature_alarm_thresholds_celsius",
                "recent_health_checks", "module_enabled_states", "observed_binary_states"};
        ValueCodec<?>[] codecs = {DeviceCollections.INT_LIST, DeviceCollections.STRING_INT_MAP, DeviceCollections.INT_SET,
                DeviceCollections.LONG_LIST, DeviceCollections.STRING_LONG_MAP, DeviceCollections.LONG_SET,
                DeviceCollections.FLOAT_LIST, DeviceCollections.STRING_FLOAT_MAP, DeviceCollections.FLOAT_SET,
                DeviceCollections.DOUBLE_LIST, DeviceCollections.STRING_DOUBLE_MAP, DeviceCollections.DOUBLE_SET,
                DeviceCollections.BOOLEAN_LIST, DeviceCollections.STRING_BOOLEAN_MAP, DeviceCollections.BOOLEAN_SET};
        String[] prefixes = {"memory", "file", "database"};
        int[] stores = {Wire.MEMORY, Wire.FILE, Wire.DATABASE};
        Set<String> addresses = new HashSet<>();
        Set<String> schemas = new HashSet<>(Arrays.asList(
                DeviceCollections.STRING_LIST.schemaId(),
                DeviceCollections.STRING_MAP.schemaId(),
                DeviceCollections.STRING_SET.schemaId()));
        for (int type = 0; type < suffixes.length; type++) {
            assertTrue(schemas.add(codecs[type].schemaId()));
            for (int store = 0; store < stores.length; store++) {
                Method method = DeviceDataKeys.class.getMethod(prefixes[store] + suffixes[type], boolean.class);
                DataKey<?> normal = (DataKey<?>) method.invoke(null, false);
                DataKey<?> platform = (DataKey<?>) method.invoke(null, true);
                assertEquals(stores[store], normal.store);
                assertEquals(Wire.USER_CE, normal.scope);
                assertEquals(Wire.DEVICE_DE, platform.scope);
                assertEquals(DeviceDataKeys.APP1_PACKAGE, normal.ownerPackage);
                assertEquals(DeviceDataKeys.SHARED_NAMESPACE, normal.namespace);
                assertEquals(DeviceDataKeys.SYSTEM_NAMESPACE, platform.namespace);
                assertEquals(keys[type], normal.key);
                assertEquals(normal.key, platform.key);
                assertSame(codecs[type], normal.codec);
                assertSame(codecs[type], platform.codec);
                assertTrue(addresses.add(normal.store + ":" + normal.key));
            }
        }
        assertEquals(45, addresses.size());
        assertEquals("recent_tags", DeviceDataKeys.memoryListStringRecentTags(false).key);
        assertEquals("com.oem.device.string-list", DeviceCollections.STRING_LIST.schemaId());
        assertEquals("display_options", DeviceDataKeys.fileMapStringStringDisplayOptions(false).key);
        assertEquals("com.oem.device.string-map", DeviceCollections.STRING_MAP.schemaId());
        assertEquals("enabled_feature_flags", DeviceDataKeys.databaseSetStringEnabledFeatureFlags(false).key);
        assertEquals("com.oem.device.string-set", DeviceCollections.STRING_SET.schemaId());
    }
}
