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
@property (nonatomic, assign) int processCount; // Tracks spawned NSTasks
@end

@implementation AppDelegate

#ifdef ENABLE_COCOA_FILE_HANDLER

// Unique argument that serves as a marker for internal use
#define IGNORE_HANDLER_ARGUMENT "--launcher-ignore-openFiles"

// This captures files opened via Finder / Apple Events. In order to emulate similar
// behavior to Windows & Linux, we start a separate main function for each file with
// the file as a parameter. Note that macOS may send the same open event to the new
// instance, so we add a special guard argument to avoid infinite launches.
// The guard is removed before calling main.
- (void)application:(NSApplication *)sender openFiles:(NSArray<NSString *> *)filenames {
    if (![[[NSProcessInfo processInfo] arguments] containsObject:@(IGNORE_HANDLER_ARGUMENT)]) {
        for (NSString *filename in filenames) {
            [self launchTaskWithArguments:@[filename, @(IGNORE_HANDLER_ARGUMENT)]];
        }
    }
    [sender replyToOpenOrPrint:NSApplicationDelegateReplySuccess];
}

// Handle re-activation (e.g. double clicking app while children are running)
// macOS typically uses a single instance per app, but if we can have multiple instances
// due to file handling, we also need to launch separate instances of the main application.
// Otherwise a click would always restart with the potentially same file argument.
- (BOOL)applicationShouldHandleReopen:(NSApplication *)sender hasVisibleWindows:(BOOL)flag {
    [self launchTaskWithArguments:@[@(IGNORE_HANDLER_ARGUMENT)]];
    return YES;
}

// Make sure that the guard argument gets filtered before starting the main application
- (void)applicationWillFinishLaunching:(NSNotification *)notification {
    if (self.argc > 1 && strcmp(self.argv[self.argc-1], IGNORE_HANDLER_ARGUMENT) == 0) {
        self.argc -= 1;
    }
}

- (void)launchTaskWithArguments:(NSArray<NSString *> *)arguments {
    LOG_DEBUG(@"Launching new instance with arguments: %@", arguments);

    NSTask *task = [[NSTask alloc] init];
    NSString *executablePath = [[NSBundle mainBundle] executablePath];
    [task setExecutableURL:[NSURL fileURLWithPath:executablePath]];
    [task setArguments:arguments];

    // Count on the main thread to avoid race conditions. The termination
    // runs on a background thread, so we need to bounce back to main.
    self.processCount++;
    task.terminationHandler = ^(NSTask *t) {
        dispatch_async(dispatch_get_main_queue(), ^{
            self.processCount--;
            [self checkForTermination];
        });
    };

    // Revert count immediately if the launch fails
    NSError *error = nil;
    if (![task launchAndReturnError:&error]) {
        LOG_DEBUG(@"Failed to launch task: %@", error.localizedDescription);
        self.processCount--;
    }

}
#endif

- (void)applicationDidFinishLaunching:(NSNotification *)aNotification  {
    // Having launched children at this point means that the process was
    // started from a file event that already started a separate process.
    // Thus, we don't need to display anything and just keep the process
    // for future file events.
    if (self.processCount > 0) {
        LOG_DEBUG(@"Holding on to process as an event handler")
        return;
    }

    // Elevate to a standard app so it appears in the Dock and App Switcher
    LOG_DEBUG(@"Cocoa finished launching")
    [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
    [NSApp activateIgnoringOtherApps:YES];

    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        LOG_DEBUG(@"Handing over application logic to background thread");
        self.processCount++;
        self.callback(self.argc, self.argv);

        dispatch_async(dispatch_get_main_queue(), ^{
            // Hide from the dock again
            [NSApp setActivationPolicy:NSApplicationActivationPolicyAccessory];
            self.processCount--;
            [self checkForTermination];
        });
    });
}


// Helper to centralize exit logic
- (void)checkForTermination {
    if (self.processCount <= 0) {
        LOG_DEBUG(@"All tasks finished. Terminating.");
        [NSApp terminate:nil];
    }
}

- (BOOL) applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)app {
    return NO;
}

- (BOOL) applicationSupportsSecureRestorableState:(NSApplication *)app {
    return YES;
}

@end

void launchCocoaApp(int argc, char** argv, main_callback_t callback) {
    LOG_DEBUG(@"Launching Cocoa framework");
    @autoreleasepool {
        AppDelegate* delegate = [[AppDelegate alloc] init];
        delegate.argc = argc;
        delegate.argv = argv;
        delegate.callback = callback;

        NSApplication *app = [NSApplication sharedApplication];
        app.delegate = delegate;

        // Start as Accessory so parent doesn't clutter Dock while children run
        [NSApp setActivationPolicy:NSApplicationActivationPolicyAccessory];
        [NSApp activateIgnoringOtherApps:YES];
        [NSApp run];
    }
}