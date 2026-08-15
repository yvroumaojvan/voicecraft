package com.yvroumaojvan.voicecraft;

/** Edge TTS 中文音色列表 */
public class Voices {
    public static final String[][] LIST = {
        {"zh-CN-XiaoxiaoNeural", "晓晓 · 温暖女声"},
        {"zh-CN-XiaoyiNeural", "晓伊 · 活泼女声"},
        {"zh-CN-XiaohanNeural", "晓涵 · 温和女声"},
        {"zh-CN-XiaomengNeural", "晓梦 · 甜美女声"},
        {"zh-CN-XiaomoNeural", "晓墨 · 知性女声"},
        {"zh-CN-XiaoruiNeural", "晓睿 · 成熟女声"},
        {"zh-CN-XiaoshuangNeural", "晓双 · 孩童女声"},
        {"zh-CN-XiaoxuanNeural", "晓萱 · 温柔女声"},
        {"zh-CN-XiaoyanNeural", "晓颜 · 自然女声"},
        {"zh-CN-XiaozhenNeural", "晓真 · 温婉女声"},
        {"zh-CN-YunxiNeural", "云希 · 阳光少年"},
        {"zh-CN-YunjianNeural", "云健 · 沉稳男声"},
        {"zh-CN-YunyangNeural", "云扬 · 专业男声"},
        {"zh-CN-YunfengNeural", "云枫 · 方言男声"},
        {"zh-CN-YunxiaNeural", "云夏 · 孩童男声"},
        {"zh-CN-YunhaoNeural", "云皓 · 磁性男声"},
    };

    /** 音色 id 数组 */
    public static final String[] ID = new String[LIST.length];
    /** 音色显示名数组 */
    public static final String[] LABEL = new String[LIST.length];

    static {
        for (int i = 0; i < LIST.length; i++) {
            ID[i] = LIST[i][0];
            LABEL[i] = LIST[i][1];
        }
    }

    /** 返回音色 id 数组（存入历史记录用） */
    public static String idOf(int index) {
        return LIST[index][0];
    }

    public static String labelOf(int index) {
        return LIST[index][1];
    }

    public static int indexOfId(String id) {
        for (int i = 0; i < LIST.length; i++) {
            if (LIST[i][0].equals(id)) return i;
        }
        return 0;
    }
}
