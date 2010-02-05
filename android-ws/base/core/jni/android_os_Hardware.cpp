/*
 * Copyright 2006, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

#include <hardware_legacy/flashlight.h>
#include <hardware_legacy/power.h>

#include <nativehelper/jni.h>
#include <android_runtime/AndroidRuntime.h>
#include <nativehelper/JNIHelp.h>

namespace android {

static jint
getFlashlightEnabled(JNIEnv *env, jobject clazz)
{
    return get_flashlight_enabled();
}

static void
setFlashlightEnabled(JNIEnv *env, jobject clazz, jboolean on)
{
    set_flashlight_enabled(on);
}

static void
enableCameraFlash(JNIEnv *env, jobject clazz, jint milliseconds)
{
    enable_camera_flash(milliseconds);
}

// ============================================================================
/*
 * JNI registration.
 */

static JNINativeMethod g_methods[] = {
    /* name, signature, funcPtr */
    { "getFlashlightEnabled", "()Z", (void*)getFlashlightEnabled },
    { "setFlashlightEnabled", "(Z)V", (void*)setFlashlightEnabled },
    { "enableCameraFlash", "(I)V", (void*)enableCameraFlash },
};

int register_android_os_Hardware(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(env,
            "android/os/Hardware", g_methods, NELEM(g_methods));
}

}; // namespace android
