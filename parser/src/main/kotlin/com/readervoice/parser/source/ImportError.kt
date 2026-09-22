package com.readervoice.parser.source

/** 结构化导入错误（TASK-010 §25）：禁止只存 Exception.toString()。 */
enum class ImportError(val code: String) {
    IO_ERROR("IO_ERROR"),
    PERMISSION_ERROR("PERMISSION_ERROR"),
    UNSUPPORTED_ENCODING("UNSUPPORTED_ENCODING"),
    AMBIGUOUS_ENCODING("AMBIGUOUS_ENCODING"),
    CORRUPT_TEXT("CORRUPT_TEXT"),
    OUT_OF_SPACE("OUT_OF_SPACE"),
    HASH_ERROR("HASH_ERROR"),
    INDEX_ERROR("INDEX_ERROR"),
    UNKNOWN("UNKNOWN"),
}
