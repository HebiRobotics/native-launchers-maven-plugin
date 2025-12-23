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
#include <sys/file.h>

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

/**
 * Advisory lock to determine which process is currently responsible for
 * spawning new instances. If the leader terminates, the kernel releases
 * the lock, allowing a survivor to take over.
 */
- (BOOL)isLeader {
    static int lockFd = -1;
    if (lockFd != -1) return YES;

    // macOS routes events based on the bundleID, so it's a good unique identifier
    NSString *uniqueID = [[NSBundle mainBundle] bundleIdentifier];

    // generate a unique fallback based on the absolute path in case the launcher
    // does not live inside a bundle
    if (uniqueID == nil || uniqueID.length == 0) {
        NSString *execPath = [[NSBundle mainBundle] executablePath];
        uniqueID = [NSString stringWithFormat:@"path-hash-%lx", (unsigned long)[execPath hash]];
    }

    // Use the absolute path of the executable to create a unique identifier
    NSString *lockName = [NSString stringWithFormat:@"launcher-%@.lock", uniqueID];
    NSString *lockPath = [NSTemporaryDirectory() stringByAppendingPathComponent:lockName];

    int fd = open([lockPath fileSystemRepresentation], O_CREAT | O_RDWR, 0666);
    if (fd < 0) return NO;

    // Advisory lock: kernel releases this automatically if process dies
    if (flock(fd, LOCK_EX | LOCK_NB) == 0) {
        lockFd = fd; // This process is now the designated launcher
        LOG_DEBUG(@"Acquired leader lock: %@", lockPath);
        return YES;
    }

    close(fd);
    return NO;
}

/*
This captures files opened via Finder / Apple Events. In order to emulate similar
behavior to Windows & Linux, we start a separate main function for each file with
the file as a parameter. Note that macOS may send the same open event to the new
instance, so we keeps launches only to the primary instance.
*/
- (void)application:(NSApplication *)sender openFiles:(NSArray<NSString *> *)filenames {
    if ([self isLeader]) {
        for (NSString *filename in filenames) {

            // Try to launch in the same process if possible. We modify
            // the input args rather than spawning a new process.
            if (!self.isProcessUsed && self.argc <= 1) {
                LOG_DEBUG(@"Using current process for file: %@", filename);
                char **argv = malloc(3 * sizeof(char *));
                argv[0] = self.argv[0];
                argv[1] = strdup([filename fileSystemRepresentation]);
                argv[2] = NULL;

                self.argv = argv;
                self.argc = 2;
                self.isProcessUsed = YES;
                continue;
            }

            [self launchTaskWithArguments:@[filename]];
        }
    }
    [sender replyToOpenOrPrint:NSApplicationDelegateReplySuccess];
}

/*
Handle re-activation (e.g. double clicking app while children are running)
macOS typically uses a single instance per app, but if we can have multiple instances
due to file handling, we also need to launch separate instances of the main application.
Otherwise a click would always restart with the potentially same file argument.
*/
- (BOOL)applicationShouldHandleReopen:(NSApplication *)sender hasVisibleWindows:(BOOL)flag {
    if ([self isLeader]) {
        [self launchTaskWithArguments:@[]];
    }
    return YES;
}

- (void)launchTaskWithArguments:(NSArray<NSString *> *)arguments {
    LOG_DEBUG(@"Launching new instance with arguments: %@", arguments);

    NSTask *task = [[NSTask alloc] init];
    NSString *executablePath = [[NSBundle mainBundle] executablePath];
    [task setExecutableURL:[NSURL fileURLWithPath:executablePath]];
    [task setArguments:arguments];

    NSError *error = nil;
    if (![task launchAndReturnError:&error]) {
        LOG_DEBUG(@"Failed to launch task: %@", error.localizedDescription);
    }

}
#endif

- (void)applicationDidFinishLaunching:(NSNotification *)aNotification  {
    LOG_DEBUG(@"Cocoa finished launching")
    self.isProcessUsed = YES;

    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        LOG_DEBUG(@"Starting application logic on background thread");
        self.callback(self.argc, self.argv);

        // Close the application once the callback is done executing
        dispatch_async(dispatch_get_main_queue(), ^{
            LOG_DEBUG(@"Terminating Cocoa");
            [NSApp terminate:nil];
        });
    });
}

- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)app {
    return NO; // it gets closed when the main callback is done
}

- (BOOL)applicationSupportsSecureRestorableState:(NSApplication *)app {
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