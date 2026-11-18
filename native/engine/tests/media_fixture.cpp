// Deterministic generated tones/pictures and an independent decoder process.
// No public media, private links or production engine internals are used.
#include <algorithm>
#include <cmath>
#include <filesystem>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavutil/channel_layout.h>
#include <libavutil/mem.h>
#include <libavutil/sha.h>
}

namespace {
void checked(int result,const char* reason){if(result<0)throw std::runtime_error(reason);}
struct Output {
    AVFormatContext* context=nullptr;
    ~Output(){if(context){if(context->pb)avio_closep(&context->pb);avformat_free_context(context);}}
    Output(const std::string& path,const char* format,AVCodecContext* codec){
        checked(avformat_alloc_output_context2(&context,nullptr,format,path.c_str()),"fixture output format");
        auto* stream=avformat_new_stream(context,nullptr);if(!stream)throw std::bad_alloc();
        checked(avcodec_parameters_from_context(stream->codecpar,codec),"fixture stream parameters");stream->time_base=codec->time_base;
        checked(avio_open(&context->pb,path.c_str(),AVIO_FLAG_WRITE),"fixture output open");
        AVDictionary* options=nullptr;
        if(std::string(format)=="mp4"){av_dict_set(&options,"movflags","empty_moov+default_base_moof+frag_keyframe",0);av_dict_set(&options,"frag_duration","1000000",0);}
        auto result=avformat_write_header(context,&options);av_dict_free(&options);checked(result,"fixture output header");
    }
    void packet(const AVPacket* source,AVRational time_base){
        auto* copy=av_packet_clone(source);if(!copy)throw std::bad_alloc();av_packet_rescale_ts(copy,time_base,context->streams[0]->time_base);copy->stream_index=0;
        auto result=av_interleaved_write_frame(context,copy);av_packet_free(&copy);checked(result,"fixture packet output");
    }
    void close(){checked(av_write_trailer(context),"fixture output trailer");checked(avio_closep(&context->pb),"fixture output close");}
};
struct Codec {
    AVCodecContext* context=nullptr;
    AVFrame* frame=av_frame_alloc();AVPacket* packet=av_packet_alloc();
    ~Codec(){avcodec_free_context(&context);av_frame_free(&frame);av_packet_free(&packet);}
};
void generate(const std::filesystem::path& root,bool audio){
    const auto* encoder=avcodec_find_encoder(audio?AV_CODEC_ID_AAC:AV_CODEC_ID_MPEG4);if(!encoder)throw std::runtime_error("required fixture encoder unavailable");
    Codec codec;codec.context=avcodec_alloc_context3(encoder);if(!codec.context || !codec.frame || !codec.packet)throw std::bad_alloc();
    auto* context=codec.context;context->flags|=AV_CODEC_FLAG_GLOBAL_HEADER;
    if(audio){context->sample_rate=48000;context->sample_fmt=AV_SAMPLE_FMT_FLTP;context->time_base={1,48000};context->bit_rate=96000;av_channel_layout_default(&context->ch_layout,2);}
    else{context->width=64;context->height=48;context->pix_fmt=AV_PIX_FMT_YUV420P;context->time_base={1,10};context->framerate={10,1};context->gop_size=10;context->max_b_frames=0;context->bit_rate=100000;}
    checked(avcodec_open2(context,encoder,nullptr),"fixture encoder open");
    std::vector<std::unique_ptr<Output>> outputs;
    auto path=[&](const char* name){return (root/name).string();};
    outputs.push_back(std::make_unique<Output>(path(audio?"audio.mp4":"video.mp4"),"mp4",context));
    if(audio)outputs.push_back(std::make_unique<Output>(path("audio.aac"),"adts",context));
    auto* frame=codec.frame;frame->format=audio?static_cast<int>(context->sample_fmt):static_cast<int>(context->pix_fmt);
    if(audio){frame->sample_rate=context->sample_rate;frame->nb_samples=context->frame_size;checked(av_channel_layout_copy(&frame->ch_layout,&context->ch_layout),"fixture channel layout");}
    else{frame->width=context->width;frame->height=context->height;}
    checked(av_frame_get_buffer(frame,32),"fixture frame buffer");
    auto drain=[&]{while(true){auto result=avcodec_receive_packet(context,codec.packet);if(result==AVERROR(EAGAIN) || result==AVERROR_EOF)break;checked(result,"fixture encoder packet");for(auto& output:outputs)output->packet(codec.packet,context->time_base);av_packet_unref(codec.packet);}};
    int frames=audio?150:32;
    for(int n=0;n<frames;++n){
        checked(av_frame_make_writable(frame),"fixture frame writable");
        if(audio){
            for(int channel=0;channel<2;++channel){auto* data=reinterpret_cast<float*>(frame->data[channel]);for(int i=0;i<frame->nb_samples;++i){double time=static_cast<double>(n*frame->nb_samples+i)/48000;data[i]=static_cast<float>(.25*std::sin(6.283185307179586*(channel?660:440)*time));}}
            frame->pts=static_cast<int64_t>(n)*frame->nb_samples;
        }else{
            for(int y=0;y<48;++y)for(int x=0;x<64;++x)frame->data[0][y*frame->linesize[0]+x]=static_cast<uint8_t>((x*3+y+n*7)%220+16);
            for(int y=0;y<24;++y)for(int x=0;x<32;++x){frame->data[1][y*frame->linesize[1]+x]=static_cast<uint8_t>(64+n);frame->data[2][y*frame->linesize[2]+x]=static_cast<uint8_t>(170-n);}
            frame->pts=n;
        }
        checked(avcodec_send_frame(context,frame),"fixture encode frame");drain();
    }
    checked(avcodec_send_frame(context,nullptr),"fixture encoder flush");drain();for(auto& output:outputs)output->close();
}
struct Stream {
    AVCodecContext* decoder=nullptr;AVBSFContext* filter=nullptr;AVSHA* hash=av_sha_alloc();
    uint64_t packets=0,frames=0,samples=0;
    ~Stream(){avcodec_free_context(&decoder);av_bsf_free(&filter);av_free(hash);}
};
void inspect(const char* path){
    AVFormatContext* raw=nullptr;checked(avformat_open_input(&raw,path,nullptr,nullptr),"fixture inspect open");
    struct Close{void operator()(AVFormatContext* p)const{avformat_close_input(&p);}};
    std::unique_ptr<AVFormatContext,Close> format(raw);checked(avformat_find_stream_info(raw,nullptr),"fixture inspect streams");
    std::vector<std::unique_ptr<Stream>> streams;
    for(unsigned i=0;i<raw->nb_streams;++i){
        auto p=std::make_unique<Stream>();if(!p->hash)throw std::bad_alloc();checked(av_sha_init(p->hash,256),"fixture hash init");
        auto* parameters=raw->streams[i]->codecpar;auto* decoder=avcodec_find_decoder(parameters->codec_id);if(!decoder)throw std::runtime_error("required fixture decoder unavailable");
        p->decoder=avcodec_alloc_context3(decoder);if(!p->decoder)throw std::bad_alloc();checked(avcodec_parameters_to_context(p->decoder,parameters),"fixture decoder parameters");checked(avcodec_open2(p->decoder,decoder,nullptr),"fixture decoder open");
        if(parameters->codec_id==AV_CODEC_ID_AAC){checked(av_bsf_alloc(av_bsf_get_by_name("aac_adtstoasc"),&p->filter),"fixture filter alloc");checked(avcodec_parameters_copy(p->filter->par_in,parameters),"fixture filter parameters");p->filter->time_base_in=raw->streams[i]->time_base;checked(av_bsf_init(p->filter),"fixture filter init");}
        streams.push_back(std::move(p));
    }
    Codec buffers;
    auto decode=[&](Stream& stream){while(true){auto result=avcodec_receive_frame(stream.decoder,buffers.frame);if(result==AVERROR(EAGAIN) || result==AVERROR_EOF)break;checked(result,"fixture decode frame");++stream.frames;stream.samples+=static_cast<uint64_t>(buffers.frame->nb_samples);av_frame_unref(buffers.frame);}};
    auto hash=[&](Stream& stream,AVPacket* packet){av_sha_update(stream.hash,packet->data,static_cast<size_t>(packet->size));++stream.packets;};
    auto* filtered=av_packet_alloc();if(!filtered)throw std::bad_alloc();
    while(av_read_frame(raw,buffers.packet)>=0){
        auto& stream=*streams.at(static_cast<size_t>(buffers.packet->stream_index));checked(avcodec_send_packet(stream.decoder,buffers.packet),"fixture decode packet");decode(stream);
        if(stream.filter){checked(av_bsf_send_packet(stream.filter,buffers.packet),"fixture canonical packet");while(av_bsf_receive_packet(stream.filter,filtered)==0){hash(stream,filtered);av_packet_unref(filtered);}}
        else hash(stream,buffers.packet);
        av_packet_unref(buffers.packet);
    }
    av_packet_free(&filtered);
    for(auto& stream:streams){checked(avcodec_send_packet(stream->decoder,nullptr),"fixture decoder flush");decode(*stream);}
    std::cout<<"{\"duration_us\":"<<raw->duration<<",\"streams\":[";
    for(size_t i=0;i<streams.size();++i){auto& s=*streams[i];uint8_t bytes[32];av_sha_final(s.hash,bytes);std::string hex;for(auto value:bytes){hex+="0123456789abcdef"[value>>4];hex+="0123456789abcdef"[value&15];}
        if(i)std::cout<<",";
        std::cout<<"{\"codec\":\""<<avcodec_get_name(raw->streams[i]->codecpar->codec_id)<<"\",\"packets\":"<<s.packets<<",\"frames\":"<<s.frames<<",\"samples\":"<<s.samples<<",\"packet_sha256\":\""<<hex<<"\"}";
    }
    std::cout<<"]}\n";
}
}
int main(int argc,char** argv){
    try{
        av_log_set_level(AV_LOG_ERROR);
        if(argc==3 && std::string(argv[1])=="generate"){std::filesystem::create_directories(argv[2]);generate(argv[2],true);generate(argv[2],false);std::cout<<"MEDIA_FIXTURES_GENERATED\n";return 0;}
        if(argc==3 && std::string(argv[1])=="inspect"){inspect(argv[2]);return 0;}
        return 2;
    }catch(const std::exception& error){std::cerr<<error.what()<<"\n";return 1;}
}
