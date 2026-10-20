package com.oem.globaldata.contracts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.ipc.Wire;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** 防止新业务地址再次使用无法辨认 Store 或数据格式的方法名。 */
public final class DeviceDataKeysNamingTest {
    @Test public void publicFactoriesNameStoreAndValueFormatWithoutChangingAddresses() throws Exception {
        int count = 0;
        for (Method method : DeviceDataKeys.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != DataKey.class) {
                continue;
            }
            count++;
            assertEquals(method.getName(), 1, method.getParameterCount());
            assertEquals(method.getName(), boolean.class, method.getParameterTypes()[0]);
            DataKey<?> normal = (DataKey<?>) method.invoke(null, false);
            DataKey<?> platform = (DataKey<?>) method.invoke(null, true);
            String expectedPrefix = storeName(normal.store) + formatName(
                    ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0]);
            String name = method.getName();
            assertTrue(name, name.startsWith(expectedPrefix));
            assertTrue(name, name.length() > expectedPrefix.length());
            assertTrue(name, Character.isUpperCase(name.charAt(expectedPrefix.length())));
            assertEquals(name, normal.store, platform.store);
            assertEquals(name, normal.key, platform.key);
            assertEquals(name, normal.codec.schemaId(), platform.codec.schemaId());
            assertEquals(name, Wire.USER_CE, normal.scope);
            assertEquals(name, Wire.DEVICE_DE, platform.scope);
        }
        assertEquals(83, count);
        // 历史综合示例仍指向原业务地址，只有 Java 方法名发生变化。
        assertEquals("mode", DeviceDataKeys.memoryIntMode(false).key);
        assertEquals("config", DeviceDataKeys.fileObjectConfig(false).key);
        assertEquals("config", DeviceDataKeys.databaseObjectConfig(false).key);
        assertEquals("tags", DeviceDataKeys.databaseListStringTags(false).key);
        assertEquals("options", DeviceDataKeys.fileMapStringStringOptions(false).key);
    }

    private static String storeName(int store) {
        switch (store) {
            case Wire.MEMORY: return "memory";
            case Wire.FILE: return "file";
            case Wire.DATABASE: return "database";
            default: throw new AssertionError("Unknown store " + store);
        }
    }

    private static String formatName(Type type) {
        if (type == Integer.class) return "Int";
        if (type == Long.class) return "Long";
        if (type == Float.class) return "Float";
        if (type == Double.class) return "Double";
        if (type == Boolean.class) return "Boolean";
        if (type == String.class) return "String";
        if (type == DeviceConfig.class) return "Object";
        if (type == byte[].class) return "Bytes";
        if (type instanceof ParameterizedType) {
            ParameterizedType collection = (ParameterizedType) type;
            Type raw = collection.getRawType();
            Type[] args = collection.getActualTypeArguments();
            if (raw == List.class) return "List" + formatName(args[0]);
            if (raw == Set.class) return "Set" + formatName(args[0]);
            if (raw == Map.class) return "Map" + formatName(args[0]) + formatName(args[1]);
        }
        throw new AssertionError("Unknown value type " + type);
    }
}
