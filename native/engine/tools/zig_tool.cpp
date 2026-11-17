#include <process.h>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

// Adapter for upstream build systems that require one compiler executable.
// No shell interpolation: all arguments are forwarded as separate arguments.
int main(int argc,char** argv){
    const char* compiler=std::getenv("TD_ZIG_EXE");
    if(!compiler || !*compiler){std::cerr<<"TD_ZIG_EXE is required\n";return 2;}
    std::string executable=argv[0];bool ranlib=executable.find("ranlib")!=std::string::npos;
    bool archive=!ranlib && executable.find("zig-ar")!=std::string::npos;
    std::vector<const char*> arguments{compiler,ranlib?"ranlib":archive?"ar":"cc"};
    std::vector<std::string> normalized;normalized.reserve(static_cast<size_t>(argc));
    if(!archive && !ranlib){arguments.push_back("-target");arguments.push_back("x86_64-windows-gnu");}
    for(int i=1;i<argc;++i){
        // FFmpeg's GNU PE configure probe adds an obsolete GNU ld flag. LLD
        // emits a normal PE executable without it; retain the requested entry.
        if(std::strcmp(argv[i],"-Wl,--pic-executable,-e,mainCRTStartup")==0){
            normalized.emplace_back("-Wl,-e,mainCRTStartup");arguments.push_back(normalized.back().c_str());
        }else arguments.push_back(argv[i]);
    }
    arguments.push_back(nullptr);
    auto result=_spawnv(_P_WAIT,compiler,arguments.data());return result<0?127:static_cast<int>(result);
}
