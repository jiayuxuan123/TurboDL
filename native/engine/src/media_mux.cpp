#include "media_mux.hpp"
#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdio>
#include <limits>
#include <memory>
#include <openssl/evp.h>
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/bsf.h>
#include <libavutil/avutil.h>
#include <libavutil/mem.h>
}

namespace td {
namespace {
void cancelled(const std::atomic<bool>& stop){if(stop.load())throw MediaCancelled();}
FILE* read_file(const fs::path& path){
#ifdef _WIN32
    return _wfopen(path.c_str(),L"rb");
#else
    return std::fopen(path.c_str(),"rb");
#endif
}
using File=std::unique_ptr<FILE,decltype(&std::fclose)>;
struct CipherDeleter {void operator()(EVP_CIPHER_CTX* c)const{EVP_CIPHER_CTX_free(c);}};
struct PacketDeleter {void operator()(AVPacket* p)const{av_packet_free(&p);}};
using Packet=std::unique_ptr<AVPacket,PacketDeleter>;
struct Concat {
    const std::atomic<bool>& stop;
    std::vector<fs::path> files;
    std::vector<int64_t> ends;
    int64_t size=0,position=0;
    size_t opened=SIZE_MAX;
    File current{nullptr,&std::fclose};
    explicit Concat(const MediaInput& input,const std::atomic<bool>& token):stop(token),files(input.pieces){
        if(files.empty() || files.size()>20000)throw IntegrityError("Invalid media input count");
        for(const auto& path:files){auto n=target_size(path);if(n>static_cast<uint64_t>(INT64_MAX-size))throw IntegrityError("Media input exceeds file limits");size+=static_cast<int64_t>(n);ends.push_back(size);}
    }
    int read(uint8_t* buffer,int capacity){
        cancelled(stop);if(position>=size)return AVERROR_EOF;
        auto index=static_cast<size_t>(std::upper_bound(ends.begin(),ends.end(),position)-ends.begin());
        if(index>=files.size())return AVERROR_EOF;
        if(index!=opened){current.reset(read_file(files[index]));if(!current)throw std::runtime_error("Cannot read media cache");opened=index;}
        auto begin=index?ends[index-1]:0;seek_target(current.get(),static_cast<uint64_t>(position-begin));
        auto count=static_cast<size_t>(std::min<int64_t>(capacity,ends[index]-position));
        auto read=std::fread(buffer,1,count,current.get());if(!read)throw IntegrityError("Media cache ended early");position+=static_cast<int64_t>(read);return static_cast<int>(read);
    }
    static int read_cb(void* value,uint8_t* buffer,int size){try{return static_cast<Concat*>(value)->read(buffer,size);}catch(const MediaCancelled&){return AVERROR_EXIT;}catch(...){return AVERROR(EIO);}}
    static int64_t seek_cb(void* value,int64_t offset,int whence){
        auto& c=*static_cast<Concat*>(value);if(c.stop.load())return AVERROR_EXIT;
        if(whence&AVSEEK_SIZE)return c.size;
        whence&=~AVSEEK_FORCE;
        int64_t base=whence==SEEK_SET?0:whence==SEEK_CUR?c.position:whence==SEEK_END?c.size:-1;
        if(base<0 || (offset<0 && offset<-base) || (offset>0 && offset>c.size-base))return AVERROR(EINVAL);
        c.position=base+offset;return c.position;
    }
};
struct Input {
    Concat data;
    AVFormatContext* format=nullptr;
    AVIOContext* io=nullptr;
    std::vector<int> output_streams;
    std::vector<AVBSFContext*> filters;
    std::vector<int64_t> offset,last_dts;
    AVBSFContext* draining=nullptr;
    unsigned draining_stream=0,flush_stream=0;
    bool eof=false;
    Packet packet{av_packet_alloc()};
    explicit Input(const MediaInput& pieces,const std::atomic<bool>& stop):data(pieces,stop){
        try{
            if(!packet)throw std::bad_alloc();
            auto* buffer=static_cast<uint8_t*>(av_malloc(65536));if(!buffer)throw std::bad_alloc();
            io=avio_alloc_context(buffer,65536,0,&data,&Concat::read_cb,nullptr,&Concat::seek_cb);
            if(!io){av_free(buffer);throw std::bad_alloc();}
            format=avformat_alloc_context();if(!format)throw std::bad_alloc();
            format->pb=io;format->flags|=AVFMT_FLAG_CUSTOM_IO|AVFMT_FLAG_GENPTS;
            format->probesize=1048576;format->max_analyze_duration=3000000;format->max_streams=16;format->max_index_size=1048576;
            format->interrupt_callback={[](void* p){return static_cast<const std::atomic<bool>*>(p)->load()?1:0;},const_cast<std::atomic<bool>*>(&stop)};
            AVDictionary* options=nullptr;av_dict_set(&options,"protocol_whitelist","file",0);av_dict_set(&options,"enable_drefs","0",0);
            auto result=avformat_open_input(&format,nullptr,nullptr,&options);av_dict_free(&options);
            if(result<0 || avformat_find_stream_info(format,nullptr)<0){cancelled(stop);throw IntegrityError("Downloaded fragments are not valid media");}
            output_streams.assign(format->nb_streams,-1);filters.resize(format->nb_streams,nullptr);
            offset.resize(format->nb_streams,0);last_dts.resize(format->nb_streams,AV_NOPTS_VALUE);
        }catch(...){close();throw;}
    }
    ~Input(){close();}
    void close(){for(auto*& filter:filters)av_bsf_free(&filter);if(format)avformat_close_input(&format);if(io){av_freep(&io->buffer);avio_context_free(&io);}}
    bool next(){
        av_packet_unref(packet.get());
        while(true){
            cancelled(data.stop);
            if(draining){
                auto result=av_bsf_receive_packet(draining,packet.get());
                if(result==0){packet->stream_index=static_cast<int>(draining_stream);break;}
                if(result!=AVERROR(EAGAIN) && result!=AVERROR_EOF)throw IntegrityError("Audio packet conversion failed");draining=nullptr;
            }
            if(eof){
                while(flush_stream<filters.size() && !filters[flush_stream])++flush_stream;
                if(flush_stream==filters.size())return false;
                draining_stream=flush_stream++;draining=filters[draining_stream];av_bsf_send_packet(draining,nullptr);continue;
            }
            auto result=av_read_frame(format,packet.get());
            if(result==AVERROR_EOF){eof=true;continue;}
            if(result<0){cancelled(data.stop);throw IntegrityError("Media packet read failed");}
            if(packet->size<0 || packet->size>16*1024*1024)throw IntegrityError("Media packet exceeds limit");
            auto index=static_cast<unsigned>(packet->stream_index);
            if(index>=output_streams.size() || output_streams[index]<0){av_packet_unref(packet.get());continue;}
            if(filters[index]){draining=filters[index];draining_stream=index;if(av_bsf_send_packet(draining,packet.get())<0)throw IntegrityError("Audio packet conversion failed");continue;}
            break;
        }
        return true;
    }
};
struct Output {
    AVFormatContext* format=nullptr;
    ~Output(){if(format){if(format->pb)avio_closep(&format->pb);avformat_free_context(format);}}
};
}

void decrypt_media_aes128(const fs::path& source,const fs::path& destination,const fs::path& key_path,const std::array<uint8_t,16>& iv,const std::atomic<bool>& stop){
    cancelled(stop);std::array<uint8_t,17> key{};File key_file(read_file(key_path),&std::fclose);
    if(!key_file || std::fread(key.data(),1,key.size(),key_file.get())!=16)throw IntegrityError("HLS AES-128 key must contain 16 bytes");
    File input(read_file(source),&std::fclose),output(open_target(destination,false),&std::fclose);
    if(!input || !output)throw std::runtime_error("Cannot open media processing cache");
    std::unique_ptr<EVP_CIPHER_CTX,CipherDeleter> cipher(EVP_CIPHER_CTX_new());
    if(!cipher || EVP_DecryptInit_ex(cipher.get(),EVP_aes_128_cbc(),nullptr,key.data(),iv.data())!=1)throw IntegrityError("HLS cipher initialization failed");
    std::array<uint8_t,65536> encoded{};std::array<uint8_t,65552> decoded{};size_t n;
    while((n=std::fread(encoded.data(),1,encoded.size(),input.get()))){
        cancelled(stop);int bytes=0;if(EVP_DecryptUpdate(cipher.get(),decoded.data(),&bytes,encoded.data(),static_cast<int>(n))!=1)throw IntegrityError("HLS decryption failed");
        if(bytes && std::fwrite(decoded.data(),1,static_cast<size_t>(bytes),output.get())!=static_cast<size_t>(bytes))throw std::runtime_error("Cannot write decrypted media");
    }
    if(std::ferror(input.get()))throw std::runtime_error("Cannot read encrypted media");
    cancelled(stop);int bytes=0;if(EVP_DecryptFinal_ex(cipher.get(),decoded.data(),&bytes)!=1)throw IntegrityError("HLS cipher padding is invalid");
    if(bytes && std::fwrite(decoded.data(),1,static_cast<size_t>(bytes),output.get())!=static_cast<size_t>(bytes))throw std::runtime_error("Cannot write decrypted media");
    sync_target(output.get());
}

MediaProduct mux_media(const std::vector<MediaInput>& pieces,const fs::path& destination,const std::atomic<bool>& stop){
    cancelled(stop);if(pieces.empty() || pieces.size()>8)throw IntegrityError("Invalid media track count");
    std::vector<std::unique_ptr<Input>> inputs;
    Output output;auto name=utf8(destination);
    if(avformat_alloc_output_context2(&output.format,nullptr,"mp4",name.c_str())<0 || !output.format)throw std::runtime_error("Cannot create media output");
    output.format->max_interleave_delta=100000;
    for(const auto& p:pieces){
        auto in=std::make_unique<Input>(p,stop);
        for(unsigned i=0;i<in->format->nb_streams;++i){
            auto* stream=in->format->streams[i];auto type=stream->codecpar->codec_type;
            if(type!=AVMEDIA_TYPE_VIDEO && type!=AVMEDIA_TYPE_AUDIO)continue;
            if((p.kind=="video" && type!=AVMEDIA_TYPE_VIDEO) || (p.kind=="audio" && type!=AVMEDIA_TYPE_AUDIO))continue;
            if(output.format->nb_streams>=16)throw IntegrityError("Media stream count exceeds limit");
            auto* added=avformat_new_stream(output.format,nullptr);if(!added)throw std::bad_alloc();
            auto* parameters=stream->codecpar;
            if(parameters->codec_id==AV_CODEC_ID_AAC){
                auto* filter=av_bsf_get_by_name("aac_adtstoasc");
                if(!filter || av_bsf_alloc(filter,&in->filters[i])<0)throw IntegrityError("AAC packet converter unavailable");
                if(avcodec_parameters_copy(in->filters[i]->par_in,parameters)<0)throw std::bad_alloc();
                in->filters[i]->time_base_in=stream->time_base;
                if(av_bsf_init(in->filters[i])<0)throw IntegrityError("AAC packet converter initialization failed");parameters=in->filters[i]->par_out;
            }
            if(avcodec_parameters_copy(added->codecpar,parameters)<0)throw std::bad_alloc();
            added->codecpar->codec_tag=0;added->time_base=stream->time_base;in->output_streams[i]=added->index;
        }
        inputs.push_back(std::move(in));
    }
    if(!output.format->nb_streams)throw IntegrityError("Downloaded fragments have no supported streams");
    if(avio_open(&output.format->pb,name.c_str(),AVIO_FLAG_WRITE)<0)throw std::runtime_error("Cannot write media output");
    AVDictionary* options=nullptr;av_dict_set(&options,"movflags","faststart",0);
    auto header=avformat_write_header(output.format,&options);av_dict_free(&options);if(header<0)throw IntegrityError("Media output header failed");
    std::vector<bool> pending;for(auto& input:inputs)pending.push_back(input->next());
    uint64_t packets=0;
    while(true){
        cancelled(stop);size_t selected=inputs.size();int64_t earliest=INT64_MAX;
        for(size_t i=0;i<inputs.size();++i)if(pending[i]){
            auto& in=*inputs[i];auto* packet=in.packet.get();auto* stream=in.format->streams[packet->stream_index];
            auto timestamp=packet->dts!=AV_NOPTS_VALUE?packet->dts:packet->pts;
            auto time=timestamp==AV_NOPTS_VALUE?0:av_rescale_q(timestamp,stream->time_base,AV_TIME_BASE_Q)-(in.format->start_time==AV_NOPTS_VALUE?0:in.format->start_time);
            if(selected==inputs.size() || time<earliest){selected=i;earliest=time;}
        }
        if(selected==inputs.size())break;
        auto& in=*inputs[selected];auto* packet=in.packet.get();auto index=static_cast<unsigned>(packet->stream_index);auto* source=in.format->streams[index];auto out=in.output_streams[index];
        auto origin=in.format->start_time==AV_NOPTS_VALUE?0:av_rescale_q(in.format->start_time,AV_TIME_BASE_Q,source->time_base);
        if(packet->dts!=AV_NOPTS_VALUE){
            packet->dts-=origin;
            if(in.last_dts[index]!=AV_NOPTS_VALUE && packet->dts+in.offset[index]<=in.last_dts[index])in.offset[index]=in.last_dts[index]+std::max<int64_t>(1,packet->duration)-packet->dts;
            packet->dts+=in.offset[index];in.last_dts[index]=packet->dts;
        }
        if(packet->pts!=AV_NOPTS_VALUE)packet->pts=packet->pts-origin+in.offset[index];
        av_packet_rescale_ts(packet,source->time_base,output.format->streams[out]->time_base);packet->stream_index=out;packet->pos=-1;
        if(av_interleaved_write_frame(output.format,packet)<0)throw IntegrityError("Media packet output failed");++packets;
        pending[selected]=in.next();
    }
    if(!packets)throw IntegrityError("Downloaded fragments contain no media packets");
    cancelled(stop);if(av_write_trailer(output.format)<0)throw std::runtime_error("Media output commit failed");
    if(avio_closep(&output.format->pb)<0)throw std::runtime_error("Media output close failed");
    File synced(open_target(destination,true),&std::fclose);if(!synced)throw std::runtime_error("Cannot synchronize media output");sync_target(synced.get());synced.reset();
    cancelled(stop);return {target_size(destination),sha256_file(destination,[&]{return stop.load();})};
}
}
