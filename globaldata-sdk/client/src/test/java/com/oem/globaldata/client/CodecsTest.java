package com.oem.globaldata.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class CodecsTest {
    @Test public void primitiveRoundTripsAndRejectsWrongVersion() throws Exception {
        assertEquals(Integer.valueOf(-187), Codecs.INT.decode(Codecs.INT.encode(-187), 1));
        assertEquals(Boolean.TRUE, Codecs.BOOLEAN.decode(Codecs.BOOLEAN.encode(true), 1));
        assertEquals("共享数据😀", Codecs.STRING.decode(Codecs.STRING.encode("共享数据😀"), 1));
        byte[] original = {0, 1, -1};
        byte[] encoded = Codecs.BYTES.encode(original);
        original[0] = 9;
        assertArrayEquals(new byte[]{0, 1, -1}, Codecs.BYTES.decode(encoded, 1));
        assertThrows(IllegalArgumentException.class, () -> Codecs.INT.decode(new byte[]{1}, 1));
        assertThrows(IllegalArgumentException.class, () -> Codecs.BOOLEAN.decode(new byte[]{2}, 1));
        assertThrows(IllegalArgumentException.class, () -> Codecs.STRING.decode(new byte[0], 2));
    }

    @Test public void longUsesEightBigEndianBytesAndKeepsFullRange() throws Exception {
        assertEquals("primitive.long", Codecs.LONG.schemaId());
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0, 0, 0, 0},
                Codecs.LONG.encode(4_294_967_296L));
        for (long value : new long[]{Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE})
            assertEquals(Long.valueOf(value), Codecs.LONG.decode(Codecs.LONG.encode(value), 1));
        assertThrows(IllegalArgumentException.class, () -> Codecs.LONG.decode(new byte[7], 1));
        assertThrows(IllegalArgumentException.class, () -> Codecs.LONG.decode(new byte[8], 2));
    }

    @Test public void floatKeepsIeee754BitsAndRejectsWrongFormat() throws Exception {
        assertEquals("primitive.float", Codecs.FLOAT.schemaId());
        assertArrayEquals(new byte[]{0x3f, 0x40, 0, 0}, Codecs.FLOAT.encode(0.75f));
        float payloadNaN = Float.intBitsToFloat(0x7fc01234);
        for (float value : new float[]{-0.0f, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, payloadNaN}) {
            float decoded = Codecs.FLOAT.decode(Codecs.FLOAT.encode(value), 1);
            assertEquals(Float.floatToRawIntBits(value), Float.floatToRawIntBits(decoded));
        }
        assertThrows(IllegalArgumentException.class, () -> Codecs.FLOAT.decode(new byte[3], 1));
        assertThrows(IllegalArgumentException.class, () -> Codecs.FLOAT.decode(new byte[4], 2));
    }

    @Test public void doubleKeepsIeee754BitsAndRejectsWrongFormat() throws Exception {
        assertEquals("primitive.double", Codecs.DOUBLE.schemaId());
        assertArrayEquals(new byte[]{0x3f, (byte) 0xd7, (byte) 0xa0, 0, 0, 0, 0, 0},
                Codecs.DOUBLE.encode(0.369140625d));
        double payloadNaN = Double.longBitsToDouble(0x7ff8000000001234L);
        for (double value : new double[]{-0.0d, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, payloadNaN}) {
            double decoded = Codecs.DOUBLE.decode(Codecs.DOUBLE.encode(value), 1);
            assertEquals(Double.doubleToRawLongBits(value), Double.doubleToRawLongBits(decoded));
        }
        assertThrows(IllegalArgumentException.class, () -> Codecs.DOUBLE.decode(new byte[7], 1));
        assertThrows(IllegalArgumentException.class, () -> Codecs.DOUBLE.decode(new byte[8], 2));
    }
}
