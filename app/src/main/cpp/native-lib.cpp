#include <jni.h>
#include <node.h>
#include <cstdlib>
#include <string>
#include <vector>
#include <cstring>

extern "C"
JNIEXPORT jint JNICALL
Java_dev_marklv_android_EmbeddedFreeLLMRuntime_startNodeWithArguments(
    JNIEnv* env,
    jobject /* receiver */,
    jobjectArray arguments,
    jobjectArray environment) {

    const jsize envCount = env->GetArrayLength(environment);
    for (jsize i = 0; i < envCount; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(environment, i));
        if (item == nullptr) continue;
        const char* raw = env->GetStringUTFChars(item, nullptr);
        if (raw != nullptr) {
            std::string entry(raw);
            const auto separator = entry.find('=');
            if (separator != std::string::npos && separator > 0) {
                const std::string name = entry.substr(0, separator);
                const std::string value = entry.substr(separator + 1);
                setenv(name.c_str(), value.c_str(), 1);
            }
            env->ReleaseStringUTFChars(item, raw);
        }
        env->DeleteLocalRef(item);
    }

    const jsize count = env->GetArrayLength(arguments);
    std::vector<std::string> storage;
    storage.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        const char* raw = item ? env->GetStringUTFChars(item, nullptr) : nullptr;
        storage.emplace_back(raw ? raw : "");
        if (raw) env->ReleaseStringUTFChars(item, raw);
        if (item) env->DeleteLocalRef(item);
    }

    std::vector<char*> argv;
    argv.reserve(storage.size());
    for (auto& arg : storage) argv.push_back(arg.data());

    return static_cast<jint>(node::Start(static_cast<int>(argv.size()), argv.data()));
}
