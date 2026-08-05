package com.chuanyi.hooker.hookers.gameclick

import org.json.JSONObject

/**
 * 登录返回的改写。
 *
 * 服务端 `Login4` 返回的是**明文 JSON**，原样交给 `NativeUtil.LoginResult(String)`：
 *
 * ```json
 * {"share":"...","notice":"","openshop":true,"forcestorage":false,"openwx":true,
 *  "wxlogin":true,"devicenum":1,"viptime":"2026-8-11 1:33","vip":"1","uid":6174505,
 *  "vipchange":0,"timeout":509188,"par":"T7kARTRBcE6..."}
 * ```
 *
 * 那个 `par` 看着像签名，实测**不是**：把 `viptime` 改成 2099、`devicenum` 改成 99
 * 之后 `par` 原封不动送进去，原生库照单全收，`GetVipTime()` / `DeviceNum()` 当场
 * 就是新值。所以这里只需要改字段，不用重算任何东西。
 *
 * 改源头而不是逐个补读取点，是这个 hooker 的主路径：原生库内部的会员状态本身就
 * 变成了「是会员」，Java 侧那十几处 `IsVip()`、原生侧自己的判断、乃至下次冷启动前
 * 的缓存，全都自然一致。
 */
internal object LoginPayload {

    /**
     * 把一份登录返回改成「永久会员」。
     *
     * @param raw 服务端原始响应体
     * @param deviceSlots 想显示的设备位数量；<= 0 表示保持服务端给的值
     * @return 改写后的 JSON；[raw] 不是 JSON 对象时原样返回
     */
    fun forge(raw: String?, deviceSlots: Int): String? {
        if (raw.isNullOrBlank()) return raw
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return raw

        // vip 才是判定位。服务端给的是字符串 "1"，但按原类型写回去更稳 ——
        // 万一哪天改成数字，写字符串会让原生侧的解析落空。
        if (json.opt(GameClick.F_VIP) is Number) {
            json.put(GameClick.F_VIP, 1)
        } else {
            json.put(GameClick.F_VIP, GameClick.VIP_YES)
        }

        // 不含空格 => 界面走 R.string.forever 那条分支，显示「永久」。
        json.put(GameClick.F_VIP_TIME, GameClick.VIP_TIME_FOREVER)

        // 这三个是入口开关，任何一个为 false 都会让账号页/悬浮窗少一块。
        json.put(GameClick.F_OPEN_SHOP, true)
        json.put(GameClick.F_OPEN_WX, true)
        json.put(GameClick.F_WX_LOGIN, true)

        // 非空就会拦下悬浮窗（CheckDeviceNum 弹框后返回 false），清掉。
        json.put(GameClick.F_LOGIN_ERROR, "")

        if (deviceSlots > 0) json.put(GameClick.F_DEVICE_NUM, deviceSlots)

        // vipchange 有意不动：它只控制「vip时间延长至…」那个提示弹不弹，
        // 强行置 1 会变成每次登录都弹一次。
        return json.toString()
    }

    /** 日志用的摘要，避免把整包（含 openid 密文）刷进 logcat。 */
    fun digest(raw: String?): String {
        if (raw.isNullOrBlank()) return "<空>"
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return "<非 JSON, ${raw.length} 字节>"
        return buildString {
            append("vip=").append(json.opt(GameClick.F_VIP))
            append(" viptime=").append(json.opt(GameClick.F_VIP_TIME))
            append(" devicenum=").append(json.opt(GameClick.F_DEVICE_NUM))
            append(" wxlogin=").append(json.opt(GameClick.F_WX_LOGIN))
            val err = json.optString(GameClick.F_LOGIN_ERROR)
            if (err.isNotEmpty()) append(" err=").append(err)
        }
    }
}
