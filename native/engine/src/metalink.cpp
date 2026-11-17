#include <turbodl/engine.h>
#include <curl/curl.h>
#include <pugixml.hpp>
#include <algorithm>
#include <charconv>
#include <cctype>
#include <cstddef>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::string local(const char* name) {
    const char* colon=std::strchr(name,':');return colon?colon+1:name;
}
std::string lower(std::string value) {
    for(auto& c:value)c=static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return value;
}
std::string trim(std::string value) {
    const auto first=value.find_first_not_of(" \r\n\t");
    if(first==std::string::npos)return {};
    return value.substr(first,value.find_last_not_of(" \r\n\t")-first+1);
}
int64_t integer(const std::string& value) {
    int64_t number=0;
    auto [end,error]=std::from_chars(value.data(),value.data()+value.size(),number);
    if(error!=std::errc{} || end!=value.data()+value.size() || number<0)throw std::invalid_argument("Invalid Metalink integer");
    return number;
}
void bounded_tree(pugi::xml_node node,unsigned depth=0) {
    if(depth>16)throw std::invalid_argument("Metalink nesting exceeds limit");
    unsigned count=0;
    for(auto child:node.children()) {
        if(++count>1024)throw std::invalid_argument("Metalink item count exceeds limit");
        bounded_tree(child,depth+1);
    }
}
pugi::xml_node child(pugi::xml_node node,const char* name) {
    for(auto item:node.children())if(local(item.name())==name)return item;
    return {};
}
std::string resolve(const char* base,const std::string& input) {
    auto* parsed=curl_url();if(!parsed)throw std::bad_alloc();
    struct Cleanup {CURLU* p;~Cleanup(){curl_url_cleanup(p);}} cleanup{parsed};
    if(base && *base && curl_url_set(parsed,CURLUPART_URL,base,0)!=CURLUE_OK)throw std::invalid_argument("Invalid Metalink base");
    if(curl_url_set(parsed,CURLUPART_URL,input.c_str(),0)!=CURLUE_OK)throw std::invalid_argument("Invalid Metalink URL");
    auto part=[&](CURLUPart key){char* value=nullptr;std::string result;if(curl_url_get(parsed,key,&value,0)==CURLUE_OK)result=value;curl_free(value);return result;};
    auto scheme=lower(part(CURLUPART_SCHEME));
    if((scheme!="http" && scheme!="https") || part(CURLUPART_HOST).empty() || !part(CURLUPART_USER).empty() || !part(CURLUPART_PASSWORD).empty())throw std::invalid_argument("Unsupported Metalink URL");
    return part(CURLUPART_URL);
}
}

extern "C" TD_API int32_t td_metalink_submit(td_engine* engine,const char* xml,
    uint32_t length,const char* base,const char* selected,const td_task_options* transfer,td_task_id* out) {
    if(!engine || !xml || !length || length>1048576 || !transfer || !out ||
       transfer->struct_size<offsetof(td_task_options,network) || transfer->abi_version!=TD_ABI_VERSION)return TD_INVALID;
    *out=0;
    try {
        std::string text(xml,length);
        const auto folded=lower(text);
        if(text.find('\0')!=std::string::npos || folded.find("<!doctype")!=std::string::npos || folded.find("<!entity")!=std::string::npos)return TD_INVALID;
        pugi::xml_document doc;
        if(!doc.load_buffer(text.data(),text.size(),pugi::parse_default,pugi::encoding_utf8))return TD_INVALID;
        bounded_tree(doc);
        const auto root=doc.document_element();
        if(local(root.name())!="metalink")return TD_INVALID;
        std::string prefix=root.name();const auto colon=prefix.find(':');
        const auto ns=root.attribute(colon==std::string::npos?"xmlns":("xmlns:"+prefix.substr(0,colon)).c_str()).as_string();
        const bool v4=std::string(ns)=="urn:ietf:params:xml:ns:metalink";
        const bool v3=std::string(ns)=="http://www.metalinker.org/";
        if(!v4 && !v3)return TD_UNSUPPORTED;
        auto files=v4?root:child(root,"files");
        std::vector<pugi::xml_node> entries;
        for(auto item:files.children())if(local(item.name())=="file")entries.push_back(item);
        if(entries.empty() || entries.size()>256)return TD_INVALID;
        pugi::xml_node file;
        if(selected && *selected) {
            for(auto entry:entries)if(std::string(entry.attribute("name").as_string())==selected){if(file)return TD_INVALID;file=entry;}
        } else if(entries.size()==1)file=entries.front();
        if(!file)return TD_INVALID;
        std::string hash;
        auto hashes=v4?file:child(file,"verification");
        for(auto item:hashes.children())if(local(item.name())=="hash") {
            auto type=lower(item.attribute("type").as_string());
            if(type!="sha-256" && type!="sha256")continue;
            auto value=lower(trim(item.text().as_string()));
            if(value.size()!=64 || value.find_first_not_of("0123456789abcdef")!=std::string::npos || (!hash.empty() && value!=hash))return TD_INVALID;
            hash=value;
        }
        td_task_options options{};
        std::memcpy(&options,transfer,std::min<size_t>(transfer->struct_size,sizeof(options)));
        options.struct_size=sizeof(options);options.mirrors=nullptr;options.mirror_count=0;
        if(options.expected_sha256 && *options.expected_sha256) {
            auto provided=lower(options.expected_sha256);
            if(!hash.empty() && hash!=provided)return TD_INVALID;
            hash=provided;
        }
        if(hash.empty())return TD_UNSUPPORTED; // no unverified mirror mixing
        struct Url {int64_t priority;std::string value;};
        std::vector<Url> urls;
        auto resources=v4?file:child(file,"resources");
        for(auto item:resources.children())if(local(item.name())=="url") {
            if(urls.size()>=32)return TD_INVALID;
            const auto priority=trim(item.attribute(v4?"priority":"preference").as_string());
            auto rank=priority.empty()?999999:integer(priority);
            if(v3)rank=999999-rank;
            urls.push_back({rank,resolve(base,trim(item.text().as_string()))});
        }
        if(urls.empty())return TD_UNSUPPORTED;
        std::stable_sort(urls.begin(),urls.end(),[](const auto& a,const auto& b){return a.priority<b.priority;});
        std::vector<td_source_options> mirrors;
        for(size_t i=1;i<urls.size();++i)mirrors.push_back({sizeof(td_source_options),TD_ABI_VERSION,urls[i].value.c_str(),nullptr,0,0,nullptr});
        options.url=urls.front().value.c_str();options.expected_sha256=hash.c_str();
        options.mirrors=mirrors.data();options.mirror_count=static_cast<uint32_t>(mirrors.size());
        // XML cannot authorize sending credentials to any supplied URL.
        // Protected sources use the explicit task/source API instead.
        if(options.header_count || options.network)return TD_UNSUPPORTED;
        auto size=child(file,"size");int64_t expected_size=0;
        if(size){expected_size=integer(trim(size.text().as_string()));options.expected_size=&expected_size;}
        return td_task_submit(engine,&options,out);
    } catch(const std::invalid_argument&) {return TD_INVALID;}
      catch(...) {return TD_INTERNAL;}
}
