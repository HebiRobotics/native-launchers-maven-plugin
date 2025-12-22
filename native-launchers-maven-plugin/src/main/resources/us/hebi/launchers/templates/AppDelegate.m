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
@property (atomic, assign) BOOL isProcessUsed;
@end

@implementation AppDelegate

#ifdef ENABLE_COCOA_FILE_HANDLER

// Unique argument that serves as a marker for internal use
#define IGNORE_HANDLER_ARGUMENT "--launcher-ignore-openFiles"

// This captures files opened via Finder / Apple Events. In order to emulate similar
// behavior to Windows & Linux, we start a separate main function for each file with
// the file as a parameter.
- (void)application:(NSApplication *)sender openFiles:(NSArray<NSString *> *)filenames {
    // Guard: ignore this event if this process was launched via files event
    if ([[[NSProcessInfo processInfo] arguments] containsObject:@(IGNORE_HANDLER_ARGUMENT)]) {
        [sender replyToOpenOrPrint:NSApplicationDelegateReplySuccess];
        return;
    }

    // Events handler
    for (NSString *filename in filenames) {

        // Try to launch in the same process if possible
        if (!self.isProcessUsed && self.argc <= 1) {
            LOG_DEBUG(@"Using current process for file: %@", filename);
            // Modify self.args to include the new file path
            // (launch in applicationDidFinishLaunching)
            char **argv = malloc(3 * sizeof(char *));
            argv[0] = self.argv[0];
            argv[1] = strdup([filename fileSystemRepresentation]);
            argv[2] = NULL;

            self.argv = argv;
            self.argc = 2;
            self.isProcessUsed = YES;
            continue;
        }

        // Otherwise launch completely new instances. Note that macOS may send the
        // same open event to the new instance, so we add a special guard argument
        // to avoid infinite launches. The guard is removed before calling main.
        LOG_DEBUG(@"Launching new instance for file: %@", filename);
        NSTask *task = [[NSTask alloc] init];
        NSString *executablePath = [[NSBundle mainBundle] executablePath];
        [task setExecutableURL:[NSURL fileURLWithPath:executablePath]];
        [task setArguments:@[filename, @(IGNORE_HANDLER_ARGUMENT)]];

        NSError *error = nil;
        if (![task launchAndReturnError:&error]) {
            LOG_DEBUG(@"Failed to launch task: %@", error.localizedDescription);
        }

    }

    // Let the system know that the event was handled. Note that there is
    // no guarantee about when this gets acknowledged.
    [sender replyToOpenOrPrint:NSApplicationDelegateReplySuccess];

}
#endif

- (void)applicationDidFinishLaunching:(NSNotification *)aNotification  {
    LOG_DEBUG(@"Cocoa finished launching")
    self.isProcessUsed = YES;

#ifdef ENABLE_COCOA_FILE_HANDLER
    // Prevent guard argument added by file open events from going into the app
    if (self.argc > 1 && strcmp(self.argv[self.argc-1], IGNORE_HANDLER_ARGUMENT) == 0) {
        self.argc -= 1;
    }
#endif

    // Start the actual main in a background thread as the main thread is busy
    // running the Cocoa event loop
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        LOG_DEBUG(@"Handing over application logic to background thread");
        self.callback(self.argc, self.argv);

        // Close the application once the callback is done executing
        dispatch_async(dispatch_get_main_queue(), ^{
            LOG_DEBUG(@"Terminating Cocoa");
            [NSApp terminate:nil];
        });
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
