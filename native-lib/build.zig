// Docs: https://ziglang.org/documentation/0.8.0/#Zig-Build-System
const Builder = @import("std").build.Builder;

pub fn build(b: *Builder) void {
    // Standard target options allows the person running `zig build` to choose
    // what target to build for. Here we do not override the defaults, which
    // means any target is allowed, and the default is native. Other options
    // for restricting supported target set are available.
    const target = b.standardTargetOptions(.{});

    // Standard release options allow the person running `zig build` to select
    // between Debug, ReleaseSafe, ReleaseFast, and ReleaseSmall.
    const mode = b.standardReleaseOptions();

    const exe = b.addExecutable("zig_hello_world", "src/main/zig/hello_world.zig");
    exe.setTarget(target);
    exe.setBuildMode(mode);
    exe.install();

    const cflags = &[_][]const u8{
        "--std=c99",
        "-Wall",
        "-Wextra",
        "-Werror",
    };

    const helloWorldWrapper = b.addExecutable("main_wrapper", null);
    helloWorldWrapper.setTarget(target);
    helloWorldWrapper.setBuildMode(mode);
    helloWorldWrapper.addCSourceFile("src/main/zig/main_wrapper.c", cflags);
    helloWorldWrapper.linkLibC();
    helloWorldWrapper.strip = true;
    helloWorldWrapper.addIncludeDir("target/native/windows-x86_64");
    helloWorldWrapper.addLibPath("target/native/windows-x86_64");
    helloWorldWrapper.linkSystemLibraryName("native-lib");
    helloWorldWrapper.install();

    const printDirWrapper = b.addExecutable("print_dir", null);
    printDirWrapper.setTarget(target);
    printDirWrapper.setBuildMode(mode);
    printDirWrapper.addCSourceFile("src/main/zig/print_directory.c", cflags);
    printDirWrapper.linkLibC();
    printDirWrapper.strip = true;
    printDirWrapper.addIncludeDir("target/native/windows-x86_64");
    printDirWrapper.addLibPath("target/native/windows-x86_64");
    printDirWrapper.linkSystemLibraryName("native-lib");
    printDirWrapper.install();

    const run_cmd = exe.run();
    run_cmd.step.dependOn(b.getInstallStep());
    if (b.args) |args| {
        run_cmd.addArgs(args);
    }

    const run_step = b.step("run", "Run the app");
    run_step.dependOn(&run_cmd.step);
}