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
 Template for a wrapper c file that calls a main entry point in a dynamically
 linked native-image shared library. Some benefits of doing dynamic linking are
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
#include "graal_jni_dynamic.h"
#include "launcher_utils.h"
typedef int(*MainFunction)(JNIEnv*, int, char**);

void* checkNotNull(void* handle){
    if(handle == 0) {
        PRINT_ERROR(dlerror());
        exit(EXIT_FAILURE);
    }
    return handle;
}

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
    char* pathProperty = concat("-Dlauncher.path=", exePath);
    free(exePath);
    PRINT_DEBUG(pathProperty);

    // Dynamically bind to library
    PRINT_DEBUG("load library {{IMAGE_NAME}}");
    void* handle = dlopen(LIB_FILE, RTLD_LAZY);
    checkNotNull(handle);

    PRINT_DEBUG("lookup symbol JNI_CreateJavaVM");
    CreateJavaVM_Func JNI_CreateJavaVM = (CreateJavaVM_Func)dlsym(handle, "JNI_CreateJavaVM");
    checkNotNull(JNI_CreateJavaVM);

    PRINT_DEBUG("lookup symbol {{METHOD_NAME}}");
    MainFunction runMain = (MainFunction)dlsym(handle, "{{METHOD_NAME}}");
    checkNotNull(runMain);

    // Actual main method
    JavaVM *isolate = 0;
    JNIEnv *thread = 0;

    PRINT_DEBUG("setting up vm options");
    int nOptions = 0;
    JavaVMOption options[10]; // TODO: max built-in options + user option count

    // Metadata about the launcher
    options[nOptions++].optionString = pathProperty;
    options[nOptions++].optionString = "-Dlauncher.entrypoint={{METHOD_NAME}}";

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

    #endif

     JavaVMInitArgs vm_args;
     vm_args.version = JNI_VERSION_1_8;
     vm_args.nOptions = nOptions;
     vm_args.options = options;
     vm_args.ignoreUnrecognized = JNI_FALSE;

    // Call JNI_CreateJavaVM, casting the last argument to void*
    if (JNI_CreateJavaVM(&isolate, &thread, &vm_args) != JNI_OK) {
        PRINT_ERROR("Failed to create JavaVM (GraalVM isolate)");
        return 1;
    }
    free(pathProperty);

    // Call into shared lib
    PRINT_DEBUG("calling {{METHOD_NAME}}");
    return runMain(thread, argc, argv);
}

// Logic to handle macOS specifics where the Cocoa/UI loop needs to take over the
// main thread and the actual main method needs to be launched in the background
#if defined(__APPLE__) && !defined(CONSOLE)
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