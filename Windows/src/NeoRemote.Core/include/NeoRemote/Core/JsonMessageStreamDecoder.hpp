#pragma once

#include <string>
#include <vector>

namespace NeoRemote::Core {

class JsonMessageStreamDecoder {
public:
    std::vector<std::string> Append(std::string_view data);

private:
    std::string buffer_;
};

} // namespace NeoRemote::Core
