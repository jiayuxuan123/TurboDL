#pragma once
#include <condition_variable>
#include <deque>
#include <functional>
#include <future>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <type_traits>

namespace td {
// One disk worker per engine; connections never own disk or hashing threads.
// Payload bytes stay in the request's admitted buffer until its job completes.
class IoQueue {
public:
    explicit IoQueue(std::function<void()> notify={}):notify_(std::move(notify)),worker_([this]{run();}){}
    ~IoQueue(){close();}
    void close(){ {std::lock_guard lock(mutex_);closing_=true;}ready_.notify_one();if(worker_.joinable())worker_.join();}
    template<class F>auto submit(F&& f)->std::future<std::invoke_result_t<F>> {
        using Result=std::invoke_result_t<F>;
        auto task=std::make_shared<std::packaged_task<Result()>>(std::forward<F>(f));
        auto future=task->get_future();
        {std::lock_guard lock(mutex_);if(closing_ || jobs_.size()>=8192)throw std::runtime_error("Disk queue capacity exceeded");jobs_.emplace_back([this,task]{(*task)();if(notify_)notify_();});}
        ready_.notify_one();return future;
    }
private:
    void run(){
        while(true){std::function<void()> job;{std::unique_lock lock(mutex_);ready_.wait(lock,[this]{return closing_ || !jobs_.empty();});if(jobs_.empty() && closing_)return;job=std::move(jobs_.front());jobs_.pop_front();}job();}
    }
    std::mutex mutex_;
    std::condition_variable ready_;
    std::deque<std::function<void()>> jobs_;
    bool closing_=false;
    std::function<void()> notify_;
    std::thread worker_;
};
}
