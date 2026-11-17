#include "hls_manifest.hpp"
#include <curl/curl.h>
#include <functional>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {
void check(bool value,const char* reason){if(!value)throw std::runtime_error(reason);}
const std::string base="https://media.example.test/channel/list.m3u8?signature=synthetic";
const std::string header="#EXTM3U\n#EXT-X-TARGETDURATION:4\n";
void invalid(const std::string& text){bool rejected=false;try{td::parse_hls_manifest(text,base);}catch(const std::invalid_argument&){rejected=true;}check(rejected,"Invalid playlist accepted");}
}
int main(){
    if(curl_global_init(CURL_GLOBAL_DEFAULT)!=CURLE_OK)return 1;
    unsigned passed=0;
    try{
        {
            auto p=td::parse_hls_manifest(header+"#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:2.5,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:4,\nb.ts\n#EXT-X-ENDLIST\n",base);
            check(p.segments.size()==2 && p.ended,"VOD sequence missing");
            check(p.segments[0].url=="https://media.example.test/channel/a.ts","Incorrect relative URI");
            check(p.segments[0].sequence==7 && p.segments[1].sequence==8 && p.segments[1].discontinuity==1,"Incorrect sequence/timeline");
            check(p.segments[0].duration==2.5,"Incorrect duration");++passed;
        }
        {
            auto p=td::parse_hls_manifest("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Main\",LANGUAGE=\"zh\",DEFAULT=YES,URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS=\"avc1.4d401f,mp4a.40.2\",AUDIO=\"audio\"\nvideo.m3u8\n",base);
            check(p.variants.size()==1 && p.audio.size()==1,"Master track groups missing");
            check(p.variants[0].codecs=="avc1.4d401f,mp4a.40.2" && p.variants[0].bandwidth==800000,"Quoted attribute split incorrectly");
            check(p.audio[0].preferred && p.audio[0].language=="zh","Rendition defaults missing");++passed;
        }
        {
            auto p=td::parse_hls_manifest(header+"#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x1\n#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"100@20\"\n#EXT-X-BYTERANGE:200@120\n#EXTINF:4,\nfile.mp4\n#EXT-X-BYTERANGE:300\n#EXTINF:4,\nfile.mp4\n#EXT-X-ENDLIST\n",base);
            check(p.segments[0].init->range->offset==20 && p.segments[0].init->key->iv[15]==1,"Initialization key/range lost");
            check(p.segments[1].range->offset==320 && p.segments[1].range->length==300,"Implicit byte offset incorrect");++passed;
        }
        {
            auto p=td::parse_hls_manifest(header+"#EXT-X-MEDIA-SEQUENCE:65537\n#EXT-X-KEY:METHOD=AES-128,URI=\"one.key\"\n#EXTINF:4,\none.ts\n#EXT-X-KEY:METHOD=AES-128,URI=\"two.key\"\n#EXTINF:4,\ntwo.ts\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\nplain.ts\n#EXT-X-ENDLIST\n",base);
            check(p.segments[0].key->iv[13]==1 && p.segments[0].key->iv[15]==1,"Sequence IV is not big endian");
            check(p.segments[1].key->iv[15]==2 && p.segments[0].key->url!=p.segments[1].key->url && !p.segments[2].key,"Rotating key state leaked");++passed;
        }
        {
            auto p=td::parse_hls_manifest(header+"#EXT-X-GAP\n#EXTINF:4,\ngap.ts\n#EXTINF:4,\ncurrent.ts\n",base);
            check(!p.ended && p.segments[0].gap && !p.segments[1].gap,"Live/gap state incorrect");++passed;
        }
        invalid(header+"#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXTINF:4,\na.ts\n");
        invalid(header+"#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXT-X-MAP:URI=\"init\"\n#EXTINF:4,\na.ts\n");++passed;
        invalid(header+"#EXT-X-BYTERANGE:10\n#EXTINF:4,\na.ts\n");
        invalid(header+"#EXT-X-BYTERANGE:10@0\n#EXTINF:4,\na.ts\n#EXT-X-BYTERANGE:10\n#EXTINF:4,\nb.ts\n");
        invalid(header+"#EXT-X-BYTERANGE:2@9223372036854775807\n#EXTINF:4,\na.ts\n");++passed;
        invalid(header+"#EXTINF:NaN,\na.ts\n");invalid(header+"#EXTINF:4,\nfile:///local\n");
        invalid(header+"#EXTINF:4,\nhttps://fixture-user@media.example.test/private\n");
        invalid(header+"#EXT-X-DEFINE:NAME=\"x\",VALUE=\"a\"\n#EXTINF:4,\n{$x}.ts\n");++passed;
        invalid(header+"#EXTINF:4,\n");invalid(header+"#EXT-X-ENDLIST\n#EXTINF:4,\na.ts\n");
        invalid(std::string(1048577,'x'));++passed;
        std::cout<<"HLS_MANIFEST_CASES_PASSED "<<passed<<"\n";curl_global_cleanup();return 0;
    }catch(const std::exception& e){std::cerr<<"HLS_MANIFEST_FAILURE after "<<passed<<": "<<e.what()<<"\n";curl_global_cleanup();return 1;}
}
