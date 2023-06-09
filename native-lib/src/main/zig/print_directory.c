#include "native-lib.h"

int main(int argc, char** argv) {
    graal_isolate_t *isolate = 0;
    graal_isolatethread_t *thread = 0;
    if (graal_create_isolate(0, &isolate, &thread) != 0) {
        return 1;
    }

    return run_us_hebi_demos_zig_PrintDirectory_main(thread, argc, argv);
}
