package com.oem.globaldata.contracts;

/** 供字节演示页使用的十六进制输入和可读值显示工具。 */
public final class DemoValueText {
    private DemoValueText() {}

    /** 偶数位十六进制，可在字节之间插入空格；不接受隐式字符编码。 */
    public static byte[] parseHex(String text) {
        String hex = text.replaceAll("\\s+", "");
        if ((hex.length() & 1) != 0) throw new IllegalArgumentException("十六进制必须是偶数位");
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("只能输入十六进制数字与空格");
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    /** 原始字节用十六进制显示，避免 byte[] 默认显示对象地址。 */
    public static String display(Object value) {
        if (value instanceof DeviceConfig) {
            DeviceConfig config = (DeviceConfig) value;
            return "编号=" + config.deviceId + "，名称=" + config.deviceName
                    + "，启用=" + config.enabled + "，分组=" + config.group;
        }
        if (!(value instanceof byte[])) return String.valueOf(value);
        StringBuilder hex = new StringBuilder();
        for (byte b : (byte[]) value) hex.append(String.format("%02X", b & 0xff));
        return hex.toString();
    }
}
