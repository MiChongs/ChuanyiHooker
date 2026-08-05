/*
 * 壳内代码的链接脚本。
 *
 * 目标不是产出一个能跑的 ELF，而是产出一段**能被整块拷走的字节**：
 *
 *   1. `.text.entry` 排在最前 —— 宿主把解密后的内存首地址直接当函数指针跳进去，
 *      不做符号解析，所以入口必须在偏移 0。
 *   2. 代码和只读数据合并进同一个输出段 —— `objcopy -O binary` 按地址顺序导出
 *      全部可分配段，只有一个段才不会在中间留下空洞。
 *   3. `.data` / `.bss` 必须为空。有可写静态数据就意味着有绝对地址引用，blob 一
 *      搬到 mmap 出来的匿名页就全指错地方。下面两条 ASSERT 把它变成构建期错误，
 *      而不是真机上的一次静默判错。
 *
 * 故意不写 `. = 0;`：那会和 lld 放 ELF 头的位置打架。基址是多少无所谓，
 * `objcopy -O binary` 以最低段地址为原点，输出仍然从 `.text.entry` 开始。
 */

ENTRY(PayloadMain)

SECTIONS
{
    .text : ALIGN(16)
    {
        KEEP(*(.text.entry))
        *(.text .text.*)
        *(.rodata .rodata.* .rodata1)
        . = ALIGN(4);
    }

    .data : { *(.data .data.* .sdata .sdata.*) }
    .bss  : { *(.bss .bss.* .sbss .sbss.* COMMON) }

    ASSERT(SIZEOF(.data) == 0,
           "payload 里出现了可写静态数据（.data 非空）。壳内代码不能有全局变量，把状态挪到栈上或 Request 里。")
    ASSERT(SIZEOF(.bss) == 0,
           "payload 里出现了未初始化的静态数据（.bss 非空）。壳内代码不能有全局变量，把状态挪到栈上或 Request 里。")

    /DISCARD/ :
    {
        *(.comment)
        *(.note*)
        *(.eh_frame*)
        *(.eh_frame_hdr)
        *(.ARM.exidx*)
        *(.ARM.extab*)
        *(.ARM.attributes)
        *(.llvm_addrsig)
        *(.debug*)
    }
}
