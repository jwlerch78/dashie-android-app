pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Vendored third-party binaries (2026-08-17). jitpack is GONE, deliberately, and its
        // absence is the proof: these nine AARs reach the camera-as-RTSP and VAD paths, jitpack
        // builds from source on demand, and Gradle verifies no checksums — so a changed upstream
        // (or a substituted build) would have entered the shipped APK silently. Two failure
        // chains die with it: upstream-gone + cache-evicted (every cold/stranger build breaks),
        // and jitpack-serves-different-bytes.
        //
        // A local maven LAYOUT rather than flat `app/libs/` files, so coordinates, versions and
        // — critically — the POM dependency graph survive: `library-2.6.1.pom` alone declares 8
        // <dependency> entries, which a bare-AAR vendoring would have forced us to hand-redeclare.
        // Because the coordinates are unchanged, no dependency DECLARATION changed anywhere.
        //
        // Bytes, licences and provenance: third_party/m2/MANIFEST.sha256 (read its header before
        // regenerating anything) · texts in third_party/licenses/.
        maven {
            url = uri("${rootDir}/third_party/m2")
            content {
                includeGroup("com.github.gkonovalov.android-vad")
                includeGroup("com.github.pedroSG94")
                includeGroup("com.github.pedroSG94.RootEncoder")
            }
        }
    }
}

rootProject.name = "Dashie"
include(":app")
include(":microfrontend")
