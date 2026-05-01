#pragma once

#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace NeoRemote::Core {

class JsonMessageStreamDecoderError final : public std::runtime_error {
public:
    explicit JsonMessageStreamDecoderError(const std::string& message);
};

class JsonMessageStreamDecoder {
public:
    static constexpr size_t MaxBufferSize = 1'048'576;

    std::vector<std::string> Append(std::string_view data);

private:
    std::string buffer_;
};

} // namespace NeoRemote::Core
