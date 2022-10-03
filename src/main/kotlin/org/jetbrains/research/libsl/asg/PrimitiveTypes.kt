package org.jetbrains.research.libsl.asg

interface PrimitiveType : Type, AliassableType {
    override fun dumpToString(): String {
        return fullName
    }
}

data class IntType(
    override val context: LslContext,
    val capacity: IntCapacity,
    override val isPointer: Boolean
) : PrimitiveType {
    override val generic: Type? = null
    override val name: String = capacity.name.lowercase()

    enum class IntCapacity {
        INT8, INT16, INT32, INT64
    }
}

data class UnsignedType(
    override val context: LslContext,
    val capacity: UnsignedCapacity,
    override val isPointer: Boolean
) : PrimitiveType {
    override val generic: Type? = null
    override val name: String = capacity.name.lowercase()

    enum class UnsignedCapacity {
        UNSIGNED8, UNSIGNED16, UNSIGNED32, UNSIGNED64
    }
}

data class FloatType(
    override val context: LslContext,
    val capacity: FloatCapacity,
    override val isPointer: Boolean
) : PrimitiveType {
    override val generic: Type? = null
    override val name: String = capacity.name.lowercase()

    enum class FloatCapacity {
        FLOAT32, FLOAT64
    }
}

data class BoolType(
    override val context: LslContext,
    override val isPointer: Boolean
) : PrimitiveType {
    override val generic: Type? = null
    override val name: String = "bool"
}

data class CharType(
    override val context: LslContext,
    override val isPointer: Boolean
) : PrimitiveType {
    override val generic: Type? = null
    override val name: String = "char"
}

data class StringType(
    override val context: LslContext,
    override val isPointer: Boolean
) : PrimitiveType {
    override val generic: Type? = null
    override val name: String = "string"
}

data class VoidType(
    override val context: LslContext,
    override val isPointer: Boolean
) : PrimitiveType {
    override val name: String = "void"
    override val generic: Type? = null
}
