#include "NeoRemote/Core/JsonMessageStreamDecoder.hpp"

namespace NeoRemote::Core {

std::vector<std::string> JsonMessageStreamDecoder::Append(std::string_view data)
{
    buffer_.append(data);

    std::vector<std::string> payloads;
    bool inString = false;
    bool escaping = false;
    int depth = 0;
    size_t startIndex = std::string::npos;
    size_t consumedThrough = std::string::npos;

    for (size_t index = 0; index < buffer_.size(); ++index) {
        const char c = buffer_[index];

        if (escaping) {
            escaping = false;
            continue;
        }
        if (c == '\\' && inString) {
            escaping = true;
            continue;
        }
        if (c == '"') {
            inString = !inString;
            continue;
        }
        if (inString) {
            continue;
        }
        if (c == '{') {
            if (depth == 0) {
                startIndex = index;
            }
            ++depth;
        } else if (c == '}' && depth > 0) {
            --depth;
            if (depth == 0 && startIndex != std::string::npos) {
                payloads.push_back(buffer_.substr(startIndex, index - startIndex + 1));
                consumedThrough = index;
                startIndex = std::string::npos;
            }
        }
    }

    if (consumedThrough != std::string::npos) {
        buffer_.erase(0, consumedThrough + 1);
    }

    return payloads;
}

} // namespace NeoRemote::Core
