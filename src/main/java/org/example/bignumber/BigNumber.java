package org.example.bignumber;

/**
 * 大数运算工具类
 * <p>
 * 使用字符串模拟手工计算过程，支持任意长度的非负整数相加、相乘。
 * 不依赖 JDK 的 {@link java.math.BigInteger}，用于展示底层实现原理。
 * </p>
 *
 * 复杂度分析：
 * <ul>
 *     <li>加法：时间 O(max(n, m))，空间 O(max(n, m))</li>
 *     <li>乘法：时间 O(n * m)，空间 O(n + m)</li>
 * </ul>
 */
public class BigNumber {

    /**
     * 大数相加：计算 num1 + num2
     *
     * @param num1 非负整数的字符串表示，例如 "123456789"
     * @param num2 非负整数的字符串表示，例如 "987654321"
     * @return 两数之和的字符串表示
     */
    public static String add(String num1, String num2) {
        validate(num1);
        validate(num2);

        StringBuilder sb = new StringBuilder();
        int i = num1.length() - 1;
        int j = num2.length() - 1;
        int carry = 0;

        // 从末位向前逐位相加，同时维护进位
        while (i >= 0 || j >= 0 || carry > 0) {
            int a = i >= 0 ? num1.charAt(i--) - '0' : 0;
            int b = j >= 0 ? num2.charAt(j--) - '0' : 0;
            int sum = a + b + carry;
            sb.append(sum % 10);
            carry = sum / 10;
        }

        return sb.reverse().toString();
    }

    /**
     * 大数相乘：计算 num1 * num2
     * <p>
     * 采用经典竖式乘法：num1 的第 i 位与 num2 的第 j 位相乘，
     * 其结果一定落在结果数组的 [i + j, i + j + 1] 两个位置上。
     * </p>
     *
     * @param num1 非负整数的字符串表示
     * @param num2 非负整数的字符串表示
     * @return 两数之积的字符串表示
     */
    public static String multiply(String num1, String num2) {
        validate(num1);
        validate(num2);

        // 任一乘数为 0 时结果直接为 0，避免出现前导零
        if ("0".equals(num1) || "0".equals(num2)) {
            return "0";
        }

        int n = num1.length();
        int m = num2.length();
        // 长度为 n 和 m 的两数相乘，结果最长为 n + m 位
        int[] result = new int[n + m];

        for (int i = n - 1; i >= 0; i--) {
            int a = num1.charAt(i) - '0';
            for (int j = m - 1; j >= 0; j--) {
                int b = num2.charAt(j) - '0';
                int mul = a * b;
                // p1 是高位索引，p2 是低位索引
                int p1 = i + j;
                int p2 = i + j + 1;
                int sum = mul + result[p2];

                result[p2] = sum % 10;
                result[p1] += sum / 10;
            }
        }

        // 拼接结果，跳过可能存在的前导零
        StringBuilder sb = new StringBuilder();
        for (int digit : result) {
            if (sb.length() == 0 && digit == 0) {
                continue;
            }
            sb.append(digit);
        }
        return sb.length() == 0 ? "0" : sb.toString();
    }

    /**
     * 校验入参必须是非空且只包含数字字符的字符串。
     */
    private static void validate(String num) {
        if (num == null || num.isEmpty()) {
            throw new IllegalArgumentException("输入不能为空");
        }
        for (int i = 0; i < num.length(); i++) {
            char c = num.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("输入包含非数字字符：" + num);
            }
        }
    }

    public static void main(String[] args) {
        // 演示用例
        String a = "123456789123456789";
        String b = "987654321987654321";

        System.out.println("a = " + a);
        System.out.println("b = " + b);
        System.out.println("a + b = " + add(a, b));
        System.out.println("a * b = " + multiply(a, b));

        // 边界用例
        System.out.println("0 + 0 = " + add("0", "0"));
        System.out.println("0 * 12345 = " + multiply("0", "12345"));
        System.out.println("999 + 1 = " + add("999", "1"));
        System.out.println("99 * 99 = " + multiply("99", "99"));
    }
}
