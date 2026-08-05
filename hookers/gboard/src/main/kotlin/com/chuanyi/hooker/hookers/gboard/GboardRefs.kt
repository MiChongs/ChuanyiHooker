package com.chuanyi.hooker.hookers.gboard

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.chuanyi.hooker.core.HookScope
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Gboard 里那些被 R8 重命名的类与成员，按**形状**在运行时认出来。
 *
 * 17.8.4 里剪贴板整包都成了两三个字母（`ClipboardAdapter` -> `fki`），写死名字等于
 * 每次 Gboard 更新都要重开一次 jadx。这里一个混淆后的名字都不写，靠三种线索：
 *
 * 1. **Flogger 的 TAG**。每个类的静态初始化里有
 *    `wbu.i("com/google/android/apps/inputmethod/libs/clipboard/ClipboardAdapter")`
 *    这么一行 —— 原始全限定名以字符串形式留在包里。类名可以变，这个串不会，
 *    它是 Google 内部日志系统的定位信息。用 DexKit 按它找类（见 [GboardDex]）。
 * 2. **签名唯一性**。找到类之后成员按参数与返回类型认：
 *    `(Context, String, String[], String)` 在那个工具类里只有一个。
 * 3. **未混淆的公开类**。键盘元数据那一层（`SoftKeyDef` / `ActionDef`）因为要被
 *    moshi 适配器按名字反射，名字原样保留 —— 那部分可以直接写。
 *
 * 每一组都是 `lazy`：只用滑动功能时不该因为剪贴板那几个类没扫到而整个失败。
 */
internal class GboardRefs private constructor(private val scope: HookScope) {

    // --- 剪贴板 -------------------------------------------------------------

    /**
     * `ClipboardContentProviderUtils`：剪贴板所有读写的唯一出入口。
     *
     * 验证条件就是我们真正要用的那个方法 —— R8 把不少 lambda 合并进了共享类，
     * 那些类身上也带着这个 TAG，只认串会挑错。
     */
    val clipUtils: Class<*> by lazy {
        GboardDex.classByString(scope, TAG_CP_UTILS) { candidate ->
            candidate.declaredMethods.any { m ->
                m.parameterTypes.contentEquals(
                    arrayOf(
                        Context::class.java, String::class.java,
                        Array<String>::class.java, String::class.java,
                    ),
                )
            }
        } ?: error("找不到剪贴板工具类")
    }

    /**
     * `queryItems(Context, selection, args, order) -> ImmutableList<ClipItem>`。
     * 那个类里唯一收这四个参数的方法。
     */
    val queryItems: Method by lazy {
        clipUtils.pick("剪贴板查询") {
            it.parameterTypes.contentEquals(
                arrayOf(Context::class.java, String::class.java, Array<String>::class.java, String::class.java),
            )
        }
    }

    /** `buildUri(Context, matchCode, id) -> Uri`：clips 表的 content URI。 */
    val buildUri: Method by lazy {
        clipUtils.pick("剪贴板 URI") {
            it.returnType == Uri::class.java && it.parameterTypes.contentEquals(
                arrayOf(Context::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType),
            )
        }
    }

    /** `ClipItem`：由 `readItem(Context, Cursor)` 的返回类型给出，不用猜名字。 */
    val clipItem: Class<*> by lazy {
        clipUtils.pick("剪贴板读取") {
            it.parameterTypes.contentEquals(arrayOf(Context::class.java, Cursor::class.java))
        }.returnType
    }

    /**
     * 列表里的三个段头（「最近」「已固定」「最近使用」），已按出现顺序排好。
     *
     * 它们是 `ClipItem` 里仅有的三个自身类型的静态字段，但**顺序不能假定** ——
     * 所以再验一道：段头的 viewType 分别是 1/2/3，而 `ClipItem` 上返回 int 的无参
     * 方法里，只有 viewType 那个对三者给出 {1,2,3}（其余读的是 entityType /
     * itemType，段头身上全是 0）。这一步同时认出了方法和顺序。
     */
    private val separators: List<Any> by lazy {
        val fields = clipItem.declaredFields.filter {
            Modifier.isStatic(it.modifiers) && it.type == clipItem
        }.onEach { it.isAccessible = true }
        require(fields.size == 3) { "剪贴板段头字段数是 ${fields.size}，不是 3" }
        val values = fields.mapNotNull { it.get(null) }

        val viewType = clipItem.declaredMethods.firstOrNull { m ->
            m.parameterCount == 0 && m.returnType == Int::class.javaPrimitiveType &&
                runCatching {
                    m.isAccessible = true
                    values.map { m.invoke(it) as Int }.toSet() == setOf(1, 2, 3)
                }.getOrDefault(false)
        } ?: error("认不出剪贴板段头的类型方法")

        values.sortedBy { viewType.invoke(it) as Int }
    }

    /**
     * `ClipItem.timestamp`：唯一的 `Instant` 字段（脱糖后是 `j$.time.Instant`，
     * 所以按简单名认而不是按类比较）。
     *
     * 一次复制可能产生同一时间戳的**多条**（文本里识别出的电话、网址各算一条），
     * 原版的「最近 5 条」数的是不同时间戳的**组数**，也就是「最近 5 次复制」——
     * 要跟上这个语义就得读到它。
     */
    private val timestampField: Field by lazy {
        clipItem.field("时间戳") { it.type.simpleName == "Instant" }
    }

    fun timestampOf(item: Any): Any? = timestampField.get(item)

    /** 「最近」段头，列表第 0 项。 */
    val sepRecent: Any get() = separators[0]

    /** 「已固定」段头。 */
    val sepPinned: Any get() = separators[1]

    /** 第三段（截图等）的段头。 */
    val sepOther: Any get() = separators[2]

    // --- Flag ---------------------------------------------------------------

    /**
     * 按名字取一个 flag 并覆盖它的取值。
     *
     * 装 flag 的类以 flag 名本身为锚点。flag 是接口，实现上恰好只有一个
     * `() -> String`（取名字）和一个 `(Object) -> void`（覆盖取值，写进优先级最高的
     * OVERRIDE 档）—— 两个都按签名认，不碰混淆后的方法名。
     */
    fun overrideFlag(name: String, value: Any): Boolean {
        val holder = GboardDex.classByString(scope, name) { candidate ->
            // flag 表都是「一堆接口类型的静态字段」，别的引用过这个串的类不长这样。
            candidate.declaredFields.count { Modifier.isStatic(it.modifiers) && it.type.isInterface } > 0
        } ?: return false
        return holder.declaredFields.asSequence()
            .filter { Modifier.isStatic(it.modifiers) && it.type.isInterface }
            .any { field ->
                runCatching {
                    field.isAccessible = true
                    val target = field.get(null) ?: return@runCatching false
                    val type = field.type
                    val getName = type.methods.single {
                        it.parameterCount == 0 && it.returnType == String::class.java
                    }
                    if (getName.invoke(target) != name) return@runCatching false
                    val setter = type.methods.single {
                        it.returnType == Void.TYPE &&
                            it.parameterTypes.contentEquals(arrayOf(Any::class.java))
                    }
                    setter.invoke(target, value)
                    true
                }.getOrDefault(false)
            }
    }

    // --- 输入框类型 ---------------------------------------------------------

    /**
     * `EditorInfoUtil`：二十多个「这个输入框是不是某某类型」的静态谓词。
     *
     * 验证条件取这类谓词的数量 —— R8 的共享类身上也可能带着这个 TAG，
     * 但不会同时带一批 `(EditorInfo) -> boolean`。
     */
    private val editorInfoUtil: Class<*> by lazy {
        GboardDex.classByString(scope, TAG_EDITOR_INFO_UTIL) { it.editorPredicates().size >= 5 }
            ?: error("找不到 EditorInfo 工具类")
    }

    /**
     * `isTypeNull(EditorInfo)` —— `editorInfo == null || editorInfo.inputType == 0`。
     *
     * 这个类里同签名的谓词有二十多个，名字全被混淆了，也没有字符串可当锚点，
     * 所以按**行为**认：其余谓词一律写成 `editorInfo == null ? false : …`，
     * 只有它**对 null 返回 true**。再补三个探针把 inputType 那一半也钉死，
     * 四点合起来唯一。
     */
    val isTypeNull: Method by lazy {
        val probes = listOf(
            null to true,
            EditorInfo() to true, // inputType 默认就是 TYPE_NULL
            EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT } to false,
            EditorInfo().apply { inputType = InputType.TYPE_CLASS_NUMBER } to false,
        )
        editorInfoUtil.editorPredicates().firstOrNull { method ->
            runCatching {
                probes.all { (arg, want) -> method.invoke(null, *arrayOf<Any?>(arg)) == want }
            }.getOrDefault(false)
        } ?: error("认不出「输入框类型为空」的判断")
    }

    /**
     * `InputBundleManager.loadActiveInputBundleId()`：挑当前该用哪套键盘。
     *
     * 它无参，而同一个类里另一个无参方法返回的是同一个类型（当前已选中的那套），
     * 光看签名分不开 —— 所以用它日志里的原始方法名定位。
     */
    val loadActiveBundle: Method by lazy {
        GboardDex.methodByStrings(
            scope, 0, TAG_INPUT_BUNDLE_MANAGER, GboardDex.ANCHOR_ACTIVE_BUNDLE,
        ) ?: error("找不到键盘选择方法")
    }

    private fun Class<*>.editorPredicates(): List<Method> = declaredMethods.filter {
        Modifier.isStatic(it.modifiers) && !it.isSynthetic &&
            it.returnType == Boolean::class.javaPrimitiveType &&
            it.parameterTypes.contentEquals(arrayOf(EditorInfo::class.java))
    }.onEach { it.isAccessible = true }

    // --- 键盘按键 -----------------------------------------------------------
    // 这一层没混淆：moshi 适配器要按名字反射，类名原样保留。

    val softKeyDef: Class<*> by lazy {
        scope.classOrNull(CLASS_SOFT_KEY_DEF) ?: error("找不到 $CLASS_SOFT_KEY_DEF")
    }

    val actionDef: Class<*> by lazy {
        scope.classOrNull(CLASS_ACTION_DEF) ?: error("找不到 $CLASS_ACTION_DEF")
    }

    /** `SoftKeyDef.actionDefs`：唯一 `ActionDef[]` 类型的字段。 */
    val actionsField: Field by lazy {
        softKeyDef.field("按键动作表") { it.type.isArray && it.type.componentType == actionDef }
    }

    /**
     * `SoftKeyDef.labels`：键面上印的字，唯一的 `CharSequence[]`。
     *
     * 第二个通常就是右上角那个小小的长按提示（字母键上印的数字）。它是**显示值**，
     * 未必等于按下去真正上屏的东西，所以只当兜底。
     */
    private val labelsField: Field by lazy {
        softKeyDef.field("键面文字") {
            it.type.isArray && it.type.componentType == CharSequence::class.java
        }
    }

    fun labelsOf(key: Any): Array<*> = labelsField.get(key) as? Array<*> ?: emptyArray<Any?>()

    /** `ActionDef.action`：唯一的枚举字段。它的类型就是那个动作枚举。 */
    private val actionField: Field by lazy {
        actionDef.field("动作类型") { Enum::class.java.isAssignableFrom(it.type) }
    }

    /** `ActionDef.keyDatas`：唯一「组件既非基本类型也非 String」的数组字段。 */
    private val keyDatasField: Field by lazy {
        actionDef.field("按键数据") {
            it.type.isArray && !it.type.componentType.isPrimitive &&
                it.type.componentType != String::class.java
        }
    }

    /** 动作枚举（PRESS / LONG_PRESS / SLIDE_UP / …）。常量名没被混淆。 */
    val actionEnum: Class<*> by lazy { actionField.type }

    /** KeyData：一次按键要产生什么。 */
    val keyData: Class<*> by lazy { keyDatasField.type.componentType }

    /**
     * `ActionDef` 的全参构造（动作 + 按键数据 + 一串开关）。
     *
     * 按**参数类型**认而不是数个数：另外两个构造分别收 `Parcel` 和 builder，
     * 只有这个以「动作枚举 + KeyData 数组」打头。参数个数是会随版本增删的。
     */
    val actionDefCtor: Constructor<*> by lazy {
        actionDef.declaredConstructors.firstOrNull {
            it.parameterCount >= 2 && it.parameterTypes[0] == actionEnum &&
                it.parameterTypes[1].isArray && it.parameterTypes[1].componentType == keyData
        }?.apply { isAccessible = true } ?: error("找不到 ActionDef 的构造")
    }

    /** `SoftKeyDef` 的全参构造：唯一直接收 `ActionDef[]` 的那个。 */
    val softKeyDefCtor: Constructor<*> by lazy {
        softKeyDef.declaredConstructors.firstOrNull { ctor ->
            ctor.parameterTypes.any { it.isArray && it.componentType == actionDef }
        }?.apply { isAccessible = true } ?: error("找不到 SoftKeyDef 的构造")
    }

    /** `KeyData(keyCode, intention, data)`。 */
    val keyDataCtor: Constructor<*> by lazy {
        keyData.declaredConstructors.firstOrNull {
            it.parameterCount == 3 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        }?.apply { isAccessible = true } ?: error("找不到 KeyData 的三参构造")
    }

    /** KeyData 的 `data`：唯一的 `Object` 字段，字母键上装的就是要上屏的字符。 */
    private val keyDataValue: Field by lazy {
        keyData.field("按键内容") { it.type == Any::class.java }
    }

    /** KeyData 的意图（DECODE / COMMIT）。构造新 KeyData 时照抄模板的。 */
    private val keyDataIntent: Field by lazy {
        keyData.field("按键意图") { Enum::class.java.isAssignableFrom(it.type) }
    }

    private val keyDataCode: Field by lazy {
        keyData.field("按键码") { it.type == Int::class.javaPrimitiveType }
    }

    fun actionOf(action: Any): Any? = actionField.get(action)

    fun keyDatasOf(action: Any): Array<*> = keyDatasField.get(action) as Array<*>

    fun dataOf(data: Any): Any? = keyDataValue.get(data)

    fun intentOf(data: Any): Any? = keyDataIntent.get(data)

    fun codeOf(data: Any): Int = keyDataCode.getInt(data)

    /** 按名字取动作枚举常量。常量名（`SLIDE_UP` 等）在 dex 里是原样的。 */
    @Suppress("UNCHECKED_CAST")
    fun action(name: String): Any? = runCatching {
        java.lang.Enum.valueOf(actionEnum as Class<out Enum<*>>, name)
    }.getOrNull()

    // --- 选择器 -------------------------------------------------------------
    // R8 会往类里加 synthetic 桥接，所以一律先滤掉再挑，而且只取第一个 ——
    // 用 single 的话多出一个桥接就是一次崩溃，而它并不影响判断。

    private fun Class<*>.pick(what: String, predicate: (Method) -> Boolean): Method =
        declaredMethods.firstOrNull { !it.isSynthetic && predicate(it) }
            ?.apply { isAccessible = true }
            ?: error("在 $simpleName 上认不出「$what」")

    private fun Class<*>.field(what: String, predicate: (Field) -> Boolean): Field =
        declaredFields.firstOrNull { !it.isSynthetic && !Modifier.isStatic(it.modifiers) && predicate(it) }
            ?.apply { isAccessible = true }
            ?: error("在 $simpleName 上认不出「$what」")

    companion object {
        /** Flogger 在类里留下的原始全限定名。类名会变，这个串不会。 */
        private const val TAG_CP_UTILS =
            "com/google/android/apps/inputmethod/libs/clipboard/ClipboardContentProviderUtils"

        private const val TAG_EDITOR_INFO_UTIL =
            "com/google/android/libraries/inputmethod/editorinfo/EditorInfoUtil"
        private const val TAG_INPUT_BUNDLE_MANAGER =
            "com/google/android/libraries/inputmethod/inputbundle/InputBundleManager"

        /** 剪贴板字数上限的 flag 名，同时也是它所在类的锚点。 */
        const val FLAG_CHAR_LIMIT = "text_clip_item_char_limit"

        private const val CLASS_SOFT_KEY_DEF =
            "com.google.android.libraries.inputmethod.metadata.SoftKeyDef"
        private const val CLASS_ACTION_DEF =
            "com.google.android.libraries.inputmethod.metadata.ActionDef"

        @Volatile
        private var cached: GboardRefs? = null

        /** 每个进程解析一次。 */
        fun of(scope: HookScope): GboardRefs = cached ?: synchronized(this) {
            cached ?: GboardRefs(scope).also { cached = it }
        }
    }
}
