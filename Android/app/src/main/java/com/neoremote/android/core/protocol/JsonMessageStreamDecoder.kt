package com.neoremote.android.core.protocol

class JsonMessageStreamDecoder {
    private var buffer = ByteArray(0)

    fun append(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) return emptyList()
        buffer += data
        check(buffer.size <= MAX_BUFFER_SIZE_BYTES) {
            buffer = ByteArray(0)
            "JSON message exceeded 1MB limit"
        }

        val payloads = mutableListOf<ByteArray>()
        var inString = false
        var escaping = false
        var depth = 0
        var startIndex = -1
        var consumedThrough = -1

        for (index in buffer.indices) {
            val byte = buffer[index].toInt() and 0xFF
            if (escaping) {
                escaping = false
                continue
            }

            if (byte == 0x5C) {
                escaping = inString
                continue
            }

            if (byte == 0x22) {
                inString = !inString
                continue
            }

            if (inString) continue

            if (byte == 0x7B) {
                if (depth == 0) {
                    startIndex = index
                }
                depth += 1
            } else if (byte == 0x7D && depth > 0) {
                depth -= 1
                if (depth == 0 && startIndex >= 0) {
                    payloads += buffer.copyOfRange(startIndex, index + 1)
                    consumedThrough = index
                }
            }
        }

        if (consumedThrough >= 0) {
            buffer = if (consumedThrough == buffer.lastIndex) {
                ByteArray(0)
            } else {
                buffer.copyOfRange(consumedThrough + 1, buffer.size)
            }
        }

        return payloads
    }

    companion object {
        const val MAX_BUFFER_SIZE_BYTES = 1_048_576
    }
}
