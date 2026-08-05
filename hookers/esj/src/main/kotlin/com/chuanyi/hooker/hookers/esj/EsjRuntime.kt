package com.chuanyi.hooker.hookers.esj

import org.json.JSONArray
import org.json.JSONObject

/**
 * 注入到 Cocos JS 引擎里的运行时。
 *
 * ## 为什么全部做在游戏自己的 JS 引擎里
 *
 * 三样东西决定了这个位置最划算：
 *
 * 1. **协议帧最终由 `WebSocket.prototype.send(ArrayBuffer)` 发出**，而 Cocos 的
 *    `WebSocket` 在 JS 层就是个普通全局类，换掉一行就够。往下走到原生层反而更难：
 *    `libcocos.so` 有 30 MB 且 strip 过，`WebSocket::send` 不在导出表里。
 * 2. **游戏自带完整的 protobuf 编解码器**。`proto-bundle.js` 里那 1338 个消息
 *    （`protocol.LoginResponse.encode(...)` 之类）是现成的 —— 要伪造服务端响应，
 *    这是最值钱的资产。换到外挂的 QuickJS 之类去做，等于把这 1338 个定义重写一遍。
 * 3. **配置表也在同一个堆里**。游戏把 431 个配置文件加载成 JS 对象，本地服务端
 *    要算数值时直接取。
 *
 * ## 分工：内置运行时 vs 外部脚本
 *
 * 这个文件里的是**引导器与地基**，编译进模块，不常改：帧编解码、拦截、模块等待、
 * 伪 WebSocket、录制、以及暴露给外部的 `ESJ` 接口。
 *
 * 真正的**本地服务端业务逻辑**放在设备上的 [Esj.EXTERNAL_SCRIPT]，由引导器用
 * `jsb.fileUtils` 读进来执行。改业务逻辑 = 推一个 js 文件 + 重进游戏，
 * 不用重新编译模块。实测过了：引擎能读应用外部目录，`adb push` 也不需要 root。
 *
 * ## 帧改完要重算长度
 *
 * 帧头第 0..3 字节是 `payloadLen = 总长 - 4`。varint 改大以后字节数会变
 * （100 是 1 字节，10000 是 2 字节），长度字段不跟着改，服务端会按旧长度截断，
 * 后面所有帧的边界全乱。`rebuild` 每次都重算。
 *
 * ## 没有签名
 *
 * `ProtocolCodec.encode` 从头到尾没有算过 HMAC，body 也不参与任何校验。
 * 所以改写不需要重新签名 —— 这是这个目标上一切改写能成立的前提。
 */
internal object EsjRuntime {

    /**
     * 生成完整的注入源码。
     *
     * @param logProtocol 打开协议观察台
     * @param boost 打开上报数值改写
     * @param keepAlive 打开断线容错
     * @param drill 打开改写链路演练
     * @param record 录制服务端响应，供离线回放
     * @param offline 离线模式：切断真实连接，改由本地服务端应答
     * @param multiplier 上报值的放大倍数
     * @param maxValue 放大后的封顶值
     * @param generation 模块代号，热重载后重新注入用
     */
    fun build(
        logProtocol: Boolean,
        boost: Boolean,
        keepAlive: Boolean,
        drill: Boolean,
        record: Boolean,
        offline: Boolean,
        multiplier: Int,
        maxValue: Int,
        generation: Long,
    ): String {
        val rules = JSONObject()
        Esj.REPORTED_FIELDS.forEach { f ->
            rules.put(f.message, JSONObject().put("tag", f.tag).put("field", f.field))
        }
        val watch = JSONArray().apply { Esj.WATCH_ONLY.forEach { put(it) } }

        val cfg = JSONObject()
            .put("gen", generation)
            .put("log", logProtocol)
            .put("boost", boost)
            .put("alive", keepAlive)
            .put("drill", drill)
            .put("rec", record)
            .put("offline", offline)
            .put("mult", multiplier)
            .put("max", maxValue)
            .put("rules", rules)
            .put("watch", watch)
            .put("script", Esj.EXTERNAL_SCRIPT)
            .put("evLog", Esj.EVENT_LOG)
            .put("evReady", Esj.EVENT_READY)
            .put("evRec", Esj.EVENT_RECORD)

        return SOURCE.replace("__ESJ_CONFIG__", cfg.toString())
    }

    /**
     * 运行时本体。
     *
     * 全程不抛异常：任何一处出问题都只是少一项功能，绝不能让游戏自己的 `send`
     * 收不到数据。所以每个对外入口都包了 try/catch，改写失败一律返回原始帧。
     */
    private val SOURCE = """
(function () {
  'use strict';
  var CFG = __ESJ_CONFIG__;
  var G = (typeof globalThis !== 'undefined') ? globalThis : this;

  if (G.__ESJ__ && G.__ESJ__.gen === CFG.gen) { say(CFG.evReady, '已就绪'); return; }

  function say(evt, msg) {
    try { jsb.bridge.sendToNative(evt, String(msg)); } catch (e) {}
  }
  function log(msg) { say(CFG.evLog, msg); }

  // ---------------------------------------------------------------- varint

  function readVarint(u8, pos) {
    var r = 0, shift = 0, b;
    do {
      if (pos >= u8.length) return null;
      b = u8[pos++];
      r += (shift < 28) ? ((b & 0x7f) << shift) : ((b & 0x7f) * Math.pow(2, shift));
      shift += 7;
      if (shift > 70) return null;
    } while (b & 0x80);
    return { value: r, next: pos };
  }

  function writeVarint(v) {
    var out = [];
    v = Math.floor(v);
    if (v < 0) v = 0;
    while (v > 127) { out.push((v % 128) | 0x80); v = Math.floor(v / 128); }
    out.push(v & 0x7f);
    return out;
  }

  function scanFields(u8) {
    var pos = 0, out = [];
    while (pos < u8.length) {
      var k = readVarint(u8, pos);
      if (!k) break;
      var tag = Math.floor(k.value / 8), wire = k.value % 8, vs = k.next, ve;
      if (wire === 0) { var v = readVarint(u8, vs); if (!v) break; ve = v.next; }
      else if (wire === 1) { ve = vs + 8; }
      else if (wire === 2) { var L = readVarint(u8, vs); if (!L) break; ve = L.next + L.value; }
      else if (wire === 5) { ve = vs + 4; }
      else break;
      if (ve > u8.length) break;
      out.push({ tag: tag, wire: wire, valStart: vs, end: ve });
      pos = ve;
    }
    return out;
  }

  function patchVarint(u8, tag, mapper) {
    var fs = scanFields(u8), i, f;
    for (i = 0; i < fs.length; i++) {
      f = fs[i];
      if (f.tag !== tag || f.wire !== 0) continue;
      var cur = readVarint(u8, f.valStart);
      if (!cur) return null;
      var nv = mapper(cur.value);
      if (nv === cur.value) return null;
      var nb = writeVarint(nv);
      var out = new Uint8Array(f.valStart + nb.length + (u8.length - f.end));
      out.set(u8.subarray(0, f.valStart), 0);
      out.set(nb, f.valStart);
      out.set(u8.subarray(f.end), f.valStart + nb.length);
      return { buf: out, from: cur.value, to: nv };
    }
    var nv2 = mapper(0);
    if (!nv2) return null;
    var key = writeVarint(tag * 8), val = writeVarint(nv2);
    var out2 = new Uint8Array(u8.length + key.length + val.length);
    out2.set(u8, 0);
    out2.set(key, u8.length);
    out2.set(val, u8.length + key.length);
    return { buf: out2, from: 0, to: nv2 };
  }

  // ----------------------------------------------------------------- 帧

  function parseFrame(ab) {
    if (!(ab instanceof ArrayBuffer) || ab.byteLength < 12) return null;
    var dv = new DataView(ab);
    if (dv.getInt8(4) !== 2) return null;
    var nlen = dv.getUint8(9);
    if (nlen === 0 || nlen > 127 || 10 + nlen + 1 > ab.byteLength) return null;
    var name = '', i;
    for (i = 0; i < nlen; i++) {
      var c = dv.getUint8(10 + i);
      if (c < 32 || c > 126) return null;
      name += String.fromCharCode(c);
    }
    return { name: name, seq: dv.getInt32(5), comp: dv.getUint8(10 + nlen), bodyStart: 10 + nlen + 1 };
  }

  function rebuild(ab, f, body) {
    var out = new Uint8Array(f.bodyStart + body.length);
    out.set(new Uint8Array(ab, 0, f.bodyStart), 0);
    out.set(body, f.bodyStart);
    new DataView(out.buffer).setInt32(0, out.length - 4);
    return out.buffer;
  }

  // 从零造一帧。seq 默认给 0 是有讲究的：ReliableMessageDelivery.handleSeqNo
  // 里第一步是 `if (N(seqNo))`，而 N 要求 seqNo > 0 —— 也就是说 **seq 为 0 的
  // 下行帧完全跳过连续性检查**，不会被判「不连续」丢弃，也不用跟着服务端的
  // 序号往前推。游戏自己的 Ack/Nack/Ping 走的也是这条路（encodeNoSeqNo）。
  function buildFrame(name, body, seq) {
    var nlen = name.length, total = 10 + nlen + 1 + body.length, i;
    var ab = new ArrayBuffer(total), dv = new DataView(ab), u8 = new Uint8Array(ab);
    dv.setInt32(0, total - 4);
    dv.setInt8(4, 2);
    dv.setInt32(5, seq || 0);
    dv.setUint8(9, nlen);
    for (i = 0; i < nlen; i++) dv.setUint8(10 + i, name.charCodeAt(i));
    dv.setUint8(10 + nlen, 0);
    u8.set(body, 10 + nlen + 1);
    return ab;
  }

  // --------------------------------------------------------------- base64

  var B64C = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  function b64(u8) {
    var out = '', i, l = u8.length, b0, b1, b2;
    for (i = 0; i < l; i += 3) {
      b0 = u8[i]; b1 = u8[i + 1]; b2 = u8[i + 2];
      out += B64C.charAt(b0 >> 2);
      out += B64C.charAt(((b0 & 3) << 4) | ((b1 === undefined ? 0 : b1) >> 4));
      out += (b1 === undefined) ? '=' : B64C.charAt(((b1 & 15) << 2) | ((b2 === undefined ? 0 : b2) >> 6));
      out += (b2 === undefined) ? '=' : B64C.charAt(b2 & 63);
    }
    return out;
  }
  function unb64(s) {
    var i, c, buf = 0, bits = 0, out = [];
    for (i = 0; i < s.length; i++) {
      c = B64C.indexOf(s.charAt(i));
      if (c < 0) continue;
      buf = (buf << 6) | c; bits += 6;
      if (bits >= 8) { bits -= 8; out.push((buf >> bits) & 0xff); }
    }
    return new Uint8Array(out);
  }

  // ------------------------------------------------------------- 模块等待

  // SystemJS 的 registry 里只有**已经实例化**的模块。注入发生在引擎第一帧，
  // 那时业务链路还没跑起来，registry 里只有寥寥几个。所以凡是要拿游戏模块的
  // 地方都得等 —— 而不是取一次拿不到就放弃。
  function findModule(needle) {
    try {
      if (typeof System === 'undefined') return null;
      if (typeof System.entries === 'function') {
        var it = System.entries(), n = it.next();
        while (!n.done) {
          if (String(n.value[0]).indexOf(needle) >= 0) return n.value[1];
          n = it.next();
        }
      }
      var m = System.get('chunks:///_virtual/' + needle + '.js');
      if (m) return m;
    } catch (e) {}
    return null;
  }

  var waiters = [];
  var pollTimer = null;
  function whenModule(needle, test, cb, label) {
    waiters.push({ needle: needle, test: test, cb: cb, label: label, tries: 0 });
    if (pollTimer === null) pollTimer = setInterval(pump, 500);
  }
  function pump() {
    var alive = [], i, w, m;
    for (i = 0; i < waiters.length; i++) {
      w = waiters[i];
      m = findModule(w.needle);
      if (m && (!w.test || w.test(m))) {
        try { w.cb(m); } catch (e) { log(w.label + ' 装载出错 ' + e); }
        continue;
      }
      if (++w.tries < 120) alive.push(w);
      else log(w.label + '：等了 60 秒仍拿不到 ' + w.needle + ' 模块');
    }
    waiters = alive;
    if (!waiters.length && pollTimer !== null) { clearInterval(pollTimer); pollTimer = null; }
  }

  // ------------------------------------------------------------ 协议库句柄

  var protocol = null;
  whenModule('proto-bundle', function (m) { return !!m.protocol; }, function (m) {
    protocol = m.protocol;
    ESJ.protocol = protocol;
    var n = 0; for (var k in protocol) n++;
    log('协议库就绪：' + n + ' 个消息定义可用');
    if (pendingServerBoot) { pendingServerBoot = false; loadExternal(); }
  }, '协议库');

  // 用协议库把对象编成 body。拿不到库时返回 null —— 调用方自己决定退路。
  function encodeMsg(name, obj) {
    if (!protocol || !protocol[name]) return null;
    try { return protocol[name].encode(obj || {}).finish(); } catch (e) { log('编码 ' + name + ' 失败 ' + e); return null; }
  }
  function decodeMsg(name, u8) {
    if (!protocol || !protocol[name]) return null;
    try { return protocol[name].decode(u8); } catch (e) { return null; }
  }

  // ------------------------------------------------------------- 统计与规则

  var WATCH = {};
  (function () { for (var i = 0; i < CFG.watch.length; i++) WATCH[CFG.watch[i]] = 1; })();

  var stat = { up: 0, down: 0, patched: 0, served: 0 };
  var drillDone = false;

  function tryDrill(ab, f) {
    if (!CFG.drill || drillDone || f.comp !== 0) return null;
    if (f.name === 'PingRequest' || f.name === 'LoginRequest' || f.name === 'Ack' || f.name === 'Nack') return null;
    if (f.name.slice(-7) !== 'Request') return null;
    drillDone = true;
    var body = new Uint8Array(ab.slice(f.bodyStart));
    var r = patchVarint(body, 1000, function (o) { return o === 0 ? 12345 : o; });
    if (!r) return null;
    log('链路演练：给 ' + f.name + ' 补了一个服务器会跳过的未知字段，' +
        ab.byteLength + '字节 改成 ' + (f.bodyStart + r.buf.length) + '字节。' +
        '接下来若能正常收到回包，说明改写在真实链路上成立');
    return rebuild(ab, f, r.buf);
  }

  var QUIET = { PingRequest: 1, PingResponse: 1, Ack: 1, Nack: 1 };

  function handleUp(ab) {
    var f = parseFrame(ab);
    if (!f) return null;
    stat.up++;
    if (CFG.log && !QUIET[f.name]) {
      log('发出 ' + f.name + '  序号' + f.seq + '  ' + ab.byteLength + '字节' +
          (WATCH[f.name] ? '  【客户端上报，值得盯】' : ''));
    }
    if (CFG.rec && !QUIET[f.name]) record('U', f.name, ab);

    var rule = CFG.rules[f.name];
    if (!rule) return tryDrill(ab, f);
    if (!CFG.boost || f.comp !== 0) return null;

    var body = new Uint8Array(ab.slice(f.bodyStart));
    var res = patchVarint(body, rule.tag, function (old) {
      var v = Math.floor(old * CFG.mult);
      if (v > CFG.max) v = CFG.max;
      if (v < old) v = old;
      return v;
    });
    if (!res) return null;
    stat.patched++;
    log('改写 ' + f.name + '.' + rule.field + '：' + res.from + ' 改成 ' + res.to);
    return rebuild(ab, f, res.buf);
  }

  function handleDown(ab) {
    var f = parseFrame(ab);
    if (!f) return;
    stat.down++;
    if (CFG.rec && !QUIET[f.name]) record('D', f.name, ab);
    if (!CFG.log || QUIET[f.name]) return;
    log('收到 ' + f.name + '  序号' + f.seq + '  ' + ab.byteLength + '字节' + (f.comp ? '  已压缩' : ''));
  }

  // 录制走 Java 侧落盘：JS 这边没有可靠的追加写，而 Java 有；顺带也不用担心
  // 游戏退出时缓冲区没刷。量不大，一次登录也就百来条。
  function record(dir, name, ab) {
    try { say(CFG.evRec, dir + '|' + name + '|' + b64(new Uint8Array(ab))); } catch (e) {}
  }

  // 下行必须每次 send 都重新确认包装还在：transport 握手时先挂一个临时
  // onmessage 等认证回包，认证过了立刻换成正式的 —— 只认「包过没有」会被覆盖掉。
  function wrapDown(sock) {
    try {
      if (!sock) return;
      var cur = sock.onmessage;
      if (typeof cur !== 'function' || cur.__esjWrap) return;
      var wrapped = function (ev) {
        try { if (ev && ev.data) handleDown(ev.data); } catch (e) {}
        return cur.apply(this, arguments);
      };
      wrapped.__esjWrap = true;
      sock.onmessage = wrapped;
    } catch (e) {}
  }

  // -------------------------------------------------------- 伪 WebSocket

  // 离线模式下顶替引擎的 WebSocket。transport 只用到这几样：
  // readyState / binaryType / bufferedAmount / send / close / on{open,message,close,error}，
  // 外加静态常量 WebSocket.OPEN（`this.socket.readyState === WebSocket.OPEN`）。
  function FakeSocket(url) {
    this.url = url;
    this.readyState = 0;
    this.binaryType = 'arraybuffer';
    this.bufferedAmount = 0;
    this.onopen = null; this.onmessage = null; this.onclose = null; this.onerror = null;
    var self = this;
    setTimeout(function () {
      if (self.readyState !== 0) return;
      self.readyState = 1;
      if (self.onopen) { try { self.onopen({ type: 'open' }); } catch (e) { log('伪连接 onopen 出错 ' + e); } }
    }, 1);
  }
  FakeSocket.CONNECTING = 0; FakeSocket.OPEN = 1; FakeSocket.CLOSING = 2; FakeSocket.CLOSED = 3;
  FakeSocket.prototype.send = function (data) {
    if (this.readyState !== 1) return;
    var self = this;
    // 异步回，模仿真实网络：同步回包会在 transport 还没把 onmessage 挂好时就送达。
    setTimeout(function () { serveOffline(self, data); }, 0);
  };
  FakeSocket.prototype.close = function (code, reason) {
    if (this.readyState === 3) return;
    this.readyState = 3;
    if (this.onclose) { try { this.onclose({ code: code || 1000, reason: reason || '' }); } catch (e) {} }
  };
  FakeSocket.prototype.addEventListener = function () {};
  FakeSocket.prototype.removeEventListener = function () {};

  // 把一帧塞进游戏的收信口。走 onmessage 而不是别的路，是因为 transport
  // 认证阶段和正常阶段挂的是不同的 onmessage，直接调它才两个阶段都通。
  function deliver(sock, ab) {
    if (!sock || !sock.onmessage) return false;
    try { sock.onmessage({ data: ab, type: 'message' }); return true; }
    catch (e) { log('回包投递失败 ' + e); return false; }
  }

  var serverHandler = null;   // 由外部脚本注册的本地服务端

  function serveOffline(sock, data) {
    if (!(data instanceof ArrayBuffer)) return;
    var f = parseFrame(data);
    if (!f) return;
    stat.up++;
    if (CFG.log && !QUIET[f.name]) log('离线收到请求 ' + f.name + '  序号' + f.seq);

    // 心跳由运行时兜底，外部脚本不用管：不回 pong，transport 一秒就判超时重连。
    if (f.name === 'PingRequest') {
      var req = decodeMsg('PingRequest', new Uint8Array(data.slice(f.bodyStart)));
      var ts = (req && req.clientTimestamp) || Date.now();
      var body = encodeMsg('PingResponse', { clientTimestamp: ts, serverTimestamp: Date.now() });
      if (body) deliver(sock, buildFrame('PingResponse', body, 0));
      return;
    }
    if (f.name === 'Ack' || f.name === 'Nack') return;

    if (!serverHandler) {
      log('离线：还没有本地服务端接管 ' + f.name + '（外部脚本没加载或没注册）');
      return;
    }
    var body2 = null;
    try { body2 = new Uint8Array(data.slice(f.bodyStart)); } catch (e) { return; }
    try {
      serverHandler({
        name: f.name,
        seq: f.seq,
        raw: data,
        body: body2,
        decode: function () { return decodeMsg(f.name, body2); },
        reply: function (name, obj) { return ESJ.reply(sock, name, obj); },
        replyRaw: function (ab) { return deliver(sock, ab); },
      });
      stat.served++;
    } catch (e) {
      log('本地服务端处理 ' + f.name + ' 时出错：' + e);
    }
  }

  // ------------------------------------------------------- 对外接口 ESJ

  var ESJ = {
    gen: CFG.gen,
    cfg: CFG,
    stat: stat,
    log: log,
    protocol: null,
    offline: !!CFG.offline,

    frame: {
      parse: parseFrame,
      build: buildFrame,
      rebuild: rebuild,
    },
    pb: {
      encode: encodeMsg,
      decode: decodeMsg,
      varint: { read: readVarint, write: writeVarint, patch: patchVarint, scan: scanFields },
    },
    b64: b64,
    unb64: unb64,

    /** 注册本地服务端。外部脚本调用它接管全部离线请求。 */
    serve: function (fn) { serverHandler = fn; log('本地服务端已注册'); },

    /** 造一条下行响应发给游戏。seq 留 0，跳过连续性检查。 */
    reply: function (sock, name, obj) {
      var body = encodeMsg(name, obj);
      if (!body) { log('回包 ' + name + ' 失败：协议库里没有这个消息'); return false; }
      return deliver(sock, buildFrame(name, body, 0));
    },

    /** 读写外部文件，给存档用。 */
    file: {
      read: function (p) { try { return jsb.fileUtils.getStringFromFile(p); } catch (e) { return null; } },
      write: function (p, s) { try { return jsb.fileUtils.writeStringToFile(String(s), p); } catch (e) { log('写文件失败 ' + e); return false; } },
      exists: function (p) { try { return jsb.fileUtils.isFileExist(p); } catch (e) { return false; } },
      dir: function () { try { return jsb.fileUtils.getWritablePath(); } catch (e) { return ''; } },
    },

    /** 等某个游戏模块实例化后回调。 */
    whenModule: function (needle, cb) { whenModule(needle, null, cb, '外部脚本'); },
    findModule: findModule,

    dump: function () {
      log('统计 上行' + stat.up + ' 下行' + stat.down + ' 改写' + stat.patched + ' 本地应答' + stat.served);
    },
  };
  G.ESJ = ESJ;

  // ------------------------------------------------------------ 挂 send

  if (typeof WebSocket === 'undefined') { say(CFG.evReady, '失败：引擎里没有 WebSocket'); return; }

  var RealWS = G.__esjRealWS || G.WebSocket;
  G.__esjRealWS = RealWS;

  var proto = RealWS.prototype;
  var origSend = proto.__esjOrigSend || proto.send;
  proto.__esjOrigSend = origSend;
  proto.send = function (data) {
    try {
      wrapDown(this);
      if (data instanceof ArrayBuffer) {
        var r = handleUp(data);
        if (r) data = r;
      }
    } catch (e) {
      log('处理上行时出错，已按原样发出：' + e);
    }
    return origSend.call(this, data);
  };

  if (CFG.offline) {
    // 换掉全局构造器，游戏再 new WebSocket 拿到的就是本地回环，一个包都不出去。
    G.WebSocket = FakeSocket;
    log('离线模式：已接管 WebSocket，游戏不会再连服务器');
  } else {
    G.WebSocket = RealWS;
  }

  // -------------------------------------------------- 断线容错（延迟装）

  function installKeepAlive(mod) {
    var P = mod.WebSocketTransport.prototype;
    if (P.__esjAlive === CFG.gen) return;
    P.__esjAlive = CFG.gen;
    // 原函数存一份再包：热重载换代号后这里会重新装，直接读 P.giveUp 会把上一代的
    // 包装当成原函数，每重载一次就多套一层。
    var origGiveUp = P.__esjOrigGiveUp || P.giveUp;
    P.__esjOrigGiveUp = origGiveUp;
    P.giveUp = function () {
      try {
        log('网络中断，已拦下「无法与服务器通信」，继续重连');
        this.fd.lastHeartbeatTimestamp = Date.now();
        this.reconnect();
      } catch (e) {
        return origGiveUp.apply(this, arguments);
      }
    };
    say(CFG.evReady, '断线容错已就位');
  }
  if (CFG.alive) {
    whenModule('WebsocketTransport', function (m) { return !!m.WebSocketTransport; }, installKeepAlive, '断线容错');
  }

  // ------------------------------------------------------------ 外部脚本

  var pendingServerBoot = false;

  function loadExternal() {
    var p = CFG.script;
    if (!ESJ.file.exists(p)) {
      log('外部脚本不存在：' + p + '（离线模式需要它提供本地服务端）');
      return;
    }
    var src = ESJ.file.read(p);
    if (!src) { log('外部脚本读到空内容：' + p); return; }
    try {
      (new Function('ESJ', src))(ESJ);
      log('外部脚本已加载（' + src.length + ' 字节）');
    } catch (e) {
      log('外部脚本执行出错：' + e);
    }
  }

  // 外部脚本几乎一定要用 protocol，所以等协议库就绪再加载；
  // 已经就绪就立刻加载。
  if (protocol) loadExternal(); else pendingServerBoot = true;

  // --------------------------------------------------------------- 自检

  function selfTest() {
    var out = [];
    var a = patchVarint(new Uint8Array([0x08, 0x64, 0x18, 0x64]), 3, function (o) { return o * 5; });
    out.push((a && a.from === 100 && a.to === 500 && a.buf.length === 5 &&
              a.buf[3] === 0xF4 && a.buf[4] === 0x03) ? '改值正常' : '改值异常');
    var b = patchVarint(new Uint8Array([0x08, 0x64]), 3, function (o) { return o === 0 ? 777 : o; });
    out.push((b && b.buf.length === 5 && b.buf[2] === 0x18 &&
              b.buf[3] === 0x89 && b.buf[4] === 0x06) ? '补写正常' : '补写异常');
    var name = 'DanceEndRequest', body = new Uint8Array([0x08, 0x64, 0x18, 0x64]);
    var ab = buildFrame(name, body, 1);
    var f = parseFrame(ab);
    var ok = !!f && f.name === name && f.bodyStart === 10 + name.length + 1 &&
             new DataView(ab).getInt32(0) === ab.byteLength - 4;
    if (ok && a) {
      var rb = rebuild(ab, f, a.buf);
      ok = rb.byteLength === ab.byteLength + 1 &&
           new DataView(rb).getInt32(0) === rb.byteLength - 4;
    }
    out.push(ok ? '整帧收发正常' : '整帧收发异常');
    var rt = b64(new Uint8Array([1, 2, 3, 250, 251, 252]));
    var back = unb64(rt);
    out.push((back.length === 6 && back[5] === 252) ? '录制编码正常' : '录制编码异常');
    return out.join('，');
  }

  // --------------------------------------------------------------- 收尾

  G.__ESJ__ = { gen: CFG.gen, stat: stat, api: ESJ };

  var check = '自检未跑';
  try { check = selfTest(); } catch (e) { check = '自检出错 ' + e; }

  say(CFG.evReady, '注入完成：' +
      (CFG.offline ? '离线模式' : '联机模式') +
      '，观察台' + (CFG.log ? '开' : '关') +
      '，上报改写' + (CFG.boost ? '开(x' + CFG.mult + ')' : '关') +
      '，录制' + (CFG.rec ? '开' : '关') + '；' + check);
})();
""".trimIndent()
}
