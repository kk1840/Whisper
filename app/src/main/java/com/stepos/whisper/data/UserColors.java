package com.stepos.whisper.data;

import java.util.List;

/** 用户颜色调色板。 */
public final class UserColors {
    public static final int[] PALETTE = {
            0xFF3D5AFE, 0xFFE53935, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA, 0xFF00ACC1,
            0xFFD81B60, 0xFF6D4C41, 0xFF1E88E5, 0xFF7CB342, 0xFFF4511E, 0xFF5E35B1,
    };

    public static final int OWNER_DEFAULT = PALETTE[0];

    private UserColors() {
    }

    /** 选一个还没被占用的颜色；都被占用时按数量轮转。 */
    public static int pickUnused(List<Integer> used) {
        for (int c : PALETTE) {
            if (!used.contains(c)) {
                return c;
            }
        }
        return PALETTE[used.size() % PALETTE.length];
    }

    /** 老数据颜色为 0 时给一个稳定的默认色。 */
    public static int orDefault(int color, long userId) {
        return color != 0 ? color : PALETTE[(int) (Math.abs(userId) % PALETTE.length)];
    }
}
