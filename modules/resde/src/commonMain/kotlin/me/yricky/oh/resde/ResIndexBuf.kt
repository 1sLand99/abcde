package me.yricky.oh.resde

import me.yricky.oh.common.*

class ResIndexBuf(
    override val buf: LEByteBuf
) :BufOffset {
    override val offset: Int get() = 0

    val header = ResIndexHeader(buf)

    /**
     * Map<IdSetOffset,List<LimitKeyConfig.KeyParam>>
     */
    private fun limitKeyConfigs(): DataAndNextOff<LimitKeyConfigs> {
        val list = HashMap<Int,List<LimitKeyConfig.KeyParam>>()
        var off = 136
        repeat(header.limitKeyConfigCount){
            off +=4 // "KEYS"
            val kOffset = buf.getInt(off)
            off += 4
            val keyCount = buf.getInt(off)
            off += 4
            val param = ArrayList<LimitKeyConfig.KeyParam>(keyCount)
            repeat(keyCount){
                param.add(LimitKeyConfig.KeyParam(buf.getLong(off)))
                off += 8
            }
            list[kOffset] = param
        }
        return DataAndNextOff(list,off)
    }

    /**
     * Map<ResItemOffset,Pair<ResId,IdSetOffset>>
     */
    private fun idSetMap(limitKeyConfigs: DataAndNextOff<LimitKeyConfigs>): DataAndNextOff<Map<Int,Pair<Int,Int>>>{
        val list = mutableMapOf<Int,Pair<Int,Int>>()
        var off = limitKeyConfigs.nextOffset
        repeat(header.limitKeyConfigCount){
            val thisOffset = off
            off +=4 // "IDSS"
            val idCount = buf.getInt(off)
            off += 4
            repeat(idCount){
                val idOffset = IdSet.IdOffset(buf.getLong(off))
                list[idOffset.offset] = Pair(idOffset.id,thisOffset)
                off += 8
            }
        }
        return DataAndNextOff(list,off)
    }

    val resMap:Map<Int,List<ResourceItem>> by lazy {
        val limitKeyConfigs = limitKeyConfigs()
        val idSetMap = idSetMap(limitKeyConfigs)

        val map = mutableMapOf<Int,MutableList<ResourceItem>>()
        var off = idSetMap.nextOffset
        while (off < buf.limit()){
            val thisOffset = off
//            val size = buf.getInt(off)
            off += 4
            val resType = ResType(buf.getInt(off))
            off += 4
            val resId = buf.getInt(off)
            off += 4
            val dataSize = buf.getShort(off).toUInt().toInt()
            off += 2
            val data = ByteArray((dataSize-1).coerceAtLeast(0))
            buf.get(off,data)
            off += dataSize
            val nameSize = buf.getShort(off).toUInt().toInt()
            off += 2
            val name = ByteArray((nameSize-1).coerceAtLeast(0))
            buf.get(off,name)
            off += nameSize
            val fileName = String(name)
            val idTableOffset = idSetMap.value[thisOffset]!!
            assert(resId == idTableOffset.first)
            val keyParams = limitKeyConfigs.value[idTableOffset.second]!!
            val item = ResourceItem(
                fileName, keyParams, resType, data
            )
            map[resId]?.add(item) ?: let {
                map[resId] = mutableListOf(item)
            }
        }
        map
    }
}