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

#ifndef __LAUNCHER_UTILS_H
#define __LAUNCHER_UTILS_H

/*
 * Utilities for some basic cross-platform things
 */

// =========== PRINTOUTS ===========
#ifndef DEBUG
#define PRINT_DEBUG(...)
#else
#define PRINT_DEBUG(...) fprintf(stdout, "[DEBUG] "), fprintf(stdout, __VA_ARGS__), fprintf(stdout, "\n")
#endif
#define PRINT_ERROR(...) fprintf(stderr, "[ERROR] "), fprintf(stderr, __VA_ARGS__), fprintf(stderr, "\n")

#define STRINGIFY(x) #x
#define TOSTRING(x) STRINGIFY(x)

// =========== DEPENDENCIES FOR DLOPEN API ===========
#if defined(_WIN32) || defined(_WIN64)

    // Windows w/ wrappers that make it look the same as linux & macOS
    #include <windows.h>
    #include <stdio.h>
    #define RTLD_LAZY 0 // TODO: are there flags we should add?

    #ifdef AUMID
    #include <shlobj.h>  // Contains the declaration for SetCurrentProcessExplicitAppUserModelID
    #include <wchar.h>   // For wide characters (L"...")
    #endif

    #ifdef __cplusplus
    extern "C" {
    #endif

    static inline void* dlopen(const LPCWSTR name, int flags){
        HMODULE module = LoadLibraryW(name);
        return module;
    }
    static inline void* dlsym(void* module, const LPCSTR name){
        FARPROC addr = GetProcAddress((HMODULE)module, name);
        return addr;
    }
    static inline char* dlerror(){
        LPTSTR lpBuffer = NULL;
        FormatMessage(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS, NULL,  GetLastError(), 0, (LPTSTR)&lpBuffer, 0, NULL);
        return lpBuffer;
    }

    #ifdef __cplusplus
    }
    #endif

#else
    // Linux & macOS (link -ldl on Linux)
    #include <stdlib.h>
    #include <stdio.h>
    #include <dlfcn.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

static inline void* checkNotNull(void* handle){
    if(handle == 0) {
        PRINT_ERROR("%s", dlerror());
        exit(EXIT_FAILURE);
    }
    return handle;
}

#ifdef __cplusplus
}
#endif

// =========== API for getting the executable path ===========
// Include necessary headers for specific OS functions
#if defined(_WIN32) || defined(_WIN64)
    #include <windows.h>
    #define PATH_MAX MAX_PATH
    #ifndef strdup
    #define strdup _strdup
    #endif
#elif defined(__linux__)
    #include <unistd.h>
    #include <string.h>
    #include <limits.h>
    #ifndef PATH_MAX
        #define PATH_MAX 4096
    #endif
#elif defined(__APPLE__)
    #include <mach-o/dyld.h>
    #include <string.h>
    #include <limits.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Retrieves the absolute path of the current executable.
 * Note: The returned string must be freed by the caller using free().
 * @return A dynamically allocated string containing the absolute path, or NULL on failure.
 */
static inline char* getExecutablePath() {
    char path_buffer[PATH_MAX];
    size_t length = 0;

    #if defined(_WIN32) || defined(_WIN64)
        length = GetModuleFileName(NULL, path_buffer, PATH_MAX);
        if (length == 0 || length >= PATH_MAX) {
            return NULL; // Error or buffer too small
        }
    #elif defined(__linux__)
        length = readlink("/proc/self/exe", path_buffer, PATH_MAX);
        if (length == -1 || length >= PATH_MAX) {
            return NULL; // Error or buffer too small
        }
        path_buffer[length] = '\0'; // readlink does not null-terminate
    #elif defined(__APPLE__)
        uint32_t size = PATH_MAX;
        if (_NSGetExecutablePath(path_buffer, &size) != 0) {
            // Buffer was too small. Would require a realloc-loop
            return NULL;
        }
        // _NSGetExecutablePath might return a path with symlinks/relative parts (../),
        // realpath() is needed for a canonical absolute path.
        char real_path[PATH_MAX];
        if (realpath(path_buffer, real_path) == NULL) {
            return NULL;
        }
        strncpy(path_buffer, real_path, PATH_MAX);
        length = strlen(path_buffer);
    #else
        // Fallback for unsupported platforms (e.g., BSD variants, Solaris)
        fprintf(stderr, "Warning: getExecutablePath not implemented for this OS.\n");
        return NULL;
    #endif

    // Dynamically allocate memory and copy the path
    char* absolute_path = strdup(path_buffer);
    return absolute_path;
}

static inline char* concat(const char* prefix, const char* suffix) {
    const char* p = (prefix != NULL) ? prefix : "";
    const char* s = (suffix != NULL) ? suffix : "";

    size_t p_len = strlen(p);
    size_t s_len = strlen(s);

    // +1 for '\0'
    char* out = (char*)malloc(p_len + s_len + 1);
    if (out == NULL) {
        return NULL;
    }

    memcpy(out, p, p_len);
    memcpy(out + p_len, s, s_len);
    out[p_len + s_len] = '\0';
    return out; // caller owns and must free()
}

#ifdef __cplusplus
}
#endif

#endif
