package com.oem.globaldata.contracts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.oem.globaldata.client.Codecs;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.client.EventTopic;
import com.oem.globaldata.client.ValueCodec;
import com.oem.globaldata.ipc.Wire;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class ContractsTest {
    @Test public void allDemoKeysHaveDistinctSemanticAddressesAndExpectedScopes() {
        DataKey<?>[] publicKeys = {
                DeviceDataKeys.memoryIntOperationCount(false), DeviceDataKeys.memoryBooleanNotificationsEnabled(false),
                DeviceDataKeys.memoryStringWelcomeMessage(false), DeviceDataKeys.memoryObjectDeviceConfig(false),
                DeviceDataKeys.memoryListStringRecentTags(false), DeviceDataKeys.memoryMapStringStringDisplayOptions(false),
                DeviceDataKeys.fileIntOperationCount(false), DeviceDataKeys.fileBooleanNotificationsEnabled(false),
                DeviceDataKeys.fileStringWelcomeMessage(false), DeviceDataKeys.fileObjectDeviceConfig(false),
                DeviceDataKeys.fileListStringRecentTags(false), DeviceDataKeys.fileMapStringStringDisplayOptions(false),
                DeviceDataKeys.databaseIntOperationCount(false), DeviceDataKeys.databaseBooleanNotificationsEnabled(false),
                DeviceDataKeys.databaseStringWelcomeMessage(false), DeviceDataKeys.databaseObjectDeviceConfig(false),
                DeviceDataKeys.databaseListStringRecentTags(false), DeviceDataKeys.databaseMapStringStringDisplayOptions(false)
        };
        DataKey<?>[] platformKeys = {
                DeviceDataKeys.memoryIntOperationCount(true), DeviceDataKeys.memoryBooleanNotificationsEnabled(true),
                DeviceDataKeys.memoryStringWelcomeMessage(true), DeviceDataKeys.memoryObjectDeviceConfig(true),
                DeviceDataKeys.memoryListStringRecentTags(true), DeviceDataKeys.memoryMapStringStringDisplayOptions(true),
                DeviceDataKeys.fileIntOperationCount(true), DeviceDataKeys.fileBooleanNotificationsEnabled(true),
                DeviceDataKeys.fileStringWelcomeMessage(true), DeviceDataKeys.fileObjectDeviceConfig(true),
                DeviceDataKeys.fileListStringRecentTags(true), DeviceDataKeys.fileMapStringStringDisplayOptions(true),
                DeviceDataKeys.databaseIntOperationCount(true), DeviceDataKeys.databaseBooleanNotificationsEnabled(true),
                DeviceDataKeys.databaseStringWelcomeMessage(true), DeviceDataKeys.databaseObjectDeviceConfig(true),
                DeviceDataKeys.databaseListStringRecentTags(true), DeviceDataKeys.databaseMapStringStringDisplayOptions(true)
        };
        String[] names = {"operation_count", "notifications_enabled", "welcome_message",
                "device_config", "recent_tags", "display_options"};
        String[] schemas = {"primitive.int", "primitive.boolean", "primitive.string",
                "com.oem.device.config", "com.oem.device.string-list", "com.oem.device.string-map"};
        ValueCodec<?>[] codecs = {Codecs.INT, Codecs.BOOLEAN, Codecs.STRING,
                DeviceConfigCodec.INSTANCE, DeviceCollections.STRING_LIST, DeviceCollections.STRING_MAP};
        int[] stores = {Wire.MEMORY, Wire.FILE, Wire.DATABASE};
        Set<String> addresses = new HashSet<>();
        for (int i = 0; i < publicKeys.length; i++) {
            int group = i / names.length;
            int type = i % names.length;
            DataKey<?> key = publicKeys[i];
            assertEquals(stores[group], key.store);
            assertEquals(Wire.USER_CE, key.scope);
            assertEquals(DeviceDataKeys.APP1_PACKAGE, key.ownerPackage);
            assertEquals(DeviceDataKeys.SHARED_NAMESPACE, key.namespace);
            assertEquals(names[type], key.key);
            assertEquals(schemas[type], key.codec.schemaId());
            assertSame(codecs[type], key.codec);
            assertFalse("旧 a 地址不可混入新示例", "a".equals(key.key));
            addresses.add(key.store + ":" + key.key);

            DataKey<?> platform = platformKeys[i];
            assertEquals(key.store, platform.store);
            assertEquals(key.key, platform.key);
            assertEquals(Wire.DEVICE_DE, platform.scope);
            assertEquals(DeviceDataKeys.SYSTEM_NAMESPACE, platform.namespace);
            assertEquals(DeviceDataKeys.APP1_PACKAGE, platform.ownerPackage);
            assertSame(codecs[type], platform.codec);
        }
        assertEquals(18, addresses.size());
    }

    @Test public void eventTopicsAreDirectionalAndUseMatchingScopes() {
        EventTopic sent = DeviceTopics.app1ToApp2ConfigRefreshedEvent(false);
        EventTopic requested = DeviceTopics.app2ToApp1ConfigRefreshRequestEvent(false);
        assertEquals(Wire.USER_CE, sent.scope);
        assertEquals(DeviceDataKeys.APP1_PACKAGE, sent.ownerPackage);
        assertEquals("events.com.oem.globaldata.app1/CONFIG_REFRESHED", sent.topic);
        assertEquals("events.com.oem.globaldata.app1/REQUEST_CONFIG_REFRESH", requested.topic);
        assertEquals(DeviceDataKeys.APP1_PACKAGE, requested.ownerPackage);
        assertFalse(sent.topic.equals(requested.topic));
        EventTopic platformSent = DeviceTopics.app1ToApp2ConfigRefreshedEvent(true);
        EventTopic platformRequested = DeviceTopics.app2ToApp1ConfigRefreshRequestEvent(true);
        assertEquals(Wire.DEVICE_DE, platformSent.scope);
        assertEquals(Wire.DEVICE_DE, platformRequested.scope);
        assertEquals("sys.device/CONFIG_REFRESHED", platformSent.topic);
        assertEquals("sys.device/REQUEST_CONFIG_REFRESH", platformRequested.topic);
        assertEquals("events.com.oem.globaldata.app1/CONFIG_REFRESH",
                DeviceTopics.legacyConfigRefreshEvent(false).topic);
        assertEquals("sys.device/CONFIG_REFRESH", DeviceTopics.legacyConfigRefreshEvent(true).topic);
    }

    @Test public void byteDemoAddressesAreDistinctAcrossStoresAndSharedAcrossApps() {
        DataKey<?>[] keys = {
                DeviceDataKeys.memoryBytesDiagnosticBytes(false),
                DeviceDataKeys.fileBytesDiagnosticBytes(false),
                DeviceDataKeys.databaseBytesDiagnosticBytes(false)};
        int[] stores = {Wire.MEMORY, Wire.FILE, Wire.DATABASE};
        for (int i = 0; i < keys.length; i++) {
            assertEquals(stores[i], keys[i].store);
            assertEquals(Wire.USER_CE, keys[i].scope);
            assertEquals(DeviceDataKeys.APP1_PACKAGE, keys[i].ownerPackage);
            assertEquals(DeviceDataKeys.SHARED_NAMESPACE, keys[i].namespace);
            assertEquals("diagnostic_bytes", keys[i].key);
            assertSame(Codecs.BYTES, keys[i].codec);
        }
        assertEquals(Wire.DEVICE_DE, DeviceDataKeys.memoryBytesDiagnosticBytes(true).scope);
        assertEquals(Wire.DEVICE_DE, DeviceDataKeys.fileBytesDiagnosticBytes(true).scope);
        assertEquals(Wire.DEVICE_DE, DeviceDataKeys.databaseBytesDiagnosticBytes(true).scope);
    }

    @Test public void numericDemoKeysKeepTypeAndStoreIsolationInBothScopes() {
        DataKey<?>[] publicKeys = {
                DeviceDataKeys.memoryLongTotalTransferBytes(false),
                DeviceDataKeys.memoryFloatDownloadProgressRatio(false),
                DeviceDataKeys.memoryDoubleSensorTemperatureCelsius(false),
                DeviceDataKeys.fileLongTotalTransferBytes(false),
                DeviceDataKeys.fileFloatDownloadProgressRatio(false),
                DeviceDataKeys.fileDoubleSensorTemperatureCelsius(false),
                DeviceDataKeys.databaseLongTotalTransferBytes(false),
                DeviceDataKeys.databaseFloatDownloadProgressRatio(false),
                DeviceDataKeys.databaseDoubleSensorTemperatureCelsius(false)
        };
        DataKey<?>[] platformKeys = {
                DeviceDataKeys.memoryLongTotalTransferBytes(true),
                DeviceDataKeys.memoryFloatDownloadProgressRatio(true),
                DeviceDataKeys.memoryDoubleSensorTemperatureCelsius(true),
                DeviceDataKeys.fileLongTotalTransferBytes(true),
                DeviceDataKeys.fileFloatDownloadProgressRatio(true),
                DeviceDataKeys.fileDoubleSensorTemperatureCelsius(true),
                DeviceDataKeys.databaseLongTotalTransferBytes(true),
                DeviceDataKeys.databaseFloatDownloadProgressRatio(true),
                DeviceDataKeys.databaseDoubleSensorTemperatureCelsius(true)
        };
        int[] stores = {Wire.MEMORY, Wire.FILE, Wire.DATABASE};
        String[] names = {"total_transfer_bytes", "download_progress_ratio",
                "sensor_temperature_celsius"};
        ValueCodec<?>[] codecs = {Codecs.LONG, Codecs.FLOAT, Codecs.DOUBLE};
        Set<String> addresses = new HashSet<>();
        for (int i = 0; i < publicKeys.length; i++) {
            int type = i % names.length;
            DataKey<?> publicKey = publicKeys[i];
            DataKey<?> platformKey = platformKeys[i];
            assertEquals(stores[i / names.length], publicKey.store);
            assertEquals(names[type], publicKey.key);
            assertSame(codecs[type], publicKey.codec);
            assertEquals(Wire.USER_CE, publicKey.scope);
            assertEquals(DeviceDataKeys.APP1_PACKAGE, publicKey.ownerPackage);
            assertEquals(DeviceDataKeys.SHARED_NAMESPACE, publicKey.namespace);
            assertEquals(publicKey.store, platformKey.store);
            assertEquals(publicKey.key, platformKey.key);
            assertSame(publicKey.codec, platformKey.codec);
            assertEquals(Wire.DEVICE_DE, platformKey.scope);
            assertEquals(DeviceDataKeys.APP1_PACKAGE, platformKey.ownerPackage);
            assertEquals(DeviceDataKeys.SYSTEM_NAMESPACE, platformKey.namespace);
            addresses.add(publicKey.store + ":" + publicKey.key);
        }
        assertEquals(9, addresses.size());
    }

    @Test public void byteDemoHexInputRoundTripsAndRejectsInvalidText() {
        assertArrayEquals(new byte[]{0x0a, (byte) 0xff, 0x01},
                DemoValueText.parseHex("0A ff 01"));
        assertEquals("0AFF01", DemoValueText.display(new byte[]{0x0a, (byte) 0xff, 0x01}));
        assertThrows(IllegalArgumentException.class, () -> DemoValueText.parseHex("ABC"));
        assertThrows(IllegalArgumentException.class, () -> DemoValueText.parseHex("GG"));
    }

    @Test public void setDemoKeysUseSeparateAddressesAndKeepOldCollectionContracts() {
        DataKey<?>[] publicKeys = {
                DeviceDataKeys.memorySetStringEnabledFeatureFlags(false),
                DeviceDataKeys.fileSetStringEnabledFeatureFlags(false),
                DeviceDataKeys.databaseSetStringEnabledFeatureFlags(false)};
        DataKey<?>[] platformKeys = {
                DeviceDataKeys.memorySetStringEnabledFeatureFlags(true),
                DeviceDataKeys.fileSetStringEnabledFeatureFlags(true),
                DeviceDataKeys.databaseSetStringEnabledFeatureFlags(true)};
        int[] stores = {Wire.MEMORY, Wire.FILE, Wire.DATABASE};
        Set<String> addresses = new HashSet<>();
        for (int i = 0; i < stores.length; i++) {
            assertEquals(stores[i], publicKeys[i].store);
            assertEquals("enabled_feature_flags", publicKeys[i].key);
            assertEquals(Wire.USER_CE, publicKeys[i].scope);
            assertEquals(DeviceDataKeys.APP1_PACKAGE, publicKeys[i].ownerPackage);
            assertEquals(DeviceDataKeys.SHARED_NAMESPACE, publicKeys[i].namespace);
            assertSame(DeviceCollections.STRING_SET, publicKeys[i].codec);
            assertEquals(stores[i], platformKeys[i].store);
            assertEquals(Wire.DEVICE_DE, platformKeys[i].scope);
            assertEquals(DeviceDataKeys.SYSTEM_NAMESPACE, platformKeys[i].namespace);
            assertEquals(publicKeys[i].key, platformKeys[i].key);
            assertSame(DeviceCollections.STRING_SET, platformKeys[i].codec);
            addresses.add(publicKeys[i].store + ":" + publicKeys[i].key);
        }
        assertEquals(3, addresses.size());
        DataKey<?>[] oldLists = {
                DeviceDataKeys.memoryListStringRecentTags(false),
                DeviceDataKeys.fileListStringRecentTags(false),
                DeviceDataKeys.databaseListStringRecentTags(false)};
        DataKey<?>[] oldMaps = {
                DeviceDataKeys.memoryMapStringStringDisplayOptions(false),
                DeviceDataKeys.fileMapStringStringDisplayOptions(false),
                DeviceDataKeys.databaseMapStringStringDisplayOptions(false)};
        for (int i = 0; i < stores.length; i++) {
            assertEquals(stores[i], oldLists[i].store);
            assertEquals(stores[i], oldMaps[i].store);
            assertEquals("recent_tags", oldLists[i].key);
            assertEquals("display_options", oldMaps[i].key);
            assertSame(DeviceCollections.STRING_LIST, oldLists[i].codec);
            assertSame(DeviceCollections.STRING_MAP, oldMaps[i].codec);
            assertEquals(DeviceDataKeys.SHARED_NAMESPACE, oldLists[i].namespace);
            assertEquals(DeviceDataKeys.SHARED_NAMESPACE, oldMaps[i].namespace);
        }
    }

    @Test public void configReadsOldSchemaAndNewSchema() throws Exception {
        byte[] old = "{\"deviceId\":7,\"deviceName\":\"old\",\"enabled\":true}"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals("", DeviceConfigCodec.INSTANCE.decode(old, 1).group);
        DeviceConfig current = new DeviceConfig(8, "new", false, "lab");
        DeviceConfig decoded = DeviceConfigCodec.INSTANCE.decode(
                DeviceConfigCodec.INSTANCE.encode(current), 2);
        assertEquals(current.deviceId, decoded.deviceId);
        assertEquals(current.deviceName, decoded.deviceName);
        assertEquals(current.group, decoded.group);
        assertThrows(IllegalArgumentException.class,
                () -> DeviceConfigCodec.INSTANCE.decode(old, 3));
    }

    @Test public void collectionsRoundTrip() throws Exception {
        assertEquals(Arrays.asList("甲", "乙"), DeviceCollections.STRING_LIST.decode(
                DeviceCollections.STRING_LIST.encode(Arrays.asList("甲", "乙")), 1));
        Map<String, String> options = new HashMap<>();
        options.put("mode", "共享"); options.put("version", "2");
        assertEquals(options, DeviceCollections.STRING_MAP.decode(
                DeviceCollections.STRING_MAP.encode(options), 1));
    }

    @Test public void setCodecIsDeterministicAndRejectsMalformedItems() throws Exception {
        Set<String> first = new LinkedHashSet<>(Arrays.asList("wifi", "bluetooth"));
        Set<String> reversed = new LinkedHashSet<>(Arrays.asList("bluetooth", "wifi"));
        byte[] encoded = DeviceCollections.STRING_SET.encode(first);
        assertArrayEquals(encoded, DeviceCollections.STRING_SET.encode(reversed));
        assertEquals("[\"bluetooth\",\"wifi\"]", new String(encoded, StandardCharsets.UTF_8));
        assertEquals(first, DeviceCollections.STRING_SET.decode(encoded, 1));
        assertEquals(new HashSet<>(), DeviceCollections.STRING_SET.decode(
                DeviceCollections.STRING_SET.encode(new HashSet<>()), 1));
        assertEquals("com.oem.device.string-set", DeviceCollections.STRING_SET.schemaId());
        assertThrows(IllegalArgumentException.class, () -> DeviceCollections.STRING_SET.decode(
                "[\"wifi\",\"wifi\"]".getBytes(StandardCharsets.UTF_8), 1));
        assertThrows(IllegalArgumentException.class, () -> DeviceCollections.STRING_SET.decode(
                "[\"wifi\",1]".getBytes(StandardCharsets.UTF_8), 1));
        assertThrows(IllegalArgumentException.class, () -> DeviceCollections.STRING_SET.decode(encoded, 2));
        Set<String> withNull = new HashSet<>(); withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> DeviceCollections.STRING_SET.encode(withNull));
    }
}
