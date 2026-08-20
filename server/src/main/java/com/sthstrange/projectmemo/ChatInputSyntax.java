package com.sthstrange.projectmemo;

/** 数量表达式解析：64 / 3*64 / 2*1728+64（支持 x、× 写法）。 */
public final class ChatInputSyntax {

    private ChatInputSyntax() { }

    public static Long parseAmount(String s) {
        try {
            s = s.replace("×", "*").replace("x", "*").replace("X", "*").replace(" ", "");
            if (s.isEmpty()) return null;
            long total = 0;
            for (String part : s.split("\\+")) {
                if (part.isEmpty()) return null;
                long v = 1;
                for (String f : part.split("\\*")) {
                    long n = Long.parseLong(f);
                    if (n <= 0) return null;
                    v *= n;
                }
                total += v;
            }
            return total > 0 ? total : null;
        } catch (Exception e) {
            return null;
        }
    }
}
