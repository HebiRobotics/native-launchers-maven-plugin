#include "native-lib.h"

int main(int argc, char** argv) {
    graal_isolate_t *isolate = 0;
    graal_isolatethread_t *thread = 0;
    if (graal_create_isolate(0, &isolate, &thread) != 0) {
        return 1;
    }

    return run_print_directory_contents(thread, argc, argv);
}
