package org.devtcg.rojocam.rtsp;

public class StringUtils {
    public static String join(String[] array, String delimiter) {
        if (array.length == 0) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        b.append(array[0]);
        for (int i = 1; i < array.length; i++) {
            b.append(delimiter);
            b.append(array[i]);
        }
        return b.toString();
    }

    private static final char[] HEX_CHAR_TABLE = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
    };

    public static String byteArrayToHexString(byte[] raw) {
        char[] hex = new char[2 * raw.length];
        int index = 0;

        for (byte b : raw) {
            int v = b & 0xFF;
            hex[index++] = HEX_CHAR_TABLE[v >>> 4];
            hex[index++] = HEX_CHAR_TABLE[v & 0xF];
        }

        return new String(hex);
    }
}
