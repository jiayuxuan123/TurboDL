#include <windows.h>
#include <bcrypt.h>
#include <openssl/evp.h>
extern "C" {
#include <libavutil/sha.h>
#include <libavutil/mem.h>
}
#include <array>
#include <algorithm>
#include <chrono>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

using Hash=std::array<unsigned char,32>;
static std::string hex(const Hash& value){std::string out;for(auto byte:value){out+="0123456789abcdef"[byte>>4];out+="0123456789abcdef"[byte&15];}return out;}
int main(){
    constexpr size_t size=32*1024*1024;
    std::vector<unsigned char> bytes(size);uint64_t seed=0x9e3779b97f4a7c15ULL;
    for(auto& byte:bytes){seed^=seed<<13;seed^=seed>>7;seed^=seed<<17;byte=static_cast<unsigned char>(seed);}
    BCRYPT_ALG_HANDLE provider=nullptr;
    if(BCryptOpenAlgorithmProvider(&provider,BCRYPT_SHA256_ALGORITHM,nullptr,0)<0)return 2;
    auto digest=[&](int backend,const unsigned char* input,size_t count){
        Hash output{};
        if(backend==0){
            BCRYPT_HASH_HANDLE hash=nullptr;
            if(BCryptCreateHash(provider,&hash,nullptr,0,nullptr,0,0)<0)throw std::runtime_error("CNG init");
            for(size_t n=0;n<count;n+=65536)if(BCryptHashData(hash,const_cast<PUCHAR>(input+n),static_cast<ULONG>(std::min<size_t>(65536,count-n)),0)<0)throw std::runtime_error("CNG update");
            if(BCryptFinishHash(hash,output.data(),32,0)<0)throw std::runtime_error("CNG final");
            BCryptDestroyHash(hash);
        }else if(backend==1){
            auto* hash=EVP_MD_CTX_new();if(!hash || EVP_DigestInit_ex(hash,EVP_sha256(),nullptr)!=1)throw std::runtime_error("EVP init");
            for(size_t n=0;n<count;n+=65536)if(EVP_DigestUpdate(hash,input+n,std::min<size_t>(65536,count-n))!=1)throw std::runtime_error("EVP update");
            unsigned int length=0;if(EVP_DigestFinal_ex(hash,output.data(),&length)!=1 || length!=32)throw std::runtime_error("EVP final");
            EVP_MD_CTX_free(hash);
        }else{
            auto* hash=av_sha_alloc();if(!hash || av_sha_init(hash,256)!=0)throw std::runtime_error("AVSHA init");
            for(size_t n=0;n<count;n+=65536)av_sha_update(hash,input+n,std::min<size_t>(65536,count-n));
            av_sha_final(hash,output.data());av_free(hash);
        }
        return output;
    };
    try{
        auto expected=digest(0,bytes.data(),bytes.size());
        auto expected_block=digest(0,bytes.data()+size-524288,524288);
        const char* names[]={"windows_cng","pinned_libressl_evp","pinned_ffmpeg_avsha"};
        for(int pair=0;pair<5;++pair)for(int offset=0;offset<3;++offset){
            int backend=(pair+offset)%3;
            for(bool blocks:{false,true}){
                auto started=std::chrono::steady_clock::now();Hash actual{};
                if(blocks){for(size_t n=0;n<size;n+=524288)actual=digest(backend,bytes.data()+n,524288);}
                else actual=digest(backend,bytes.data(),size);
                auto elapsed=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-started).count();
                if(actual!=(blocks?expected_block:expected))throw std::runtime_error("SHA-256 disagreement");
                std::cout<<"{\"backend\":\""<<names[backend]<<"\",\"pair\":"<<pair<<",\"blocks\":"<<(blocks?"true":"false")<<",\"bytes\":"<<size<<",\"ms\":"<<elapsed<<",\"sha256\":\""<<hex(actual)<<"\"}\n";
            }
        }
    }catch(const std::exception& error){std::cerr<<error.what()<<'\n';BCryptCloseAlgorithmProvider(provider,0);return 3;}
    BCryptCloseAlgorithmProvider(provider,0);return 0;
}
