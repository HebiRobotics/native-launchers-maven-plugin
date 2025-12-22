/*-
 * #%L
 * native-launchers-maven-plugin Maven Mojo
 * %%
 * Copyright (C) 2023 HEBI Robotics
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

 /*
 Template for a tiny c launcher file that dynamically calls a main entry point inside
 a native-image shared library. Some benefits of doing dynamic linking are
   (1) customized debug and error messages
   (2) compilation at any phase without requiring the native library
   (3) potentially simpler cross-compilation
       * note: zig cc v0.10.1 does not support dynamic loading w/ cross-compilation
 */

// =========== OS-SPECIFIC DEFINITIONS ===========
#if defined(_WIN32) || defined(_WIN64)
#ifndef OS_FAMILY
#define OS_FAMILY "Windows"
#endif
#ifndef LIB_FILE
#define LIB_FILE L"{{IMAGE_NAME}}.dll"
#endif
#elif defined(__APPLE__)
#ifndef OS_FAMILY
#define OS_FAMILY "macOS"
#endif
#ifndef LIB_FILE
#define LIB_FILE "{{IMAGE_NAME}}.dylib"
#endif
#elif defined(__linux__)
#ifndef OS_FAMILY
#define OS_FAMILY "Linux"
#endif
#ifndef LIB_FILE
#define LIB_FILE "{{IMAGE_NAME}}.so"
#endif
#endif

// =========== MAIN CODE ===========
// Disable statically linked methods
#define _JNI_IMPLEMENTATION_
#include "jni.h"

// Function signature for dynamic lookup
typedef jint (JNICALL *CreateJavaVM_Func)(
    JavaVM **pvm,
    JNIEnv **penv,         // type-safe alias for void**
    JavaVMInitArgs *args    // type-safe alias for void*
);


#include "launcher_utils.h"

// Main entry point
int main_entry_point(int argc, char** argv) {
    PRINT_DEBUG("Running on "OS_FAMILY);

    // Determine the executable path on the native side since
    // it's more reliable than trying to do it in Java.
    PRINT_DEBUG("Determining executable path property");
    char* exePath = getExecutablePath();
    if (exePath == NULL) {
        PRINT_ERROR("Could not determine executable path.");
    }
    char* launcherPath = concat("-Dlauncher.executablePath=", exePath);
    free(exePath);

    // Prepare jvm options
    int nOptions = 0;
    JavaVMOption options[10 + {{NUM_JVM_ARGS}}];

    // General options for a good out of the box experience
    options[nOptions++].optionString = "-Dpicocli.ansi=tty";
    options[nOptions++].optionString = "-Dfile.encoding=UTF-8";
    options[nOptions++].optionString = "-Dnative.encoding=UTF-8";
    options[nOptions++].optionString = "-Dsun.jnu.encoding=UTF-8";

    #if defined(_WIN32) || defined(_WIN64)

        // Set the Console Code Pages to UTF-8 (65001)
        DWORD consoleMode = 0;
        HANDLE stdOut = GetStdHandle(STD_OUTPUT_HANDLE);

        if (GetConsoleMode(stdOut, &consoleMode)) {
            // We have a real console, set UTF-8
            SetConsoleOutputCP(65001);
            SetConsoleCP(65001);
            PRINT_DEBUG("Set console output to UTF-8 (check: Æøåæøå)");

            // Other piped inputs are determined by the sender
            options[nOptions++].optionString = "-Dstdin.encoding=UTF-8";
        }

        // Make Java aware that streams are UTF-8. Note that this does not
        // handle piped file outputs in classic powershell, but there does not
        // seem to be a way to fix that from the application side.
        options[nOptions++].optionString = "-Dstdout.encoding=UTF-8";
        options[nOptions++].optionString = "-Dstderr.encoding=UTF-8";

        #ifdef AUMID
        // Define the AUMID as a wide character string literal (required by Windows API)
        // This makes the taskbar icons be consistent, e.g., launching the app does not show two icons
        const wchar_t* app_aumid = L"" TOSTRING(AUMID);

        HRESULT hr = SetCurrentProcessExplicitAppUserModelID(app_aumid);
        if (SUCCEEDED(hr)) {
            PRINT_DEBUG("Set Application User Model Id: " TOSTRING(AUMID));

             // Set property similar to Conveyor's 'app.windows.userModelID'
            // https://conveyor.hydraulic.dev/21.0/configs/os-integration/#windows-appusermodelid-aumid
            options[nOptions++].optionString = "-Dlauncher.windows.userModelID=" TOSTRING(AUMID);
        } else {
            PRINT_ERROR("Failed to set Application User Model Id)");
        }
        #endif

    #endif

    // Metadata and user jvm args
    options[nOptions++].optionString = launcherPath;{{JVM_ARGS}}

    PRINT_DEBUG("Adding vm options:");
    for (int i=0; i < nOptions; i++) {
        PRINT_DEBUG("%s", options[i].optionString);
    }

    // Init struct
    JavaVMInitArgs vm_args;
    vm_args.version = JNI_VERSION_1_8;
    vm_args.nOptions = nOptions;
    vm_args.options = options;
    vm_args.ignoreUnrecognized = JNI_FALSE;

    // Dynamically bind to library
    PRINT_DEBUG("Loading library " TOSTRING(LIB_FILE));
    void* handle = dlopen(LIB_FILE, RTLD_LAZY);
    checkNotNull(handle);

    PRINT_DEBUG("Looking up symbol: JNI_CreateJavaVM");
    CreateJavaVM_Func JNI_CreateJavaVM = (CreateJavaVM_Func)dlsym(handle, "JNI_CreateJavaVM");
    checkNotNull(JNI_CreateJavaVM);

    // Call JNI_CreateJavaVM
    JavaVM *vm = 0; // same as isolate
    JNIEnv *env = 0; // same as thread
    if (JNI_CreateJavaVM(&vm, &env, &vm_args) != JNI_OK) {
        PRINT_ERROR("Failed to create JavaVM (GraalVM isolate)");
        return 1;
    }
    free(launcherPath);

    // Convert C args to Java String[]
    jobjectArray javaArgs = NULL;
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (stringClass == NULL) {
        PRINT_ERROR("Failed to find java/lang/String class");
        return 1;
    }

    // Java omits the program name (argv[0])
    int arrayLength = (argc > 0) ? argc - 1 : 0;
    javaArgs = (*env)->NewObjectArray(env, arrayLength, stringClass, NULL);
    if (javaArgs == NULL) {
        PRINT_ERROR("Failed to create String array");
        (*env)->DeleteLocalRef(env, stringClass);
        return 1;
    }

    PRINT_DEBUG("Creating Java args[%d]",arrayLength);
    for (int i = 0; i < arrayLength; i++) {
        PRINT_DEBUG("  args[%d]=%s",i, argv[i+1]);
        jstring str = (*env)->NewStringUTF(env, argv[i + 1]);
        if (str == NULL) {
            PRINT_ERROR("Failed to create string for argument %d", i);
            (*env)->DeleteLocalRef(env, javaArgs);
            (*env)->DeleteLocalRef(env, stringClass);
            return 1;
        }
        (*env)->SetObjectArrayElement(env, javaArgs, i, str);
        (*env)->DeleteLocalRef(env, str);
    }
    (*env)->DeleteLocalRef(env, stringClass);

    // Load main class via reflection
    char internalClassName[512];
    snprintf(internalClassName, sizeof(internalClassName), "%s", "{{MAIN_CLASS}}");
    for (int i = 0; internalClassName[i]; i++) {
        if (internalClassName[i] == '.') {
            internalClassName[i] = '/';
        }
    }

    PRINT_DEBUG("Loading class: %s", internalClassName);
    jclass mainClass = (*env)->FindClass(env, internalClassName);
    if (mainClass == NULL) {
        PRINT_ERROR("Failed to find main class: %s", internalClassName);
        (*env)->DeleteLocalRef(env, javaArgs);
        return 1;
    }

    // Find main method: public static void main(String[])
    PRINT_DEBUG("Looking up method: main([Ljava/lang/String;)V");
    jmethodID mainMethod = (*env)->GetStaticMethodID(env, mainClass, "main", "([Ljava/lang/String;)V");
    if (mainMethod == NULL) {
        PRINT_ERROR("Failed to find main method in {{MAIN_CLASS}}. Ensure the signature matches public static void main(String[])");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        (*env)->DeleteLocalRef(env, javaArgs);
        (*env)->DeleteLocalRef(env, mainClass);
        return 1;
    }

    // Call the main method
    PRINT_DEBUG("Invoking main method for {{MAIN_CLASS}}");
    (*env)->CallStaticVoidMethod(env, mainClass, mainMethod, javaArgs);

    // Check for exceptions
    jint exitCode = 0;
    if ((*env)->ExceptionCheck(env)) {
        PRINT_ERROR("Exception occurred during main method execution");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        exitCode = 1;
    }

    // Cleanup
    (*env)->DeleteLocalRef(env, javaArgs);
    (*env)->DeleteLocalRef(env, mainClass);

    return exitCode;

}

// Logic to handle macOS specifics where the Cocoa/UI loop needs to take over the
// main thread and the actual main method needs to be launched in the background
#if defined(__APPLE__) && defined(COCOA)
typedef int (*main_callback_t)(int argc, char **argv);
extern void launchCocoaApp(int argc, char** argv, main_callback_t callback);
int main(int argc, char** argv) {
    PRINT_DEBUG("Launching Cocoa framework");
    launchCocoaApp(argc, argv, main_entry_point);
}
#else
int main(int argc, char** argv) {
    return main_entry_point(argc, argv);
}
#endif