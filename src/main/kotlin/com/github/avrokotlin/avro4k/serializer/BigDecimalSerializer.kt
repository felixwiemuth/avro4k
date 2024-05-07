package com.github.avrokotlin.avro4k.serializer

import com.github.avrokotlin.avro4k.AnnotatedLocation
import com.github.avrokotlin.avro4k.AvroDecimal
import com.github.avrokotlin.avro4k.AvroLogicalType
import com.github.avrokotlin.avro4k.AvroLogicalTypeSupplier
import com.github.avrokotlin.avro4k.decoder.AvroDecoder
import com.github.avrokotlin.avro4k.decoder.AvroTaggedDecoder
import com.github.avrokotlin.avro4k.encoder.AvroEncoder
import com.github.avrokotlin.avro4k.encoder.encodeResolvingUnion
import com.github.avrokotlin.avro4k.internal.BadEncodedValueError
import com.github.avrokotlin.avro4k.internal.findElementAnnotation
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.apache.avro.Conversions
import org.apache.avro.LogicalType
import org.apache.avro.LogicalTypes
import org.apache.avro.Schema
import org.apache.avro.generic.GenericFixed
import java.math.BigDecimal
import java.nio.ByteBuffer

private val converter = Conversions.DecimalConversion()
private val defaultAnnotation = AvroDecimal()

public object BigDecimalSerializer : AvroSerializer<BigDecimal>(), AvroLogicalTypeSupplier {
    override fun getLogicalType(inlinedStack: List<AnnotatedLocation>): LogicalType {
        return inlinedStack.firstNotNullOfOrNull {
            it.descriptor.findElementAnnotation<AvroDecimal>(it.elementIndex ?: return@firstNotNullOfOrNull null)?.logicalType
        } ?: defaultAnnotation.logicalType
    }

    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        buildSerialDescriptor(BigDecimal::class.qualifiedName!!, StructureKind.LIST) {
            element("item", buildSerialDescriptor("item", PrimitiveKind.BYTE))
            this.annotations = listOf(AvroLogicalType(BigDecimalSerializer::class))
        }

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: BigDecimal,
    ) {
        encodeBigDecimal(encoder, value)
    }

    override fun serializeGeneric(
        encoder: Encoder,
        value: BigDecimal,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserializeAvro(decoder: AvroDecoder): BigDecimal {
        return decodeBigDecimal(decoder)
    }

    override fun deserializeGeneric(decoder: Decoder): BigDecimal {
        return decoder.decodeString().toBigDecimal()
    }

    private val AvroDecimal.logicalType: LogicalType
        get() {
            return LogicalTypes.decimal(precision, scale)
        }
}

public object BigDecimalAsStringSerializer : AvroSerializer<BigDecimal>() {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(BigDecimal::class.qualifiedName!!, PrimitiveKind.STRING)

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: BigDecimal,
    ) {
        encodeBigDecimal(encoder, value)
    }

    override fun serializeGeneric(
        encoder: Encoder,
        value: BigDecimal,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserializeAvro(decoder: AvroDecoder): BigDecimal {
        return decodeBigDecimal(decoder)
    }

    override fun deserializeGeneric(decoder: Decoder): BigDecimal {
        return decoder.decodeString().toBigDecimal()
    }
}

private fun encodeBigDecimal(
    encoder: AvroEncoder,
    value: BigDecimal,
) {
    encoder.encodeResolvingUnion({ with(encoder) { BadEncodedValueError(value, encoder.currentWriterSchema, Schema.Type.BYTES) } }) { schema ->
        when (schema.type) {
            Schema.Type.BYTES -> encoder.encodeBytes(converter.toBytes(value, schema, schema.getDecimalLogicalType()))
            Schema.Type.FIXED -> encoder.encodeFixed(converter.toFixed(value, schema, schema.getDecimalLogicalType()))
            Schema.Type.STRING -> encoder.encodeString(value.toString())
            Schema.Type.INT -> encoder.encodeInt(value.intValueExact())
            Schema.Type.LONG -> encoder.encodeLong(value.longValueExact())
            Schema.Type.FLOAT -> encoder.encodeFloat(value.toFloat())
            Schema.Type.DOUBLE -> encoder.encodeDouble(value.toDouble())
            else -> null
        }
    }
}

private fun decodeBigDecimal(decoder: AvroDecoder): BigDecimal {
    val unionResolver = (decoder as AvroTaggedDecoder<*>).avro.unionResolver

    return when (val v = decoder.decodeValue()) {
        is CharSequence -> {
            BigDecimal(v.toString())
        }

        is ByteArray -> {
            val schema =
                unionResolver.tryResolveUnion(decoder.currentWriterSchema, Schema.Type.BYTES.getName())
                    ?: throw SerializationException("Expected to find a schema for BYTES type but found none")

            converter.fromBytes(ByteBuffer.wrap(v), schema, schema.getDecimalLogicalType())
        }

        is ByteBuffer -> {
            val schema =
                unionResolver.tryResolveUnion(decoder.currentWriterSchema, Schema.Type.BYTES.getName())
                    ?: throw SerializationException("Expected to find a schema for BYTES type but found none")

            converter.fromBytes(v, schema, schema.getDecimalLogicalType())
        }

        is GenericFixed -> {
            converter.fromFixed(v, v.schema, v.schema.getDecimalLogicalType())
        }

        else -> {
            throw SerializationException("Unsupported BigDecimal type [$v]")
        }
    }
}

private fun Schema.getDecimalLogicalType(): LogicalTypes.Decimal {
    return when (val l = logicalType) {
        is LogicalTypes.Decimal -> l
        else -> throw SerializationException("Expected to find a decimal logical type for BigDecimal but found $l on schema $this")
    }
}