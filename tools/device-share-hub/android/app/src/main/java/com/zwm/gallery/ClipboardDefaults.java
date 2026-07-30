package com.zwm.gallery;

import java.io.IOException;

/** Curated front-desk phrases from the authoritative team-building SOP. */
final class ClipboardDefaults {
    private static final String[] FRONT_DESK_PHRASES = {
            "您好呀，是咨询公司团建对吧？方便问下您是从小红书、抖音还是公众号看到我们的呀？",
            "您好呀，我这边是团建咨询。咱们这次大概多少人参加呢？",
            "了解～您从哪里出发，大概想安排一天还是两天一晚呢？日期有方向了吗？",
            "可以按您的预算方向来做，具体要结合人数、天数和车餐住玩这些包含项。您告诉我大概人数，我让策划师把方案和明细报价发您看。",
            "理解您的顾虑，我们先正常聊需求就好，不需要做任何转账或敏感操作。等方向确认后，完整方案和报价明细再通过正常对接群发您。",
            "这些细节策划师更专业，我给您建个小群，让他按现有需求发PPT方案和报价。您有什么要求直接在群里说，后面调整也方便。",
            "@策划师 这位客户计划【日期/天数】从【出发地】出发，大约【人数】，想做【地点/活动方向】，比较关注【预算/住宿/交通】。目前还差【缺失项】，麻烦您接着对接方案和报价。",
            "好的，您先忙～等人数或日期有方向了直接发我，我再按现在的信息接着安排。"
    };

    private ClipboardDefaults() {
    }

    static int ensure(SharedClipboardStore store) throws IOException {
        int added = 0;
        for (int index = 0; index < FRONT_DESK_PHRASES.length; index++) {
            String id = String.format(java.util.Locale.US, "preset-front-%02d", index + 1);
            if (store.putIfAbsent(id, SharedClipboardStore.KIND_PHRASE,
                    FRONT_DESK_PHRASES[index], 1L)) {
                added++;
            }
        }
        return added;
    }
}
