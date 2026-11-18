#include "hls_manifest.hpp"
#include <curl/curl.h>
#include <charconv>
#include <cmath>
#include <limits>
#include <locale>
#include <map>
#include <sstream>
#include <stdexcept>

namespace td {
namespace {
std::string trim(std::string value){auto first=value.find_first_not_of(" \t\r");return first==std::string::npos?"":value.substr(first,value.find_last_not_of(" \t\r")-first+1);}
uint64_t integer(const std::string& value){uint64_t n=0;auto [p,e]=std::from_chars(value.data(),value.data()+value.size(),n);if(e!=std::errc{} || p!=value.data()+value.size() || n>INT64_MAX)throw std::invalid_argument("Invalid HLS integer");return n;}
double decimal(const std::string& value){
    if(value.empty() || value.find_first_not_of("0123456789.")!=std::string::npos || value.find_first_of("0123456789")==std::string::npos)throw std::invalid_argument("Invalid HLS duration");
    // Floating-point from_chars is absent in libc++ on some supported targets.
    // Classic locale keeps playlist decimals independent of the host locale.
    std::istringstream input(value);input.imbue(std::locale::classic());double n=0;
    input>>std::noskipws>>n;
    if(!input || input.peek()!=std::char_traits<char>::eof() || !std::isfinite(n) || n<0 || n>86400)throw std::invalid_argument("Invalid HLS duration");
    return n;
}
std::map<std::string,std::string> attributes(const std::string& input){
    std::map<std::string,std::string> result;size_t offset=0;
    while(offset<input.size()){
        auto equal=input.find('=',offset);if(equal==std::string::npos)throw std::invalid_argument("Malformed HLS attributes");
        auto name=trim(input.substr(offset,equal-offset));offset=equal+1;std::string value;
        if(offset<input.size() && input[offset]=='"'){
            auto end=input.find('"',++offset);if(end==std::string::npos)throw std::invalid_argument("Unclosed HLS attribute");
            value=input.substr(offset,end-offset);offset=end+1;
            if(offset<input.size() && input[offset]!=',')throw std::invalid_argument("Malformed HLS quoted attribute");
        }else{auto end=input.find(',',offset);value=trim(input.substr(offset,end-offset));offset=end==std::string::npos?input.size():end;}
        if(name.empty() || !result.emplace(name,value).second)throw std::invalid_argument("Duplicate HLS attribute");
        if(result.size()>64)throw std::invalid_argument("Too many HLS attributes");
        if(offset<input.size())++offset;
    }
    return result;
}
std::string resolve(const std::string& base,const std::string& value){
    if(value.empty() || value.find("{$")!=std::string::npos || value.find_first_of("\r\n")!=std::string::npos)throw std::invalid_argument("Invalid HLS URI");
    auto* u=curl_url();if(!u)throw std::bad_alloc();
    struct Cleanup{CURLU* value;~Cleanup(){curl_url_cleanup(value);}} cleanup{u};
    if(curl_url_set(u,CURLUPART_URL,base.c_str(),0)!=CURLUE_OK || curl_url_set(u,CURLUPART_URL,value.c_str(),0)!=CURLUE_OK)throw std::invalid_argument("Invalid HLS URI");
    auto part=[&](CURLUPart key){char* p=nullptr;std::string out;if(curl_url_get(u,key,&p,0)==CURLUE_OK)out=p;curl_free(p);return out;};
    auto scheme=part(CURLUPART_SCHEME);
    if((scheme!="http" && scheme!="https") || part(CURLUPART_HOST).empty() || !part(CURLUPART_USER).empty() || !part(CURLUPART_PASSWORD).empty())throw std::invalid_argument("Unsupported HLS URI");
    return part(CURLUPART_URL);
}
MediaByteRange range(const std::string& text,std::optional<uint64_t> previous){
    auto at=text.find('@');auto length=integer(text.substr(0,at));
    auto offset=at==std::string::npos?previous:std::optional<uint64_t>(integer(text.substr(at+1)));
    if(!length || !offset || length>static_cast<uint64_t>(INT64_MAX)-*offset)throw std::invalid_argument("Invalid HLS byte range");
    return {*offset,length};
}
std::array<uint8_t,16> iv(const std::string& value){
    if(!value.starts_with("0x") || value.size()<3 || value.size()>34)throw std::invalid_argument("Invalid HLS AES IV");
    std::string digits=value.substr(2);if(digits.size()%2)digits="0"+digits;
    std::array<uint8_t,16> result{};size_t offset=16-digits.size()/2;
    for(size_t i=0;i<digits.size();i+=2){unsigned n=0;auto [p,e]=std::from_chars(digits.data()+i,digits.data()+i+2,n,16);if(e!=std::errc{} || p!=digits.data()+i+2)throw std::invalid_argument("Invalid HLS AES IV");result[offset++]=static_cast<uint8_t>(n);}
    return result;
}
}

HlsManifest parse_hls_manifest(const std::string& content,const std::string& base){
    if(content.empty() || content.size()>1048576 || content.find('\0')!=std::string::npos)throw std::invalid_argument("HLS manifest exceeds bounds");
    std::istringstream input(content);std::string line;
    if(!std::getline(input,line))throw std::invalid_argument("Missing HLS signature");
    if(line.starts_with("\xef\xbb\xbf"))line.erase(0,3);
    if(trim(line)!="#EXTM3U")throw std::invalid_argument("Missing HLS signature");
    HlsManifest plan;std::optional<HlsKey> key;std::optional<HlsMap> init;
    std::optional<double> duration;std::optional<std::string> next_range;
    std::optional<std::map<std::string,std::string>> variant;
    std::string previous_uri;std::optional<uint64_t> previous_end;
    uint64_t sequence=0,discontinuity=0;size_t lines=0;bool gap=false;
    while(std::getline(input,line)){
        if(++lines>20000 || line.size()>8192)throw std::invalid_argument("HLS manifest exceeds bounds");
        line=trim(line);if(line.empty())continue;
        if(line.starts_with("#EXT-X-DEFINE:"))throw std::invalid_argument("HLS variables require explicit resolution");
        if(line.starts_with("#EXT-X-MEDIA-SEQUENCE:")){
            if(!plan.segments.empty())throw std::invalid_argument("Late HLS media sequence");
            sequence=plan.media_sequence=integer(line.substr(22));
        }else if(line.starts_with("#EXT-X-DISCONTINUITY-SEQUENCE:"))discontinuity=integer(line.substr(30));
        else if(line=="#EXT-X-DISCONTINUITY"){
            if(discontinuity==INT64_MAX)throw std::invalid_argument("HLS discontinuity overflow");++discontinuity;
        }else if(line.starts_with("#EXT-X-TARGETDURATION:")){
            plan.target_duration=integer(line.substr(22));if(!plan.target_duration)throw std::invalid_argument("Invalid HLS target duration");
        }else if(line.starts_with("#EXTINF:")){
            if(duration)throw std::invalid_argument("Missing HLS segment URI");
            auto raw=line.substr(8);duration=decimal(raw.substr(0,raw.find(',')));
        }else if(line.starts_with("#EXT-X-BYTERANGE:")){if(next_range)throw std::invalid_argument("Duplicate HLS byte range");next_range=line.substr(17);}
        else if(line=="#EXT-X-GAP")gap=true;
        else if(line.starts_with("#EXT-X-STREAM-INF:"))variant=attributes(line.substr(18));
        else if(line.starts_with("#EXT-X-MEDIA:")){
            auto attrs=attributes(line.substr(13));
            if(attrs["TYPE"]=="AUDIO" && !attrs["URI"].empty()){
                plan.audio.push_back({resolve(base,attrs["URI"]),attrs["GROUP-ID"],attrs["NAME"],attrs["LANGUAGE"],attrs["DEFAULT"]=="YES"});
                if(plan.audio.size()>256)throw std::invalid_argument("Too many HLS renditions");
            }
        }else if(line.starts_with("#EXT-X-KEY:")){
            auto attrs=attributes(line.substr(11));
            if(attrs["METHOD"]=="NONE")key.reset();
            else{
                if(attrs["METHOD"]!="AES-128" || (!attrs["KEYFORMAT"].empty() && attrs["KEYFORMAT"]!="identity"))throw std::invalid_argument("Unsupported encrypted HLS method");
                HlsKey next;next.url=resolve(base,attrs["URI"]);next.generation=sequence;
                if(!attrs["IV"].empty()){next.iv=iv(attrs["IV"]);next.explicit_iv=true;}
                key=std::move(next);
            }
        }else if(line.starts_with("#EXT-X-MAP:")){
            auto attrs=attributes(line.substr(11));HlsMap map;map.url=resolve(base,attrs["URI"]);map.key=key;
            if(!attrs["BYTERANGE"].empty())map.range=range(attrs["BYTERANGE"],0);
            if(key && !key->explicit_iv)throw std::invalid_argument("Encrypted HLS initialization requires explicit IV");
            init=std::move(map);
        }else if(line=="#EXT-X-ENDLIST")plan.ended=true;
        else if(line[0]!='#'){
            if(plan.ended)throw std::invalid_argument("HLS segment after end list");
            auto url=resolve(base,line);
            if(variant){
                if(duration || !plan.segments.empty())throw std::invalid_argument("Mixed master/media playlist");
                auto& attrs=*variant;plan.variants.push_back({url,attrs["AUDIO"],attrs["CODECS"],integer(attrs["BANDWIDTH"])});variant.reset();
                if(plan.variants.size()>256)throw std::invalid_argument("Too many HLS variants");
                continue;
            }
            if(!duration || !plan.variants.empty())throw std::invalid_argument("HLS segment requires duration");
            HlsSegment segment;segment.url=url;segment.sequence=sequence;segment.discontinuity=discontinuity;segment.duration=*duration;segment.init=init;segment.key=key;segment.gap=gap;
            if(next_range){segment.range=range(*next_range,previous_uri==url?previous_end:std::nullopt);previous_end=segment.range->offset+segment.range->length;}
            else previous_end.reset();
            previous_uri=url;
            if(segment.key && !segment.key->explicit_iv)for(unsigned i=0;i<8;++i)segment.key->iv[15-i]=static_cast<uint8_t>(sequence>>(i*8));
            plan.segments.push_back(std::move(segment));
            if(plan.segments.size()>10000 || sequence==INT64_MAX)throw std::invalid_argument("Too many HLS segments");
            ++sequence;duration.reset();next_range.reset();gap=false;
        }
    }
    if(duration || variant || next_range || gap || (plan.segments.empty() && plan.variants.empty()) || (!plan.segments.empty() && !plan.target_duration))throw std::invalid_argument("Incomplete HLS manifest");
    return plan;
}
}
