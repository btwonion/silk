package net.axay.fabrik.nbt.serialization.decoder

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import net.axay.fabrik.nbt.serialization.Nbt
import net.axay.fabrik.nbt.serialization.UnknownKeyException
import net.axay.fabrik.nbt.serialization.internal.*
import net.minecraft.nbt.*

@ExperimentalSerializationApi
abstract class NbtTagDecoder(protected val nbt: Nbt) : AbstractDecoder() {
    override val serializersModule: SerializersModule = nbt.serializersModule

    private enum class NextArrayType {
        Byte, Int, Long, None
    }

    private var nextArrayType = NextArrayType.None
    private var nextNullable: NbtElement? = null

    abstract fun next(): NbtElement

    @Suppress("unchecked_cast")
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T =
        when (deserializer) {
            byteArraySerializer -> decodeByteArray() as T
            intArraySerializer -> decodeIntArray() as T
            longArraySerializer -> decodeLongArray() as T
            else -> {
                nextArrayType = when (deserializer.elementSerializer) {
                    byteSerializer -> NextArrayType.Byte
                    intSerializer -> NextArrayType.Int
                    longSerializer -> NextArrayType.Long
                    else -> NextArrayType.None
                }
                super.decodeSerializableValue(deserializer)
            }
        }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
        when (nextArrayType) {
            NextArrayType.Byte -> NbtByteArrayDecoder(nextMaybeNullable() as NbtByteArray)
            NextArrayType.Int -> NbtIntArrayDecoder(nextMaybeNullable() as NbtIntArray)
            NextArrayType.Long -> NbtLongArrayDecoder(nextMaybeNullable() as NbtLongArray)
            NextArrayType.None -> when (descriptor.kind) {
                StructureKind.LIST -> NbtListDecoder(nbt, nextMaybeNullable() as NbtList)
                else -> NbtCompoundDecoder(nbt, nextMaybeNullable() as NbtCompound)
            }
        }

    override fun decodeNotNullMark() =
        (next() as NbtList).let {
            if (it.isEmpty()) {
                false
            } else {
                nextNullable = it[0]
                true
            }
        }

    override fun decodeNull(): Nothing? {
        nextNullable = null
        return null
    }

    private fun nextMaybeNullable(): NbtElement {
        val next = nextNullable ?: next()
        nextNullable = null
        return next
    }

    override fun decodeBoolean(): Boolean =
        when ((nextMaybeNullable() as NbtByte).byteValue()) {
            0.toByte() -> false
            1.toByte() -> true
            else -> throw SerializationException("Byte is not a valid boolean")
        }

    override fun decodeByte(): Byte = (nextMaybeNullable() as NbtByte).byteValue()
    override fun decodeShort(): Short = (nextMaybeNullable() as NbtShort).shortValue()
    override fun decodeChar(): Char = (nextMaybeNullable() as NbtInt).intValue().toChar()
    override fun decodeInt(): Int = (nextMaybeNullable() as NbtInt).intValue()
    override fun decodeLong(): Long = (nextMaybeNullable() as NbtLong).longValue()
    override fun decodeFloat(): Float = (nextMaybeNullable() as NbtFloat).floatValue()
    override fun decodeDouble(): Double = (nextMaybeNullable() as NbtDouble).doubleValue()
    override fun decodeString(): String = (nextMaybeNullable() as NbtString).asString()
    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int =
        enumDescriptor.getElementIndex(decodeString())

    private fun decodeByteArray(): ByteArray = (nextMaybeNullable() as NbtByteArray).byteArray
    private fun decodeIntArray(): IntArray = (nextMaybeNullable() as NbtIntArray).intArray
    private fun decodeLongArray(): LongArray = (nextMaybeNullable() as NbtLongArray).longArray
}

@ExperimentalSerializationApi
class NbtRootDecoder(
    nbt: Nbt,
    private val element: NbtElement
) : NbtTagDecoder(nbt) {
    override fun next() = element

    override fun decodeElementIndex(descriptor: SerialDescriptor) = 0
}

@ExperimentalSerializationApi
class NbtCompoundDecoder(
    nbt: Nbt,
    private val compound: NbtCompound
) : NbtTagDecoder(nbt) {
    private lateinit var element: NbtElement
    private var idx = 0

    override fun next() = element

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (idx < descriptor.elementsCount) {
            val name = descriptor.getElementName(idx++)
            compound.get(name)?.let {
                element = it
                return idx - 1
            }
        }
        return CompositeDecoder.DECODE_DONE
    }

    override fun decodeCollectionSize(descriptor: SerialDescriptor) = compound.size

    override fun endStructure(descriptor: SerialDescriptor) {
        if (nbt.config.ignoreUnknownKeys || descriptor.kind is PolymorphicKind) return

        val names = (0 until descriptor.elementsCount).mapTo(HashSet()) { descriptor.getElementName(it) }
        for (key in compound.keys) {
            if (!names.contains(key)) {
                throw UnknownKeyException(key)
            }
        }
    }
}

@ExperimentalSerializationApi
class NbtListDecoder(
    nbt: Nbt,
    private val list: NbtList
) : NbtTagDecoder(nbt) {
    private val elements = list.listIterator()

    override fun next(): NbtElement = elements.next()

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (elements.hasNext()) {
            elements.nextIndex()
        } else {
            CompositeDecoder.DECODE_DONE
        }

    override fun decodeCollectionSize(descriptor: SerialDescriptor) = list.size
    override fun decodeSequentially() = true
}

@ExperimentalSerializationApi
class NbtByteArrayDecoder(array: NbtByteArray) : AbstractDecoder() {
    private val array = array.byteArray

    override val serializersModule: SerializersModule = EmptySerializersModule

    private var idx = 0

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int = idx
    override fun decodeCollectionSize(descriptor: SerialDescriptor) = array.size
    override fun decodeByte(): Byte = array[idx++]
    override fun decodeSequentially() = true
}

@ExperimentalSerializationApi
class NbtIntArrayDecoder(array: NbtIntArray) : AbstractDecoder() {
    private val array = array.intArray

    override val serializersModule: SerializersModule = EmptySerializersModule

    private var idx = 0

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int = idx
    override fun decodeCollectionSize(descriptor: SerialDescriptor) = array.size
    override fun decodeInt(): Int = array[idx++]
    override fun decodeSequentially() = true
}

@ExperimentalSerializationApi
class NbtLongArrayDecoder(array: NbtLongArray) : AbstractDecoder() {
    private val array = array.longArray

    override val serializersModule: SerializersModule = EmptySerializersModule

    private var idx = 0

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int = idx
    override fun decodeCollectionSize(descriptor: SerialDescriptor) = array.size
    override fun decodeLong(): Long = array[idx++]
    override fun decodeSequentially() = true
}