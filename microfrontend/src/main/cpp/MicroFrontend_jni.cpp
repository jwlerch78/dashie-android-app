// SPDX-License-Identifier: Apache-2.0
// Based on Home Assistant Android app microfrontend module
// JNI package: com.dashieapp.Dashie.microfrontend.MicroFrontend

#include <jni.h>
#include <android/log.h>
#include "MicroFrontendWrapper.h"

#define LOG_TAG "MicroFrontend_JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Cached Java references for ArrayList creation
static jclass gArrayListClass = nullptr;
static jmethodID gArrayListConstructor = nullptr;
static jmethodID gArrayListAdd = nullptr;

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // Cache ArrayList class and methods
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    if (arrayListClass == nullptr) {
        LOGE("Failed to find ArrayList class");
        return JNI_ERR;
    }
    gArrayListClass = reinterpret_cast<jclass>(env->NewGlobalRef(arrayListClass));
    env->DeleteLocalRef(arrayListClass);

    gArrayListConstructor = env->GetMethodID(gArrayListClass, "<init>", "(I)V");
    gArrayListAdd = env->GetMethodID(gArrayListClass, "add", "(Ljava/lang/Object;)Z");

    if (gArrayListConstructor == nullptr || gArrayListAdd == nullptr) {
        LOGE("Failed to find ArrayList methods");
        return JNI_ERR;
    }

    LOGD("JNI_OnLoad: cached ArrayList references");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (gArrayListClass != nullptr) {
            env->DeleteGlobalRef(gArrayListClass);
            gArrayListClass = nullptr;
        }
    }
}

JNIEXPORT jlong JNICALL
Java_com_dashieapp_Dashie_microfrontend_MicroFrontend_nativeCreate(
    JNIEnv* env, jclass clazz, jint sampleRate, jint stepSizeMs) {

    auto* wrapper = new MicroFrontendWrapper(sampleRate, static_cast<size_t>(stepSizeMs));
    if (!wrapper->isInitialized()) {
        LOGE("Failed to create MicroFrontendWrapper");
        delete wrapper;
        return 0;
    }
    return reinterpret_cast<jlong>(wrapper);
}

JNIEXPORT void JNICALL
Java_com_dashieapp_Dashie_microfrontend_MicroFrontend_nativeDestroy(
    JNIEnv* env, jclass clazz, jlong handle) {

    auto* wrapper = reinterpret_cast<MicroFrontendWrapper*>(handle);
    delete wrapper;
}

JNIEXPORT jobject JNICALL
Java_com_dashieapp_Dashie_microfrontend_MicroFrontend_nativeProcessSamples(
    JNIEnv* env, jclass clazz, jlong handle, jshortArray samples) {

    auto* wrapper = reinterpret_cast<MicroFrontendWrapper*>(handle);

    jsize numSamples = env->GetArrayLength(samples);
    jshort* sampleData = env->GetShortArrayElements(samples, nullptr);

    auto results = wrapper->processSamples(sampleData, static_cast<size_t>(numSamples));

    // Release input array (JNI_ABORT = we didn't modify the array)
    env->ReleaseShortArrayElements(samples, sampleData, JNI_ABORT);

    // Create ArrayList<FloatArray> using cached class/methods
    jobject arrayList = env->NewObject(gArrayListClass, gArrayListConstructor, (jint)results.size());

    for (const auto& frame : results) {
        jfloatArray jFrame = env->NewFloatArray(static_cast<jsize>(frame.size()));
        env->SetFloatArrayRegion(jFrame, 0, static_cast<jsize>(frame.size()), frame.data());
        env->CallBooleanMethod(arrayList, gArrayListAdd, jFrame);
        env->DeleteLocalRef(jFrame);

        if (env->ExceptionCheck()) {
            LOGE("Exception during ArrayList.add");
            return nullptr;
        }
    }

    return arrayList;
}

JNIEXPORT void JNICALL
Java_com_dashieapp_Dashie_microfrontend_MicroFrontend_nativeReset(
    JNIEnv* env, jclass clazz, jlong handle) {

    auto* wrapper = reinterpret_cast<MicroFrontendWrapper*>(handle);
    wrapper->reset();
}

} // extern "C"
