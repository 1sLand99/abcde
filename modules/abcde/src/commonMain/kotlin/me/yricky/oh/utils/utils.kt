package me.yricky.oh.utils

import me.yricky.oh.common.DataAndNextOff
import me.yricky.oh.common.LEByteBuf
import kotlin.experimental.and

fun LEByteBuf.readULeb128(index:Int): DataAndNextOff<Int> {
    var result = 0
    var off = 0
    var byte:Byte
    do {
        byte = get(index + off)
        ++off
        result = result or (byte.and(0x7f).toInt().shl(7*off - 7))
    } while ((byte and 0x80.toByte() != 0.toByte()) && off < 5)
    return DataAndNextOff(result,index + off)
}

fun LEByteBuf.readSLeb128(index:Int): DataAndNextOff<Int> {
    var result = 0
    var off = 0
    var byte:Byte
    do {
        byte = get(index + off)
        ++off
        result = result or (byte.and(0x7f).toInt().shl(7*off - 7))
    } while ((byte and 0x80.toByte() != 0.toByte()) && off < 5)
    if((byte and 0x40.toByte()) != 0x00.toByte() && off < 5){
        //符号位为1
        result = result or (-1).shl(off * 7)
    }
    return DataAndNextOff(result,index + off)
}