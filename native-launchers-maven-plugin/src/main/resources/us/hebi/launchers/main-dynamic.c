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
   (1) we can provide better error messages about reasons for failure
       rather than silently exiting the app.
   (2) removing compile time dependencies allows us to build the wrappers
       at any point, i.e., before the native library is built
   (3) we can cross-compile for all operating systems (e.g. zig cc) without
       requiring the native images for each OS.
 */

 // Typedefs for symbol lookup. The threads are just pointers, so we
 // can use void* and get away without including any graalvm headers.
 typedef void graal_isolate_t;
 typedef void graal_isolatethread_t;
 typedef void graal_create_isolate_params_t;
 typedef int(*CreateIsolateMethod)(graal_create_isolate_params_t*, graal_isolate_t**, graal_isolatethread_t**);
 typedef int(*MainMethod)(graal_isolatethread_t*, int, char**);

#ifdef _WIN64

// Windows
#include <windows.h>
#include <stdio.h>

void printError(DWORD err_code) {
 LPTSTR lpBuffer = NULL;
 FormatMessage(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS, NULL,  err_code, 0, (LPTSTR)&lpBuffer, 0, NULL);
 fprintf( stderr, lpBuffer );
 LocalFree(lpBuffer);
}

HMODULE loadLibrary(LPCWSTR name) {
    HMODULE module = LoadLibraryW(name);
    DWORD err_code = GetLastError();
    if(err_code != 0) {
        fprintf( stderr, "[ERROR] unable to load library: '%ls': ", name );
        printError(err_code);
        exit(1);
    }
    #ifdef DEBUG
     fprintf( stdout, "[DEBUG] loaded library: %ls\n", name );
    #endif
    return module;
}

FARPROC getMethodAddress(HMODULE module, LPCSTR name) {
   FARPROC addr = GetProcAddress(module, name);
   if(addr == 0) {
        fprintf( stderr, "[ERROR] unable to find '%s': ", name );
        printError(GetLastError());
        exit(1);
   }
   #ifdef DEBUG
    fprintf( stdout, "[DEBUG] found method: %s\n", name );
   #endif
   return addr;
}

#else
// Linux
#include <stdlib.h>
#include <stdio.h>
#include <dlfcn.h>
void* checkRef(void* ref){
    if(!ref) {
        fprintf(stderr, "[ERROR] %s\n", dlerror());
        exit(EXIT_FAILURE);
    }
    return ref;
}
#endif


// zig cc -o hello.exe cli-hello.c -DDEBUG -target x86_64-windows
int main(int argc, char** argv) {

    #ifdef _WIN64
     HMODULE module = loadLibrary(L"{{IMAGE_NAME}}.dll");
     CreateIsolateMethod graal_create_isolate = (CreateIsolateMethod)getMethodAddress(module, "graal_create_isolate");
     MainMethod run_main = (MainMethod)getMethodAddress(module, "{{METHOD_NAME}}");
    #else
     void *handle = checkRef(dlopen ("./{{IMAGE_NAME}}.so", RTLD_LAZY)); // gcc -o test cli-hello.c -ldl
     CreateIsolateMethod graal_create_isolate = checkRef(dlsym(handle, "graal_create_isolate"));
     MainMethod run_main = checkRef(dlsym(handle, "{{METHOD_NAME}}"));
    #endif

   #ifdef DEBUG
    fprintf( stdout, "[DEBUG] initializing isolate\n");
   #endif

    // Initialize isolate
    graal_isolate_t *isolate = 0;
    graal_isolatethread_t *thread = 0;
    if (graal_create_isolate(0, &isolate, &thread) != 0) {
        fprintf( stderr, "initialization error\n" );
        return 1;
    }

    // Call into shared lib
   #ifdef DEBUG
    fprintf( stdout, "[DEBUG] calling {{METHOD_NAME}}\n");
   #endif
    return run_main(thread, argc, argv);
}