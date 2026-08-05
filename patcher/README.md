# 原包 patch

把 `:hookers:hills` 那套解锁**烧进 APK 本身**。装完不需要 LSPosed、不需要 root、
没有开关要维持 —— 应用自己就是解锁的。

```powershell
# 从设备上把装着的那套拉下来，patch 完直接装回去
python patcher\patch_hills.py --from-device --install

# 或者手上已经有原包
python patcher\patch_hills.py --input <放着 base.apk 和 split_*.apk 的目录>
```

产物在 `patcher\build\out\`，四个 split 都签好了，用 `adb install-multiple` 装。

---

## 为什么能这么干

模块侧最难的那部分，在静态 patch 里直接消失了。

`libapp.so` 里的 `_kDartIsolateSnapshotData` **是序列化的 cluster 流，不是堆镜像** ——
字符串前面紧挨着的是 `(len<<1)|0x80` 长度标记而不是对象头。isolate 启动时把它反序列化
进堆，所以**文件里是什么，堆里就是什么**。

模块必须跟 isolate 抢时间去改堆里那份（实测应用启动后 83ms 就发出第一次校验，
而全量扫一遍匿名映射要几百毫秒，这场赛跑基本必输，只能靠扣住 pigeon 回包硬等）。
静态 patch 没有这个问题：应用还没跑，字节就已经是改过的了。

等长覆写不动长度标记，所以不需要任何偏移，`--obfuscate` 也无所谓。

## 改了哪些地方

**snapshot（split_config.arm64_v8a.apk 里的 libapp.so），两处等长覆写**

| | |
|---|---|
| 校验端点 | `https://api.hills.im/functions/v1/google-verify-purchase` → `http://127.0.0.1:45872/ppp…`（补到同样 56B） |
| 应答验签公钥 | 应用自带的 450B PEM → 本次 patch 现生成的 RSA-2048 公钥（同样 450B） |

两个位置都是**扫出来的**，不是写死的偏移：URL 按路径里 verify/purchase 之类的词打分选，
公钥按 `-----BEGIN PUBLIC KEY-----` 前缀找。应用换了端点名或者轮换了密钥，重跑一遍即可。

**dex，7 个注入点**（`tools/smali_patches.py`，每处不是两条指令的插入就是整个方法体替换，
都不改寄存器压力）

| 位置 | 做什么 |
|---|---|
| `App.onCreate` | 起注入的运行时；这是每进程恰好跑一次、且早于一切的点 |
| `Translator.fromPurchasesList` | 包住返回值，塞进合成的购买记录。查询回包和购买回调都汇到这一个方法 |
| `MethodCallHandlerImpl.isFeatureSupported` | 恒返回 true。这就是「设备不支持 Google Play 订阅」那条 toast 的来源 |
| `MethodCallHandlerImpl.isReady` | 同上 |
| `MethodCallHandlerImpl.queryProductDetailsAsync` | 记下应用问 Play 要哪些商品 |
| `MethodCallHandlerImpl.launchBillingFlow` | 记下应用实际在卖哪个商品 |
| `PlayerConfig.isPro` | 恒返回 true |
| 签名通道的 digest 入参 | 换成原包证书，见下 |

**注入 `classes3.dex`** —— `patcher/runtime` 模块，纯 Java 零依赖（应用自带
kotlin-stdlib，我们再带一份就是重复类，dex 合并直接报错）。里面是 loopback 校验服务、
RS256 签名、合成购买的反射构造、商品 ID 学习。smali 只负责把控制权交出去，
真正的逻辑都在正常 Java 里 —— 手写 smali 版的 HTTP 服务是没法维护的。

## 重签名这件事

`isFeatureSupported` 那条 toast 之外，重打包唯一躲不掉的是签名：应用把
`SHA-256(apkContentsSigners[0].toByteArray())` 报给自己的后端。

处理办法是**换 digest 的输入而不是输出** —— 在 `Signature.toByteArray()` 之后插一句，
把原包证书顶上去，然后应用自己的 SHA-256 和自己的格式化照常跑。这样不用去猜它是
大写小写、拿什么分隔，格式天然对。

原包证书从输入 APK 的 v2/v3 签名块里取（这个包没有 v1，`META-INF/*.RSA` 根本不存在，
`apksigner` 又只打印摘要不给证书本体）—— `tools/apk_signing_block.py`，
产出的 SHA-256 和 `apksigner verify --print-certs` 逐字节一致。

## 打包上的两个坑

- **split 里的 `libapp.so` 是就地改的**，只补 CRC（本地头 + 中央目录各一处），
  不重建 zip。因为 `extractNativeLibs=false` 要求 .so 未压缩且页对齐，重建会把
  对齐用的 padding 弄丢；就地改则每个偏移都原封不动。见 `tools/zipedit.py`。
- **`zipalign -P 16` 和 `-p` 互斥**，前者才是 16KB 页（Android 15 要）。
  签名要显式传 `--min-sdk-version`，否则 apksigner 按 API 1 处理，
  会要求这几个包必须有 v1 签名。

## 已验证

在 Hills 1.7.2 (versionCode 4110) / HyperOS 上跑通，模块完全没参与
（logcat 里 `ChuanyiHooker` 命中 0 次）：

```
[patch] verification endpoint listening on 127.0.0.1:45872
[patch] learned one-time product id: hills.pro
[patch] learned one-time product id: hills.pro.lifetime
[patch] learned one-time product id: hills.pro.lifetime.discount3
[patch] injected hills.pro.lifetime into the purchase list
[patch] verify -> granted (req_jti=…)
```

界面显示「Hills Pro / 已解锁所有功能」。

## 注意

签名跟原版不同，**装之前必须先卸载**，应用数据会一起没。先备份：

```bash
adb shell "su -c 'cp -a /data/data/com.mountains.hills /data/local/tmp/hills_backup'"
# 装完之后（uid 变了，要改属主并重打 SELinux 标签）
NEW=$(adb shell "su -c 'stat -c %u /data/data/com.mountains.hills'")
adb shell "su -c 'rm -rf /data/data/com.mountains.hills/* \
  && cp -a /data/local/tmp/hills_backup/. /data/data/com.mountains.hills/ \
  && chown -R $NEW:$NEW /data/data/com.mountains.hills \
  && restorecon -R /data/data/com.mountains.hills'"
```
