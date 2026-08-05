package com.chuanyi.hooker.hookers.esj

/**
 * 《疯狂水世界》（`com.gx.sw.qa.fkssj004.esj`）的常量与协议表。
 *
 * 目标是 Cocos Creator 3.8.3 + 易玩 SuperSDK 聚合渠道的服务端权威 SLG。
 * 这里只放「跨版本大概率不变」的东西：Cocos 引擎自带的类名、协议帧的字节布局、
 * 服务端信任客户端上报的那几个消息名。游戏自己的业务类一个都没有 ——
 * 业务逻辑全在 `assets/main/index.jsc`（XXTEA 加密），Java 侧根本够不着。
 */
internal object Esj {

    const val PACKAGE = "com.gx.sw.qa.fkssj004.esj"

    // ---- Cocos 引擎侧的落点。这些是引擎自带的，跟着 Cocos 版本走，不随游戏改版 ----

    /** `evalString(String): int` —— 官方的「把一段 JS 丢进引擎执行」入口。 */
    const val JS_BRIDGE = "com.cocos.lib.CocosJavascriptJavaBridge"

    /** `runOnGameThread(Runnable)` —— evalString 必须在这个线程上跑。 */
    const val COCOS_HELPER = "com.cocos.lib.CocosHelper"

    /** JS 侧 `jsb.bridge.sendToNative` 的落地点，我们靠它把注入结果回传。 */
    const val JSB_BRIDGE_WRAPPER = "com.cocos.lib.JsbBridgeWrapper"

    /** 热更下载全部经过它的静态 `createTask`。 */
    const val COCOS_DOWNLOADER = "com.cocos.lib.CocosDownloader"

    const val APP_ACTIVITY = "com.cocos.game.AppActivity"

    /** 游戏自己的 SDK 桥接类，支付/登录都在这里转手。 */
    const val SDK_SUPPORT = "com.gx.cocos.utils.SDKSupport"

    // ---- JS 回传用的事件名。JsbBridgeWrapper 按名字分发，跟游戏自己的事件不会撞 ----

    const val EVENT_LOG = "esj.hooker.log"
    const val EVENT_READY = "esj.hooker.ready"
    const val EVENT_RECORD = "esj.hooker.rec"

    /** 模块在设备上的工作目录，外部脚本和录制文件都放这儿。 */
    const val WORK_DIR = "/sdcard/Android/data/$PACKAGE/files/esj"

    /**
     * 录制文件。每行一条：`方向|消息名|base64(完整帧)`。
     *
     * 离线服务端的初始数据就是从这儿来的 —— 与其把 294 种响应的字段结构逐个
     * 重新推导，不如把真实服务端在一次正常登录里下发的东西原样录下来。
     */
    const val RECORD_FILE = "$WORK_DIR/record.txt"

    /**
     * 外部脚本路径。
     *
     * 放在应用自己的外部目录下，理由是这里 `adb push` 不需要 root、游戏进程又天然可读。
     * 本地服务端的业务逻辑写在这个文件里，模块只负责把它加载进引擎 ——
     * 改逻辑就变成推一个文件加重进游戏，不用重新编译整个模块。
     */
    const val EXTERNAL_SCRIPT = "$WORK_DIR/server.js"

    // ---- 热更 ----

    /**
     * 热更清单的文件名。锁版本时拦的就是对这两个的下载请求。
     *
     * 实测 `version.manifest` 里写着：
     * `remoteManifestUrl = https://<cdn>/HotUpdateRes/gf_jg/android/project.manifest`
     * 客户端每次启动先取 `version.manifest` 比版本号，版本高了才去拉 `project.manifest`
     * 和差异资源。所以拦住这两个名字就等于把热更停在原地。
     */
    val HOT_UPDATE_MANIFESTS = listOf("version.manifest", "project.manifest")

    /**
     * 服务端信任客户端上报结果的消息。
     *
     * 这几个是从 `proto-bundle.js`（协议定义，明文）里扫出来的：整份协议共
     * 535 个 Request，绝大多数只把「意图」发给服务端（建造哪个、升级哪个），
     * 数值由服务端算。只有下面这些把**客户端算好的结果**一起报上去 ——
     * 也就是说改了它们，服务端发的是真奖励。
     *
     * `tag` 是 protobuf 字段号，`wire` 固定为 0（varint）。倍率放大而不是写死一个
     * 天文数字：服务端对这些值大多有上限，报一个明显不可能的数反而会被打回。
     */
    val REPORTED_FIELDS: List<ReportedField> = listOf(
        ReportedField("DanceEndRequest", 3, "totalScore", "跳舞小游戏的总分"),
        ReportedField("DragonBoatEndRequest", 3, "distance", "龙舟划行的距离"),
        ReportedField("RelicAppraisalRequest", 3, "point", "文物鉴定的点数"),
        ReportedField("ClickGoldRequest", 1, "times", "点金的次数"),
        ReportedField("WeekLotteryPrizeRequest", 1, "times", "周抽奖的次数"),
        ReportedField("SeasonFactoryComposeRequest", 2, "num", "赛季工厂的合成数量"),
    )

    /**
     * 只观察不改的上报点。
     *
     * 这些同样是客户端说了算，但含义不是「多就是好」：
     * - `CatcherEndRequest.hookedRewardIds` 是一串奖励 ID，乱填的 ID 服务端查不到
     *   配置表，轻则忽略重则判异常，要先用观察台看真实值长什么样。
     * - `UpgradeAfkLevelRequest.level` / `DrawAfkLevelRewardRequest.level` 是等级，
     *   跳级大概率被服务端按当前进度打回。
     * - `TournamentBetRequest.amount` 是下注额，超过持有量必然失败。
     *
     * 打开「协议观察台」时会额外标出它们，方便实测后再决定要不要动。
     */
    val WATCH_ONLY = setOf(
        "CatcherEndRequest",
        "UpgradeAfkLevelRequest",
        "DrawAfkLevelRewardRequest",
        "TournamentBetRequest",
        "TournamentClaimComboRequest",
        "BuyGroupBuyingProductRequest",
    )

    /**
     * 协议帧的字节布局（大端），从 `ProtocolCodec.encode` 反推：
     *
     * ```
     * [0..3]        int32  payloadLen = 总长 - 4
     * [4]           int8   协议版本，恒为 2
     * [5..8]        int32  seqNo
     * [9]           int8   消息名长度，不超过 127
     * [10 .. 10+n)  char[] 消息名，ASCII
     * [10+n]        int8   压缩标志，0 表示不压缩，非 0 表示 body 是 LZ4 块
     * [11+n ..]     bytes  protobuf body
     * ```
     *
     * 全程没有签名、没有 HMAC、没有加密 —— 改完 body 只要把 payloadLen 重算就行。
     * 上行（客户端发出）实测恒为不压缩，所以改写只处理压缩标志为 0 的帧。
     */
    const val PROTO_VERSION = 2

    const val HEADER_FIXED = 10

    internal data class ReportedField(
        /** protobuf 消息名，和线上帧头里那串 ASCII 完全一致。 */
        val message: String,
        /** 字段号。 */
        val tag: Int,
        /** 字段名，只用于日志。 */
        val field: String,
        /** 给人看的说明。 */
        val note: String,
    )
}
