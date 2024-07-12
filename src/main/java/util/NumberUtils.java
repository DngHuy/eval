package util;

public class NumberUtils {


    public static Object getNumberOrString(String s) {

        try {
            return Long.parseLong(s);
        } catch (RuntimeException rte) {
            try {
                return Double.parseDouble(s);
            } catch (RuntimeException rte2) {
                return s;
            }
        }

    }
}
