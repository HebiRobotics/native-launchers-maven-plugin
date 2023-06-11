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
 linked native-image shared library. Some benefits of this approach are
   (1) we can provide customized debug info and error messages
   (2) removing compile time dependencies allows building the executables before the native library
   (3) executables can be cross-compiled without requiring native libraries for each OS
 */

// =========== OS-SPECIFIC DEFINITIONS ===========
#if _WIN64
#ifndef OS
#define OS "Windows"
#endif
#ifndef LIB_NAME
#define LIB_NAME L"{{IMAGE_NAME}}.dll"
#endif
#elif __APPLE__
#ifndef OS
#define OS "macOS"
#endif
#ifndef LIB_NAME
#define LIB_NAME "{{IMAGE_NAME}}.dylib"
#endif
#elif __linux__
#ifndef OS
#define OS "Linux"
#endif
#ifndef LIB_NAME
#define LIB_NAME "./{{IMAGE_NAME}}.so"
#endif
#endif

// =========== PRINTOUTS ===========
#ifndef DEBUG
#define PRINT_DEBUG(message)
#else
#define PRINT_DEBUG(message) fprintf(stdout, "[DEBUG] %s\n", message)
#endif
#define PRINT_ERROR(message) fprintf(stderr, "[ERROR] %s\n", message)

// =========== DEPENDENCIES FOR DLOPEN API ===========
#ifndef _WIN64
// Linux & macOS (link -ldl on Linux)
#include <stdlib.h>
#include <stdio.h>
#include <dlfcn.h>
#else
// Windows w/ wrappers that make it look the same
#include <windows.h>
#include <stdio.h>
#define RTLD_LAZY 0 // TODO: are there flags we should add?
void* dlopen(const LPCWSTR name, int flags){
    HMODULE module = LoadLibraryW(name);
    return module;
}
void* dlsym(void* module, const LPCSTR name){
    FARPROC addr = GetProcAddress((HMODULE)module, name);
    return addr;
}
char* dlerror(){
    LPTSTR lpBuffer = NULL;
    FormatMessage(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS, NULL,  GetLastError(), 0, (LPTSTR)&lpBuffer, 0, NULL);
    return lpBuffer;
}
#endif

// =========== MAIN CODE ===========
// Typedefs for symbol lookup. The threads are just pointers, so we
// can use void* and get away without including any graalvm headers.
typedef void graal_isolate_t;
typedef void graal_isolatethread_t;
typedef void graal_create_isolate_params_t;
typedef int(*CreateIsolateMethod)(graal_create_isolate_params_t*, graal_isolate_t**, graal_isolatethread_t**);
typedef int(*MainMethod)(graal_isolatethread_t*, int, char**);

// Main entry point
int main(int argc, char** argv){

    // Dynamically bind to library
    PRINT_DEBUG("load library {{IMAGE_NAME}}");
    void* handle = dlopen(LIB_NAME, RTLD_LAZY);
    if(handle == 0){
        PRINT_ERROR(dlerror());
        exit(EXIT_FAILURE);
    }

    PRINT_DEBUG("lookup symbol graal_create_isolate");
    CreateIsolateMethod graal_create_isolate = (CreateIsolateMethod)dlsym(handle, "graal_create_isolate");
    if(graal_create_isolate == 0){
        PRINT_ERROR(dlerror());
        exit(EXIT_FAILURE);
    }

    PRINT_DEBUG("lookup symbol {{METHOD_NAME}}");
    MainMethod run_main = (MainMethod)dlsym(handle, "{{METHOD_NAME}}");
    if(run_main == 0){
        PRINT_ERROR(dlerror());
        exit(EXIT_FAILURE);
    }

    // Actual main method
    PRINT_DEBUG("initializing isolate");
    graal_isolate_t *isolate = 0;
    graal_isolatethread_t *thread = 0;
    if (graal_create_isolate(0, &isolate, &thread) != 0){
        PRINT_ERROR("initialization error");
        exit(EXIT_FAILURE);
    }

    // Call into shared lib
    PRINT_DEBUG("calling {{METHOD_NAME}}");
    return run_main(thread, argc, argv);
}