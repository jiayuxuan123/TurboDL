#include <turbodl/engine.h>
#include <curl/curl.h>
#include <algorithm>
#include <string>

namespace {
std::string quoted(const char* input) {
    std::string out="\"";
    for(const char* p=input;p && *p;++p) {
        if(*p=='\\' || *p=='\"')out+='\\';
        if(static_cast<unsigned char>(*p)>=32)out+=*p;
    }
    return out+'\"';
}
std::string report() {
    const auto* info=curl_version_info(CURLVERSION_NOW);
    auto has_protocol=[&](const char* name) {
        for(auto p=info->protocols;p && *p;++p)if(std::string(*p)==name)return true;
        return false;
    };
    auto boolean=[](bool value){return value?"true":"false";};
    std::string out="{\"abi\":1,\"experimental\":true,\"parity_verified\":false";
    out+=",\"curl_version\":"+quoted(info->version);
    out+=",\"tls_backend\":"+quoted(info->ssl_version);
    out+=",\"http\":"+std::string(boolean(has_protocol("http")));
    out+=",\"https\":"+std::string(boolean(has_protocol("https")));
    out+=",\"ftp\":"+std::string(boolean(has_protocol("ftp")));
    out+=",\"sftp\":"+std::string(boolean(has_protocol("sftp")));
    out+=",\"http2_compiled\":"+std::string(boolean(info->features&CURL_VERSION_HTTP2));
    out+=",\"http3_compiled\":"+std::string(boolean(info->features&CURL_VERSION_HTTP3));
    out+=",\"parallel_ranges\":true,\"sqlite_checkpoints\":true,\"disjoint_extent_recovery\":true";
    out+=",\"buffer_admission\":true,\"shared_request_admission\":true";
#ifdef TD_WITH_BITTORRENT
    out+=",\"bittorrent\":true,\"magnet\":true";
#else
    out+=",\"bittorrent\":false,\"magnet\":false";
#endif
    out+=",\"hls\":false,\"dash\":false,\"ed2k\":false";
#ifdef TD_WITH_METALINK
    out+=",\"metalink\":true}";
#else
    out+=",\"metalink\":false}";
#endif
    return out;
}
}

extern "C" TD_API const char* td_engine_capabilities(void) {
    // Compiler capabilities are not protocol negotiation or acceptance evidence.
    // Initialization is thread-safe; the ABI never exposes allocation failure.
    try {static const std::string value=report();return value.c_str();}
    catch(...) {return "{\"abi\":1,\"experimental\":true,\"capability_error\":true,\"parity_verified\":false}";}
}
