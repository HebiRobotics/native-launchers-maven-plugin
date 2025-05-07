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

typedef int (*main_callback_t)(int argc, char **argv);

@interface AppDelegate : NSObject <NSApplicationDelegate>
@property (nonatomic, assign) int argc;
@property (nonatomic, assign) char **argv;
@property (nonatomic, assign) main_callback_t callback;
@end

@implementation AppDelegate

- (void)applicationDidFinishLaunching:(NSNotification *)aNotification  {
    NSLog(@"Cocoa finished launching");

    // Start the actual main in a background thread as the main thread is busy
    // running the Cocoa event loop
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        NSLog(@"Handing over application logic to background thread");
        self.callback(self.argc, self.argv);
    });

    // App setup
    dispatch_async(dispatch_get_main_queue(), ^{
        // Regular means it's a normal app that shows up in the dock and can be tabbed
        [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];

        // Bring the app to the foreground
        [NSApp activateIgnoringOtherApps:YES];
    });

}

- (BOOL) applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)app {
    return YES;
}

@end

void launchCocoa(int argc, char** argv, main_callback_t callback) {
    NSLog(@"Launching Cocoa framework");

    @autoreleasepool {
        AppDelegate* delegate = [[AppDelegate alloc] init];
        delegate.argc = argc;
        delegate.argv = argv;
        delegate.callback = callback;

        NSApplication *app = [NSApplication sharedApplication];
        app.delegate = delegate;

        [NSApp run];
    }
}
