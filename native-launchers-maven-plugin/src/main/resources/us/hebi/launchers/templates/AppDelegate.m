/*-
 * #%L
 * native-launchers-maven-plugin Maven Mojo
 * %%
 * Copyright (C) 2025 HEBI Robotics
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
 Cocoa delegate that starts the Cocoa event loop on the main thread and
 calls the actual main logic on a background thread. This is necessary
 to display GUIs on macOS.
 */

#import <Cocoa/Cocoa.h>
#import <stdatomic.h>

#ifdef DEBUG
#define LOG_DEBUG(message, ...) NSLog(message, ##__VA_ARGS__);
#else
#define LOG_DEBUG(message, ...)
#endif

typedef int (*main_callback_t)(int argc, char **argv);

@interface AppDelegate : NSObject <NSApplicationDelegate>
@property (nonatomic, assign) int argc;
@property (nonatomic, assign) char **argv;
@property (nonatomic, assign) main_callback_t callback;
@property (atomic, assign) atomic_int activeTasks;
@property (atomic, assign) BOOL receivedFileHandle;
@end

@implementation AppDelegate

#ifdef ENABLE_COCOA_FILE_HANDLER
// This captures files opened via Finder / Apple Events. In order to emulate similar
// behavior to Windows & Linux, we start a separate main function for each file with
// the file as a parameter.
- (void)application:(NSApplication *)sender openFiles:(NSArray<NSString *> *)filenames {
    self.receivedFileHandle = YES;
    for (NSString *filename in filenames) {
        LOG_DEBUG([NSString stringWithFormat:@"Opening file: %@", filename]);

        // Launch the main method with 2 parameters (first is executable file)
        char *exec = strdup(self.argv[0]);
        char *path = strdup([filename UTF8String]);

        dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
            char *fileArgv[] = { exec, path, NULL };
            [self startTask];
            self.callback(2, fileArgv);
            [self finishTask];
            free(exec);
            free(path);
        });
    }

    // Reply to the OS that the event was handled
    [sender replyToOpenOrPrint:NSApplicationDelegateReplySuccess];
}
#endif

- (void)startTask {
    atomic_fetch_add(&_activeTasks, 1);
}

- (void)finishTask {
    int newCount = atomic_fetch_sub(&_activeTasks, 1) - 1;
    LOG_DEBUG([NSString stringWithFormat:@"Task finished. Active tasks: %d", newCount]);

    if (newCount <= 0) {
        LOG_DEBUG(@"All tasks complete. Terminating Cocoa.");
        dispatch_async(dispatch_get_main_queue(), ^{
            [NSApp terminate:nil];
        });
    }
}

- (void)applicationDidFinishLaunching:(NSNotification *)aNotification  {
    // When users click on a file when the app is not running, the app
    // gets launched with a file event. Users wouldn't he expect the
    // app to be launched twice, so we ignore the main launch.
    if (self.receivedFileHandle && self.argc <= 1) {
        LOG_DEBUG(@"Skipping empty launch as file event was handled");
        return;
    }

    LOG_DEBUG(@"Cocoa finished launching")

    // Start the actual main in a background thread as the main thread is busy
    // running the Cocoa event loop
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        LOG_DEBUG(@"Handing over application logic to background thread");
        [self startTask];
        self.callback(self.argc, self.argv);
        [self finishTask];
    });

}

- (BOOL) applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)app {
    return NO; // it gets closed when the callback is done
}

- (BOOL) applicationSupportsSecureRestorableState:(NSApplication *)app {
    return YES;
}

@end

void launchCocoaApp(int argc, char** argv, main_callback_t callback) {
    LOG_DEBUG(@"Launching Cocoa framework");
    @autoreleasepool {

        // The main method gets called once the framework finished launching
        AppDelegate* delegate = [[AppDelegate alloc] init];
        delegate.argc = argc;
        delegate.argv = argv;
        delegate.callback = callback;

        // Standard app setup
        NSApplication *app = [NSApplication sharedApplication];
        app.delegate = delegate;

        // Regular means it's a normal app that shows up in the dock and can be tabbed
        [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];

        // Bring the app to the foreground
        [NSApp activateIgnoringOtherApps:YES];

        // Start the Cocoa event loop (must be on the main thread)
        [NSApp run];
    }

}
