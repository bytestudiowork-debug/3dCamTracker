#pragma once
#include <jni.h>
#include <ceres/ceres.h>
namespace trackstudio {
class JniProgressCallback final : public ceres::IterationCallback {
public:
    JniProgressCallback(JNIEnv* env, jobject listener, jint total_iterations)
            : env_(env), listener_(listener), total_iterations_(total_iterations) {
        if (!env_ || !listener_) return;
        jclass listener_class = env_->GetObjectClass(listener_);
        if (!listener_class) return;
        method_ = env_->GetMethodID(listener_class, "onIteration", "(II)V");
        env_->DeleteLocalRef(listener_class);
    }
    bool valid() const { return method_ != nullptr; }
    ceres::CallbackReturnType operator()(const ceres::IterationSummary& summary) override {
        if (valid()) {
            env_->CallVoidMethod(listener_, method_, static_cast<jint>(summary.iteration + 1), total_iterations_);
            if (env_->ExceptionCheck()) env_->ExceptionClear();
        }
        return ceres::SOLVER_CONTINUE;
    }
private:
    JNIEnv* env_;
    jobject listener_;
    jint total_iterations_;
    jmethodID method_ = nullptr;
};
}  // namespace trackstudio
