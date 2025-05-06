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
