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
// This captures files opened via Finder / Apple Events. In order to emulate similar
// behavior to Windows & Linux, we start a separate main function for each file with
// the file as a parameter.
- (void)application:(NSApplication *)sender openFiles:(NSArray<NSString *> *)filenames {
    // Reply to the OS that the event was handled to prevent infinite launches
    [sender replyToOpenOrPrint:NSApplicationDelegateReplySuccess];

    // Absolute path to executable (more robust than argv[0])
    NSString *executablePath = [[NSBundle mainBundle] executablePath];

    for (NSString *filename in filenames) {
        LOG_DEBUG(@"Opening file: %@", filename);

        // Launch in the same process if possible
        if (!self.isProcessUsed && self.argc <= 1) {
            // Modify self.args to include the new file path
            // (launch in applicationDidFinishLaunching)
            char **argv = malloc(3 * sizeof(char *));
            argv[0] = strdup([executablePath fileSystemRepresentation]);
            argv[1] = strdup([filename fileSystemRepresentation]);
            argv[2] = NULL;

            self.argv = argv;
            self.argc = 2;
            self.isProcessUsed = YES;
            continue;
        }

        // Otherwise launch completely new instances. We do this in the main
        // queue to make sure that the event success has been handled, so the
        // other processes don't see the same event and cause infinite launches.
        dispatch_async(dispatch_get_main_queue(), ^{
            LOG_DEBUG(@"Launching new instance for file: %@", filename);
            NSTask *task = [[NSTask alloc] init];
            [task setExecutableURL:[NSURL fileURLWithPath:executablePath]];
            [task setArguments:@[filename]];

            NSError *error = nil;
            if (![task launchAndReturnError:&error]) {
                LOG_DEBUG(@"Failed to launch task: %@", error.localizedDescription);
            }
        });

    }

}
#endif

- (void)applicationDidFinishLaunching:(NSNotification *)aNotification  {
    LOG_DEBUG(@"Cocoa finished launching")
    self.isProcessUsed = YES;

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
