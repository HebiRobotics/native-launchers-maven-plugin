/*-
 * #%L
 * Native Launchers Plugin
 * %%
 * Copyright (C) 2023 - 2025 HEBI Robotics
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

#ifndef __GRAAL_JNI_H
#define __GRAAL_JNI_H

/*
 * This header defines the structures and functions required to
 * initialize a GraalVM isolate using the JNI Invocation API,
 * allowing custom arguments (-Xmx, -XX:+PrintGC, etc.).
 *
 * It provides the necessary types that allow JNIEnv* to be used
 * as a graal_isolatethread_t* (as they are pointers to the same
 * underlying structure within the GraalVM implementation).
 *
 * See https://github.com/oracle/graal/issues/12418#issuecomment-3601911781 for more details
 *
 * Generally, users are supposed to use the JNI methods rather than
 * the low-level SVM API.
 * See https://github.com/graalvm/graalvm-website/issues/12#issuecomment-1013411370
 */

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Define basic JNI primitive types required for arguments struct and function signature
typedef int32_t jint;
typedef int8_t  jboolean;
#define JNI_FALSE 0
#define JNI_TRUE  1
#define JNI_OK    0
#define JNI_ERR  (-1)

#if defined(_WIN32) || defined(_WIN64)
#define JNICALL __stdcall
#else
#define JNICALL
#endif

// Use JNI 1.8 version, which is standard for current GraalVM releases
#define JNI_VERSION_1_8 0x00010008

// Core structs for isolate and thread
typedef struct JavaVM_ JavaVM; // same as graal_isolate_t
typedef struct JNIEnv_ JNIEnv; // same as graal_isolatethread_t

/*
 * optionString may be any option accepted by the JVM, or one of the
 * following:
 *
 * -D<name>=<value>          Set a system property.
 * -verbose[:class|gc|jni]   Enable verbose output, comma-separated. E.g.
 *                           "-verbose:class" or "-verbose:gc,class"
 *                           Standard names include: gc, class, and jni.
 *                           All nonstandard (VM-specific) names must begin
 *                           with "X".
 * vfprintf                  extraInfo is a pointer to the vfprintf hook.
 * exit                      extraInfo is a pointer to the exit hook.
 * abort                     extraInfo is a pointer to the abort hook.
 */
typedef struct JavaVMOption {
    char *optionString;
    void *extraInfo;
} JavaVMOption;

typedef struct JavaVMInitArgs {
    jint version;
    jint nOptions;
    JavaVMOption *options;
    jboolean ignoreUnrecognized;
} JavaVMInitArgs;

// Function signature w/ mapped GraalVM types
typedef jint (JNICALL *CreateJavaVM_Func)(
    JavaVM **pvm,
    JNIEnv **penv,         // type-safe alias for void**
    JavaVMInitArgs *args    // type-safe alias for void*
);

#ifdef __cplusplus
}
#endif

#endif
