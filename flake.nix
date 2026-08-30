{
  description = "motd dev shell: JDK 21 + Android SDK (CI remains the canonical build env)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/767b0d3ec98a143ad9ed7dfc0d5553510ac27133";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };
        nativeNdkVersion = pkgs.lib.removePrefix "ANDROID_NDK_VERSION="
          (builtins.head (pkgs.lib.filter
            (line: pkgs.lib.hasPrefix "ANDROID_NDK_VERSION=" line)
            (pkgs.lib.splitString "\n" (builtins.readFile ./third_party/sing-box/source.lock))));
        # Match compileSdk/buildTools used by the current Gradle configuration.
        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "37.0" ];
          buildToolsVersions = [ "36.0.0" ];
          platformToolsVersion = "35.0.2";
          includeEmulator = false;
          includeSystemImages = false;
        };
        androidSdk = androidComposition.androidsdk;
        sdkRoot = "${androidSdk}/libexec/android-sdk";
        # Native release work needs the same NDK used for the checked-in
        # libbox AAR. Keep it out of the ordinary shell so UI/JVM work does
        # not pay for the 690 MiB toolchain closure.
        nativeAndroidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "37.0" ];
          buildToolsVersions = [ "36.0.0" ];
          platformToolsVersion = "35.0.2";
          includeNDK = true;
          ndkVersions = [ nativeNdkVersion ];
          includeEmulator = false;
          includeSystemImages = false;
        };
        nativeAndroidSdk = nativeAndroidComposition.androidsdk;
        nativeAndroidSdkRoot = "${nativeAndroidSdk}/libexec/android-sdk";
        # Opt-in, large emulator closure for the local headless E2E loop. Keeping this separate
        # avoids making every ordinary build fetch an API image and emulator runtime.
        # This shell only runs emulator/avdmanager/adb; headless.sh shells back out to the
        # default shell for Gradle, so no platform or build-tools beyond the AVD's API level.
        emulatorComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "34" ];
          buildToolsVersions = [ ];
          platformToolsVersion = "35.0.2";
          includeEmulator = true;
          includeSystemImages = true;
          systemImageTypes = [ "default" ];
          abiVersions = [ "x86_64" ];
        };
        emulatorSdk = emulatorComposition.androidsdk;
        emulatorSdkRoot = "${emulatorSdk}/libexec/android-sdk";
      in {
        devShells.default = pkgs.mkShell {
          # imagemagick: test/e2e/showcase-composite.sh merges the light/dark
          # showcase captures into the tracked diagonal-split screenshots.
          packages = [ pkgs.jdk21 pkgs.kotlin-language-server pkgs.nodejs_22 pkgs.imagemagick pkgs.actionlint androidSdk ];
          JAVA_HOME = pkgs.jdk21.home;
          ANDROID_HOME = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          # AGP downloads a dynamically-linked aapt2 that won't run outside FHS;
          # point Gradle at the Nix-provided one instead.
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/36.0.0/aapt2 -Dorg.gradle.workers.max=2";
        };
        devShells.native = pkgs.mkShell {
          packages = [ pkgs.jdk21 nativeAndroidSdk ];
          JAVA_HOME = pkgs.jdk21.home;
          ANDROID_HOME = nativeAndroidSdkRoot;
          ANDROID_SDK_ROOT = nativeAndroidSdkRoot;
          ANDROID_NDK_HOME = "${nativeAndroidSdkRoot}/ndk/${nativeNdkVersion}";
          ANDROID_NDK_ROOT = "${nativeAndroidSdkRoot}/ndk/${nativeNdkVersion}";
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${nativeAndroidSdkRoot}/build-tools/36.0.0/aapt2 -Dorg.gradle.workers.max=2";
        };
        devShells.emulator = pkgs.mkShell {
          packages = [ pkgs.jdk21 emulatorSdk ];
          JAVA_HOME = pkgs.jdk21.home;
          ANDROID_HOME = emulatorSdkRoot;
          ANDROID_SDK_ROOT = emulatorSdkRoot;
          LANG = "C.UTF-8";
          LC_ALL = "C.UTF-8";
        };
        # Lockfile-backed native fixture shell. Keep registry lookups out of E2E scripts so Soju,
        # Ergo, ZNC, and both REALITY implementations are reproducible from flake.lock.
        devShells.e2e-stack = pkgs.mkShell {
          packages = with pkgs; [
            ergochat
            soju
            netcat-openbsd
            openssl
            python3
            sing-box
            xray
            znc
          ];
          MOTD_E2E_STACK_SHELL = "1";
          LANG = "C.UTF-8";
          LC_ALL = "C.UTF-8";
        };
        devShells.libbox = pkgs.mkShell {
          # Deliberately omit the NDK: Nix's Android SDK composition fetches a
          # 690 MiB archive before the shell can start. build-libbox.sh accepts
          # a verified local r28 archive (or LIBBOX_NDK_HOME) instead.
          # Google's prebuilt NDK host tools use the FHS interpreter
          # /lib64/ld-linux-x86-64.so.2.  Guix does not provide that path, so
          # build-libbox.sh patches the verified *extracted cache* (never the
          # downloaded archive) to use these pinned Nix runtime paths.
          packages = [ pkgs.go_1_25 pkgs.git pkgs.gnumake pkgs.unzip pkgs.jdk21 pkgs.patchelf pkgs.zlib ];
          JAVA_HOME = pkgs.jdk21.home;
          LIBBOX_NDK_HOST_LOADER = pkgs.stdenv.cc.bintools.dynamicLinker;
          LIBBOX_NDK_HOST_RPATH = "${pkgs.zlib}/lib:${pkgs.stdenv.cc.cc.lib}/lib";
        };
      });
}
