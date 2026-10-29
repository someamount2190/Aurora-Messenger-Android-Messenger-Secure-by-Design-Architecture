rootProject.name = "aurora"

// Dependency resolution. `libs/maven` is an in-repo local Maven repository bundled
// with the project (no external sibling folders):
//  - aura-crypto (com.aura) — Aurora's own crypto module, built from /crypto and
//    published into this repo (see crypto/README and crypto/build.gradle.kts).
// The crypto stack is pure-JVM now (Bouncy Castle + Google Tink); nothing links liboqs,
// but its unused jar is still vendored under libs/maven/org/openquantumsafe and should be
// deleted. Every other dependency resolves from google()/mavenCentral() at build time.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven {
            name = "RepoLocal"
            url  = uri(rootProject.projectDir.resolve("libs/maven"))
        }
        google()
        mavenCentral()
        maven {
            name = "JitPack"
            url  = uri("https://jitpack.io")
        }
    }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":app")
