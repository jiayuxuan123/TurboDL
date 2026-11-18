#include "dash_manifest.hpp"
#include <pugixml.hpp>
#include <curl/curl.h>
#include <algorithm>
#include <charconv>
#include <cmath>
#include <locale>
#include <sstream>
#include <stdexcept>

namespace td {
namespace {
std::string local(const char* text){std::string s=text;auto colon=s.find(':');return colon==std::string::npos?s:s.substr(colon+1);}
pugi::xml_node child(pugi::xml_node n,const char* name){for(auto c:n.children())if(local(c.name())==name)return c;return {};}
uint64_t integer(const std::string& s){uint64_t n=0;auto [p,e]=std::from_chars(s.data(),s.data()+s.size(),n);if(e!=std::errc{} || p!=s.data()+s.size() || n>INT64_MAX)throw std::invalid_argument("Invalid DASH integer");return n;}
std::string trim(std::string s){auto p=s.find_first_not_of(" \n\r\t");return p==std::string::npos?"":s.substr(p,s.find_last_not_of(" \n\r\t")-p+1);}
double duration(const std::string& s){
    if(s.empty() || s[0]!='P')throw std::invalid_argument("Invalid DASH duration");
    size_t i=1;bool time=false;double seconds=0;unsigned fields=0;
    while(i<s.size()){
        if(s[i]=='T'){if(time)throw std::invalid_argument("Invalid DASH duration");time=true;++i;continue;}
        auto begin=i;while(i<s.size() && (std::isdigit(static_cast<unsigned char>(s[i])) || s[i]=='.'))++i;
        if(begin==i || i==s.size())throw std::invalid_argument("Invalid DASH duration");
        std::istringstream in(s.substr(begin,i-begin));in.imbue(std::locale::classic());double v=0;in>>std::noskipws>>v;
        if(!in || in.peek()!=std::char_traits<char>::eof() || !std::isfinite(v) || v<0)throw std::invalid_argument("Invalid DASH duration");
        char unit=s[i++];double multiplier=0;
        if(!time && unit=='D')multiplier=86400;else if(time && unit=='H')multiplier=3600;else if(time && unit=='M')multiplier=60;else if(time && unit=='S')multiplier=1;
        else throw std::invalid_argument("Unsupported DASH calendar duration");
        seconds+=v*multiplier;++fields;
        if(!std::isfinite(seconds) || seconds>31536000)throw std::invalid_argument("DASH duration exceeds bounds");
    }
    if(!fields)throw std::invalid_argument("Empty DASH duration");return seconds;
}
uint64_t ticks(double seconds,uint64_t scale){long double v=static_cast<long double>(seconds)*scale;if(v<0 || v>INT64_MAX)throw std::invalid_argument("DASH timeline overflow");return static_cast<uint64_t>(std::ceil(v));}
std::string resolve(const std::string& base,const std::string& value){
    auto* u=curl_url();if(!u)throw std::bad_alloc();struct Guard{CURLU* p;~Guard(){curl_url_cleanup(p);}} guard{u};
    if(curl_url_set(u,CURLUPART_URL,base.c_str(),0)!=CURLUE_OK || (!value.empty() && curl_url_set(u,CURLUPART_URL,value.c_str(),0)!=CURLUE_OK))throw std::invalid_argument("Invalid DASH URI");
    auto part=[&](CURLUPart k){char* p=nullptr;std::string s;if(curl_url_get(u,k,&p,0)==CURLUE_OK)s=p;curl_free(p);return s;};
    auto scheme=part(CURLUPART_SCHEME);
    if((scheme!="http" && scheme!="https") || part(CURLUPART_HOST).empty() || !part(CURLUPART_USER).empty() || !part(CURLUPART_PASSWORD).empty())throw std::invalid_argument("Unsupported DASH URI");
    return part(CURLUPART_URL);
}
std::string base_for(pugi::xml_node n,const std::string& parent){auto b=child(n,"BaseURL");return b?resolve(parent,trim(b.text().as_string())):parent;}
void bounded(pugi::xml_node n,unsigned depth=0){if(depth>20)throw std::invalid_argument("DASH nesting exceeds bounds");unsigned count=0;for(auto c:n.children()){if(++count>12000)throw std::invalid_argument("DASH item count exceeds bounds");bounded(c,depth+1);}for(auto a:n.attributes())if(local(a.name())=="href" && std::string(a.name()).find(':')!=std::string::npos)throw std::invalid_argument("DASH external XML links are unsupported");}
std::string attr(const std::vector<pugi::xml_node>& nodes,const char* name,const std::string& fallback=""){
    for(auto i=nodes.rbegin();i!=nodes.rend();++i)if(auto a=i->attribute(name))return a.value();return fallback;
}
pugi::xml_node inherited_child(const std::vector<pugi::xml_node>& nodes,const char* name){for(auto i=nodes.rbegin();i!=nodes.rend();++i)if(auto c=child(*i,name))return c;return {};}
std::string expand(const std::string& pattern,const DashTrack& track,uint64_t number,uint64_t time){
    std::string result;
    for(size_t i=0;i<pattern.size();){
        if(pattern[i]!='$'){result+=pattern[i++];continue;}
        if(i+1<pattern.size() && pattern[i+1]=='$'){result+='$';i+=2;continue;}
        auto end=pattern.find('$',i+1);if(end==std::string::npos)throw std::invalid_argument("Unclosed DASH template");
        auto token=pattern.substr(i+1,end-i-1);auto format=token.find('%');unsigned width=0;
        if(format!=std::string::npos){auto spec=token.substr(format);token.resize(format);if(spec.size()<4 || !spec.starts_with("%0") || spec.back()!='d')throw std::invalid_argument("Unsupported DASH number format");width=static_cast<unsigned>(integer(spec.substr(2,spec.size()-3)));if(width>18)throw std::invalid_argument("DASH template width exceeds bounds");}
        std::string value;
        if(token=="RepresentationID"){if(width)throw std::invalid_argument("Invalid DASH ID format");value=track.id;}
        else if(token=="Bandwidth")value=std::to_string(track.bandwidth);
        else if(token=="Number")value=std::to_string(number);else if(token=="Time")value=std::to_string(time);
        else throw std::invalid_argument("Unsupported DASH template variable");
        if(width>value.size())result.append(width-value.size(),'0');result+=value;i=end+1;
        if(result.size()>8192)throw std::invalid_argument("DASH expanded URI exceeds bounds");
    }
    return result;
}
MediaByteRange byte_range(const std::string& s){auto dash=s.find('-');if(dash==std::string::npos)throw std::invalid_argument("Invalid DASH range");auto a=integer(s.substr(0,dash)),b=integer(s.substr(dash+1));if(b<a || b==INT64_MAX)throw std::invalid_argument("Invalid DASH range");return {a,b-a+1};}
struct Point {uint64_t time,duration;};
std::vector<Point> timeline(pugi::xml_node node,uint64_t scale,uint64_t pto,double period_duration){
    std::vector<pugi::xml_node> entries;for(auto c:node.children())if(local(c.name())=="S")entries.push_back(c);
    std::vector<Point> out;uint64_t time=0;
    for(size_t i=0;i<entries.size();++i){
        auto s=entries[i];if(s.attribute("t"))time=integer(s.attribute("t").value());auto d=integer(s.attribute("d").value());if(!d)throw std::invalid_argument("Empty DASH segment duration");
        auto repeat=std::string(s.attribute("r").as_string("0"));uint64_t count=0;
        if(repeat=="-1"){
            uint64_t end=0;
            if(i+1<entries.size() && entries[i+1].attribute("t"))end=integer(entries[i+1].attribute("t").value());
            else if(period_duration>0){auto span=ticks(period_duration,scale);if(span>INT64_MAX-pto)throw std::invalid_argument("DASH timeline overflow");end=span+pto;}
            else throw std::invalid_argument("Unbounded DASH timeline requires a recording window");
            if(end<=time)throw std::invalid_argument("DASH timeline does not advance");count=(end-time-1)/d+1;
        }else count=integer(repeat)+1;
        if(count>10000-out.size())throw std::invalid_argument("DASH segment count exceeds bounds");
        for(uint64_t n=0;n<count;++n){if(time>INT64_MAX-d)throw std::invalid_argument("DASH timeline overflow");out.push_back({time,d});time+=d;}
    }
    return out;
}
DashTrack representation(pugi::xml_node period,pugi::xml_node adaptation,pugi::xml_node rep,const std::string& parent,double period_duration){
    DashTrack track;track.id=rep.attribute("id").as_string();track.language=adaptation.attribute("lang").as_string();track.codecs=rep.attribute("codecs").as_string(adaptation.attribute("codecs").as_string());
    track.bandwidth=integer(rep.attribute("bandwidth").as_string("0"));
    track.kind=adaptation.attribute("contentType").as_string();auto mime=std::string(rep.attribute("mimeType").as_string(adaptation.attribute("mimeType").as_string()));
    if(track.kind.empty()){if(mime.starts_with("audio/"))track.kind="audio";else if(mime.starts_with("video/"))track.kind="video";}
    if(track.kind!="video" && track.kind!="audio")throw std::invalid_argument("Unsupported DASH track type");
    if(child(adaptation,"ContentProtection") || child(rep,"ContentProtection"))throw std::invalid_argument("DRM protected DASH is unsupported");
    auto base=base_for(rep,parent);
    std::vector<pugi::xml_node> templates,lists;
    for(auto n:{period,adaptation,rep}){if(auto t=child(n,"SegmentTemplate"))templates.push_back(t);if(auto t=child(n,"SegmentList"))lists.push_back(t);}
    std::optional<HlsMap> init;
    if(!templates.empty()){
        auto scale=integer(attr(templates,"timescale","1"));if(!scale)throw std::invalid_argument("Invalid DASH timescale");
        auto first=integer(attr(templates,"startNumber","1")),pto=integer(attr(templates,"presentationTimeOffset","0"));
        auto media=attr(templates,"media"),initial=attr(templates,"initialization");if(media.empty())throw std::invalid_argument("Missing DASH media template");
        if(!initial.empty())init=HlsMap{resolve(base,expand(initial,track,first,pto)),std::nullopt,std::nullopt};
        auto points=timeline(inherited_child(templates,"SegmentTimeline"),scale,pto,period_duration);
        if(points.empty()){
            auto d=integer(attr(templates,"duration","0"));if(!d || period_duration<=0)throw std::invalid_argument("DASH template requires a bounded timeline");
            auto span=ticks(period_duration,scale),count=(span+d-1)/d;if(count>10000)throw std::invalid_argument("DASH segment count exceeds bounds");
            for(uint64_t i=0;i<count;++i){if(i>(INT64_MAX-pto)/d)throw std::invalid_argument("DASH timeline overflow");points.push_back({pto+i*d,d});}
        }
        for(size_t i=0;i<points.size();++i){if(i>INT64_MAX-first)throw std::invalid_argument("DASH number overflow");auto point=points[i];track.segments.push_back({resolve(base,expand(media,track,first+i,point.time)),std::nullopt,init,first+i,point.time,static_cast<double>(point.duration)/scale});}
    }else if(!lists.empty()){
        auto scale=integer(attr(lists,"timescale","1"));if(!scale)throw std::invalid_argument("Invalid DASH timescale");
        if(auto initial=inherited_child(lists,"Initialization")){
            HlsMap map;map.url=resolve(base,initial.attribute("sourceURL").as_string());if(initial.attribute("range"))map.range=byte_range(initial.attribute("range").value());init=map;
        }
        auto points=timeline(inherited_child(lists,"SegmentTimeline"),scale,0,period_duration);auto d=integer(attr(lists,"duration","0"));
        auto source=lists.back();uint64_t time=0,index=0;
        for(auto segment:source.children())if(local(segment.name())=="SegmentURL"){
            if(index>=10000)throw std::invalid_argument("DASH segment count exceeds bounds");
            DashSegment s;s.url=resolve(base,segment.attribute("media").as_string());s.init=init;s.number=index;
            if(segment.attribute("mediaRange"))s.range=byte_range(segment.attribute("mediaRange").value());
            if(index<points.size()){s.time=points[index].time;s.duration=static_cast<double>(points[index].duration)/scale;}
            else{s.time=time;s.duration=static_cast<double>(d)/scale;}
            if(d>INT64_MAX-time)throw std::invalid_argument("DASH timeline overflow");time+=d;
            track.segments.push_back(std::move(s));++index;
        }
        if(!points.empty() && points.size()!=track.segments.size())throw std::invalid_argument("DASH segment list/timeline mismatch");
    }else{
        // A complete single representation file already contains its index/init.
        if(!child(rep,"BaseURL") && !child(adaptation,"BaseURL"))throw std::invalid_argument("Missing DASH segment addressing");
        track.segments.push_back({base,std::nullopt,std::nullopt,0,0,period_duration});
    }
    if(track.segments.empty())throw std::invalid_argument("Empty DASH representation");return track;
}
}

DashManifest parse_dash_manifest(const std::string& content,const std::string& base,const DashSelection& selection){
    if(content.empty() || content.size()>1048576 || content.find('\0')!=std::string::npos)throw std::invalid_argument("DASH manifest exceeds bounds");
    auto folded=content;for(auto& c:folded)c=static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    if(folded.find("<!doctype")!=std::string::npos || folded.find("<!entity")!=std::string::npos)throw std::invalid_argument("DASH XML entities are unsupported");
    pugi::xml_document doc;if(!doc.load_buffer(content.data(),content.size(),pugi::parse_default,pugi::encoding_utf8))throw std::invalid_argument("Invalid DASH XML");bounded(doc);
    auto root=doc.document_element();if(local(root.name())!="MPD")throw std::invalid_argument("Missing DASH MPD");
    auto name=std::string(root.name());auto colon=name.find(':');auto ns=root.attribute(colon==std::string::npos?"xmlns":("xmlns:"+name.substr(0,colon)).c_str()).as_string();
    if(std::string(ns)!="urn:mpeg:dash:schema:mpd:2011")throw std::invalid_argument("Unsupported DASH namespace");
    DashManifest plan;plan.dynamic=std::string(root.attribute("type").as_string())=="dynamic";
    if(root.attribute("minimumUpdatePeriod"))plan.minimum_update_seconds=std::max(.25,duration(root.attribute("minimumUpdatePeriod").value()));
    double total=root.attribute("mediaPresentationDuration")?duration(root.attribute("mediaPresentationDuration").value()):0;
    auto root_base=base_for(root,base);unsigned periods=0;
    for(auto period:root.children())if(local(period.name())=="Period"){
        if(++periods>32)throw std::invalid_argument("Too many DASH periods");
        auto start=period.attribute("start")?duration(period.attribute("start").value()):0;
        double span=period.attribute("duration")?duration(period.attribute("duration").value()):total>start?total-start:0;
        if(auto next=period.next_sibling("Period");next && next.attribute("start")){auto next_start=duration(next.attribute("start").value());if(next_start>start)span=next_start-start;}
        auto period_base=base_for(period,root_base);std::vector<DashTrack> choices;
        for(auto adaptation:period.children())if(local(adaptation.name())=="AdaptationSet"){
            auto adaptation_base=base_for(adaptation,period_base);
            for(auto rep:adaptation.children())if(local(rep.name())=="Representation"){
                if(choices.size()>=256)throw std::invalid_argument("Too many DASH representations");
                auto mime=std::string(rep.attribute("mimeType").as_string(adaptation.attribute("mimeType").as_string()));
                auto kind=std::string(adaptation.attribute("contentType").as_string());
                if(kind!="audio" && kind!="video" && !mime.starts_with("audio/") && !mime.starts_with("video/"))continue;
                choices.push_back(representation(period,adaptation,rep,adaptation_base,span));
            }
        }
        for(auto kind:{std::string("video"),std::string("audio")}){
            DashTrack* best=nullptr;auto requested=kind=="video"?selection.video_id:selection.audio_id;
            for(auto& track:choices){
                if(track.kind!=kind || (!requested.empty() && track.id!=requested))continue;
                bool language=kind=="audio" && !selection.audio_language.empty() && track.language==selection.audio_language;
                bool best_language=best && kind=="audio" && !selection.audio_language.empty() && best->language==selection.audio_language;
                bool eligible=!selection.maximum_bandwidth || track.bandwidth<=selection.maximum_bandwidth;
                bool best_eligible=best && (!selection.maximum_bandwidth || best->bandwidth<=selection.maximum_bandwidth);
                if(!best || (language && !best_language) || (language==best_language && ((eligible && !best_eligible) || (eligible==best_eligible && (eligible?track.bandwidth>best->bandwidth:track.bandwidth<best->bandwidth)))))best=&track;
            }
            if(!requested.empty() && !best)throw std::invalid_argument("Requested DASH representation unavailable");
            if(best)plan.tracks.push_back(std::move(*best));
        }
    }
    if(plan.tracks.empty())throw std::invalid_argument("No supported DASH tracks");return plan;
}
}
