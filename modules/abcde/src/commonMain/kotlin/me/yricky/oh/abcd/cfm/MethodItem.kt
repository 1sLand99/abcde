package me.yricky.oh.abcd.cfm

import me.yricky.oh.SizeInBuf
import me.yricky.oh.abcd.AbcBufOffset
import me.yricky.oh.abcd.AbcBuf
import me.yricky.oh.abcd.code.Code
import me.yricky.oh.abcd.code.DebugInfo
import me.yricky.oh.abcd.literal.LiteralArray
import me.yricky.oh.common.DataAndNextOff
import me.yricky.oh.common.nextOffset
import me.yricky.oh.common.value
import me.yricky.oh.utils.*
import kotlin.jvm.JvmInline

sealed class MethodItem(
    final override val abc: AbcBuf,
    final override val offset:Int
): AbcBufOffset {
    val region by lazy { abc.regions.first { it.contains(offset) } }

    private val classIdx:UShort = abc.buf.getShort(offset).toUShort()
    val fieldType get() = region.classes[classIdx.toInt()]
    val clazz get() = fieldType.getClass(abc)
    private val protoIdx:UShort = abc.buf.getShort(offset + 2).toUShort()
    @Deprecated("since 12.0.1.0")
    val proto get() = region.protos.getOrNull(protoIdx.toInt())
    val nameOff:Int = abc.buf.getInt(offset + 4)

    val name :String get() = abc.stringItem(nameOff).value

    protected val _indexData = abc.buf.readULeb128(offset + 8)
    @Uncleared("不同文档对此字段定义不同")
    val indexData get() = AbcMethod.IndexData(_indexData.value)
}
class ForeignMethod(abc: AbcBuf, offset: Int) : MethodItem(abc, offset)

class AbcMethod(abc: AbcBuf, offset: Int) :MethodItem(abc, offset), SizeInBuf.External{

    private val _data by lazy {
        var tagOff = _indexData.nextOffset
        val tagList = mutableListOf<MethodTag>()
        while (tagList.lastOrNull() != MethodTag.Nothing){
            val (tag,nextOff) = MethodTag.readTag(this, tagOff)
            tagList.add(tag)
            tagOff = nextOff
        }
        if(tagList.lastOrNull() == MethodTag.Nothing){
            tagList.removeLast()
        }
        DataAndNextOff(tagList,tagOff)
    }
    val data:List<MethodTag> get() = _data.value

    val codeItem: Code? by WeakLazy {
        data.firstOrNull { it is MethodTag.Code }?.let { Code(this,(it as MethodTag.Code).offset) }
    }

    val debugInfo: MethodTag.DbgInfo? get() = data.firstOrNull { it is MethodTag.DbgInfo }?.let { it as MethodTag.DbgInfo }

    @Uncleared("不同文档对此字段定义不同")
    @JvmInline
    value class IndexData(val value:Int){
        val headerIndex get() = value and 0x0000ffff
        val functionKind get() = (value and 0x00ff0000).ushr(16)
//        val isPublic:Boolean get() = (value and 0x0001) != 0
//        val isPrivate:Boolean get() = (value and 0x0002) != 0
//        val isProtected:Boolean get() = (value and 0x0004) != 0
//        val isStatic:Boolean get() = (value and 0x0008) != 0
//        val isFinal:Boolean get() = (value and 0x0010) != 0
//        val isSynchronized:Boolean get() = (value and 0x0020) != 0
//        val isNative:Boolean get() = (value and 0x0100) != 0
//        val isAbstract:Boolean get() = (value and 0x0400) != 0
//        val isSynthetic:Boolean get() = (value and 0x1000) != 0
    }

    val nextOff get() = _data.nextOffset

    val scopeInfo by lazy{
        ScopeInfo.parseFromMethod(this)
    }

    data class ScopeInfo(
        val origin:IntRange,
        val layers:List<ScopeLayer>,
        val tag:Char
    ){

        override fun toString(): String {
            return "$layers ${TAN_NAME_MAP[tag]}"
        }
        companion object{
            const val TAG_CLASS = '~'
            const val TAG_INSTANCE = '>'
            const val TAG_STATIC = '<'
            const val TAG_CONSTRUCTOR = '='
            const val TAG_NORMAL = '*'
            const val TAG_NS_OR_MOD = '&'
            const val TAG_ENUM = '%'
            val TAN_NAME_MAP = mapOf(
                TAG_CLASS to "class",
                TAG_INSTANCE to "instance",
                TAG_STATIC to "static",
                TAG_CONSTRUCTOR to "constructor",
                TAG_NORMAL to "function",
                TAG_NS_OR_MOD to "namespace",
                TAG_ENUM to "enum"
            )

            const val ANONYMOUS_NAME = "[ANONYMOUS]"

            val scopeRegex = Regex("(#([~><=*&%](@[0-9a-fA-F]+)?(\\^[0-9a-fA-F]+)?)*[~><=*&%]#).*")

            fun parseFromMethod(method:AbcMethod):ScopeInfo?{
                return scopeRegex.find(method.name)?.let {
                    it.groups[1]?.takeIf { !it.range.isEmpty() }
                }?.let { res ->
                    val first = res.range.first + 1
                    val last = res.range.last - 1
                    val origin = res.range

                    if (first == last){
                        ScopeInfo(origin,layers = emptyList(),method.name[last])
                    } else if (first < last){
                        val layerStr = method.name.substring(first,last)
                        val layers = layerStr.map { if(TAN_NAME_MAP.containsKey(it)) " $it" else "$it" }
                            .reduce{ s1,s2 -> "$s1$s2"}
                            .split(' ')
                            .asSequence()
                            .filter { it.isNotEmpty() }
                            .map {
                                val tag = it[0]
                                val remaining = it.removePrefix("$tag")
                                if(remaining.firstOrNull() == '^'){
                                    ScopeLayer(tag, "",remaining.removePrefix("^").toIntOrNull(16))
                                } else if (remaining.firstOrNull() == '@'){
                                    if (remaining.contains('^')){
                                        val sp = remaining.split('^')
                                        val nameIndex = sp[0].removePrefix("@").toInt(16)
                                        val layerName = method.clazz?.scopeNames?.content?.getOrNull(nameIndex)
                                            ?.let { it as? LiteralArray.Literal.Str }?.get(method.abc)
                                        ScopeLayer(tag, layerName ?: "null@$nameIndex" ,sp[1].toIntOrNull(16))
                                    } else {
                                        val nameIndex = remaining.removePrefix("@").toInt(16)
                                        val layerName = method.clazz?.scopeNames?.content?.getOrNull(nameIndex)
                                            ?.let { it as? LiteralArray.Literal.Str }?.get(method.abc)
                                        ScopeLayer(tag, layerName ?: "null@$nameIndex" ,null)
                                    }
                                } else if (remaining.contains('^')){
                                    val sp = remaining.split('^')
                                    ScopeLayer(tag,sp[0],sp[1].toIntOrNull(16))
                                } else ScopeLayer(tag, remaining.ifEmpty { ANONYMOUS_NAME },null)
                            }
                            .toList()
                        ScopeInfo(origin,layers,method.name[last])
                    } else null
                }
            }

            fun decorateMethodName(name:String,tag:Char):String{
                return when(tag){
                    TAG_INSTANCE -> name.ifEmpty { ANONYMOUS_NAME }
                    TAG_CONSTRUCTOR -> "constructor"
                    else -> "${TAN_NAME_MAP[tag]} ${name.ifEmpty { ANONYMOUS_NAME }}"
                }
            }
        }
        fun decorateMethodName(method: AbcMethod):String{
            return decorateMethodName(method.name.removeRange(origin),tag)
        }

        fun asNameIterable(method: AbcMethod):Iterable<String>{
            return object : Iterable<String>{
                override fun iterator(): Iterator<String> = iterator {
                    yieldAll(layers.asSequence().map { it.toString() })
                    yield(decorateMethodName(method))
                }
            }
        }
    }
    data class ScopeLayer(
        val tag:Char,
        val layerName:String,
        val uglyIndex:Int?
    ){
        override fun toString(): String {
            val mName = "$layerName${uglyIndex?.let { "^$it" } ?: ""}"
            return ScopeInfo.decorateMethodName(mName,tag)
        }
    }

    override val externalSize: Int get() = (codeItem?.intrinsicSize?: 0) + (codeItem?.externalSize?: 0) +
            data.fold(0) { s, tag -> s + ((tag as? SizeInBuf.External)?.externalSize?:0) }
}

sealed class MethodTag{
    sealed class AnnoTag(abc: AbcBuf, annoOffset:Int):MethodTag(),SizeInBuf.External{
        val anno:AbcAnnotation = AbcAnnotation(abc,annoOffset)
        override val externalSize: Int get() = anno.intrinsicSize

        override fun toString(): String {
            return "Annotation(${anno.clazz?.name},${anno.elements.map { it.toString(anno.abc) }})"
        }
    }
    sealed class ParamAnnoTag(val annoOffset:Int):MethodTag(){
        fun get(abc: AbcBuf): ParamAnnotation = ParamAnnotation(abc,annoOffset)
    }
    data object Nothing: MethodTag()
    data class Code(val offset:Int): MethodTag()
    data class SourceLang(val value:Byte): MethodTag()
    class RuntimeAnno(abc: AbcBuf, annoOffset: Int) : AnnoTag(abc,annoOffset)
    class RuntimeParamAnno(annoOffset: Int) :ParamAnnoTag(annoOffset)
    class DbgInfo(method: AbcMethod, offset:Int): MethodTag(){
        val info = DebugInfo(method.abc,offset)
        val state by lazy {
            kotlin.runCatching { info.lineNumberProgram?.eval(info) }
                .onFailure { it.printStackTrace() }.getOrNull()
        }

        override fun toString(): String {
            return "Dbg(offset=${info.offset.toString(16)},lineStart=${info.lineStart},paramName=${info.params},cps=${info.constantPool},lnp=${info.lineNumberProgram?.eval(info)})"
        }
    }
    class Anno(abc: AbcBuf, annoOffset: Int) : AnnoTag(abc,annoOffset)
    class ParamAnno(annoOffset: Int) : ParamAnnoTag(annoOffset)
    class TypeAnno(abc: AbcBuf, annoOffset: Int) : AnnoTag(abc,annoOffset)
    class RuntimeTypeAnno(abc: AbcBuf, annoOffset: Int) : AnnoTag(abc,annoOffset)

    companion object{
        fun readTag(method: AbcMethod, offset: Int):DataAndNextOff<MethodTag>{
            val abc = method.abc
            val buf = abc.buf
            return when(val type = buf.get(offset).toInt()){
                0 -> Pair(Nothing,offset + 1)
                1 -> run{
                    val value = buf.getInt(offset + 1)
                    Pair(Code(value), offset + 5)
                }
                2 -> Pair(SourceLang(buf.get(offset + 1)),offset + 2)
                3 -> Pair(RuntimeAnno(abc,buf.getInt(offset + 1)),offset + 5)
                4 -> Pair(RuntimeParamAnno(buf.getInt(offset + 1)),offset + 5)
                5 -> Pair(DbgInfo(method,buf.getInt(offset + 1)),offset + 5)
                6 -> Pair(Anno(abc,buf.getInt(offset + 1)),offset + 5)
                7 -> Pair(ParamAnno(buf.getInt(offset + 1)),offset + 5)
                8 -> Pair(TypeAnno(abc,buf.getInt(offset + 1)),offset + 5)
                9 -> Pair(RuntimeTypeAnno(abc,buf.getInt(offset + 1)),offset + 5)
                else -> throw IllegalStateException("No this Tag:${type.toString(16)}")
            }
        }
    }
}

fun MethodItem.argsStr():String{
    if(this is AbcMethod && codeItem != null){
        val code = codeItem!!
        val argCount = code.numArgs - 3
        if(argCount == 0){
            return "(FunctionObject, NewTarget, this)"
        }
        val sb = StringBuilder()
        if(argCount >= 0){
            sb.append("(FunctionObject, NewTarget, this")
            repeat(argCount){
                sb.append(", arg$it")
            }
            sb.append(')')
        }
        return sb.toString()
    } else {
        return ""
    }
}