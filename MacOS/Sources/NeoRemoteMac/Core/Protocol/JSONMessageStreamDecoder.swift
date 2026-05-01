import Foundation

enum JSONMessageStreamDecoderError: Error, LocalizedError, Equatable {
    case bufferLimitExceeded

    var errorDescription: String? {
        "JSON 消息超过 1MB 上限，已断开连接。"
    }
}

struct JSONMessageStreamDecoder {
    static let maxBufferSize = 1_048_576

    private var buffer = Data()

    mutating func append(_ data: Data) throws -> [Data] {
        buffer.append(data)
        guard buffer.count <= Self.maxBufferSize else {
            buffer.removeAll(keepingCapacity: false)
            throw JSONMessageStreamDecoderError.bufferLimitExceeded
        }

        var payloads: [Data] = []
        var inString = false
        var isEscaping = false
        var depth = 0
        var startIndex: Int?
        var consumedThrough: Int?

        let bytes = Array(buffer)
        for (index, byte) in bytes.enumerated() {
            if isEscaping {
                isEscaping = false
                continue
            }

            if byte == 0x5C {
                isEscaping = inString
                continue
            }

            if byte == 0x22 {
                inString.toggle()
                continue
            }

            if inString {
                continue
            }

            if byte == 0x7B {
                if depth == 0 {
                    startIndex = index
                }
                depth += 1
            } else if byte == 0x7D, depth > 0 {
                depth -= 1
                if depth == 0, let startIndex {
                    payloads.append(buffer.subdata(in: startIndex ..< (index + 1)))
                    consumedThrough = index
                }
            }
        }

        if let consumedThrough {
            buffer.removeSubrange(...consumedThrough)
        }

        return payloads
    }
}
