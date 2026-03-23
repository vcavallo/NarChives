{
  description = "Narchives - Nostr Web Archive Reader for Android";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            android_sdk.accept_license = true;
            allowUnfree = true;
          };
        };

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          cmdLineToolsVersion = "11.0";
          platformToolsVersion = "35.0.2";
          buildToolsVersions = [ "35.0.0" "34.0.0" ];
          platformVersions = [ "35" "34" ];
          includeEmulator = false;
          includeNDK = false;
          includeSources = false;
          includeSystemImages = false;
          extraLicenses = [
            "android-sdk-license"
            "android-sdk-preview-license"
            "android-googletv-license"
            "google-gdk-license"
            "intel-android-extra-license"
            "intel-android-sysimage-license"
            "mips-android-sysimage-license"
          ];
        };

        androidSdk = androidComposition.androidsdk;
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            pkgs.jdk17
            pkgs.gradle
            androidSdk
            pkgs.nak
            pkgs.kotlin
          ];

          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk17.home}";
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/35.0.0/aapt2";

          shellHook = ''
            echo "Narchives dev environment loaded"
            echo "  JDK: $(java -version 2>&1 | head -1)"
            echo "  Android SDK: $ANDROID_HOME"
            echo "  nak: $(nak --version 2>/dev/null || echo 'available')"

            # Generate local.properties for Gradle
            if [ ! -f local.properties ] || ! grep -q "$ANDROID_HOME" local.properties 2>/dev/null; then
              echo "sdk.dir=$ANDROID_HOME" > local.properties
              echo "  Generated local.properties"
            fi
          '';
        };
      }
    );
}
